package com.easymall.refund;

import com.easymall.commonent.SpringContext;
import com.easymall.entity.enums.LogisticsStatusEnum;
import com.easymall.entity.enums.OrderItemStatusEnum;
import com.easymall.entity.enums.OrderStatusEnum;
import com.easymall.entity.enums.PayChannelEnum;
import com.easymall.entity.po.OrderInfo;
import com.easymall.entity.po.OrderItem;
import com.easymall.entity.po.OrderLogisticsInfo;
import com.easymall.entity.query.OrderInfoQuery;
import com.easymall.entity.query.OrderItemQuery;
import com.easymall.entity.query.OrderLogisticsInfoQuery;
import com.easymall.entity.query.ProductSkuQuery;
import com.easymall.exception.BusinessException;
import com.easymall.mappers.OrderInfoMapper;
import com.easymall.mappers.OrderItemMapper;
import com.easymall.mappers.OrderLogisticsInfoMapper;
import com.easymall.mappers.ProductSkuMapper;
import com.easymall.mappers.RefundRequestMapper;
import com.easymall.entity.po.ProductSku;
import com.easymall.service.PayChannel;
import com.easymall.utils.StringTools;
import com.easymall.validation.InputValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 商城退款业务服务。每次申请退回一条订单明细的全部实付金额。
 *
 * <p>事务 A 负责校验订单归属、占用明细和保存退款单。提交后，在事务外调用支付渠道。
 * 渠道结果明确后，事务 B 一起更新退款单、明细、主订单和需要归还的库存。</p>
 *
 * <p>支付渠道的资金变化无法随本地事务回滚。遇到超时或落库失败时，保留明细占用和原渠道退款号，
 * 由定时任务查证结果，再完成商城侧的更新。</p>
 */
@Service
public class RefundService {
    private static final Logger LOG = LoggerFactory.getLogger(RefundService.class);
    private static final int NORMAL = OrderItemStatusEnum.NORMAL.getStatus();
    private static final int REFUNDING = OrderItemStatusEnum.REFUNDING.getStatus();
    private static final int REFUNDED = OrderItemStatusEnum.REFUND.getStatus();
    // 主订单处于部分退款时，剩余明细仍可能符合退款条件，具体由明细旧状态决定。
    private static final Set<Integer> REFUNDABLE = Set.of(OrderStatusEnum.PAID.getStatus(),
            OrderStatusEnum.SHIPPED.getStatus(), OrderStatusEnum.PARTIALLY_REFUNDED.getStatus());

    private final RefundRequestMapper requests;
    private final OrderInfoMapper<OrderInfo, OrderInfoQuery> orders;
    private final OrderItemMapper<OrderItem, OrderItemQuery> items;
    private final OrderLogisticsInfoMapper<OrderLogisticsInfo, OrderLogisticsInfoQuery> logistics;
    private final ProductSkuMapper<ProductSku, ProductSkuQuery> stock;
    private final TransactionTemplate transaction;

    public RefundService(RefundRequestMapper requests, OrderInfoMapper<OrderInfo, OrderInfoQuery> orders,
                         OrderItemMapper<OrderItem, OrderItemQuery> items,
                         OrderLogisticsInfoMapper<OrderLogisticsInfo, OrderLogisticsInfoQuery> logistics,
                         ProductSkuMapper<ProductSku, ProductSkuQuery> stock, PlatformTransactionManager manager) {
        this.requests = requests;
        this.orders = orders;
        this.items = items;
        this.logistics = logistics;
        this.stock = stock;
        this.transaction = new TransactionTemplate(manager);
        // 每次 execute 都开启并提交自己的短事务。申请占用和最终落库各自提交。
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // 拿到主单行锁后读取最新的已提交数据，避免继续沿用等待锁之前的查询快照。
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        // 这里的 10 秒限制本地数据库事务，支付渠道调用使用其自身的网络超时配置。
        transaction.setTimeout(10);
    }

    /**
     * 申请退款。通过 Spring 代理进入此方法时，挂起调用方事务，渠道调用期间不持有退款业务的数据库锁。
     *
     * @param userId 当前登录用户ID，用于校验明细归属
     * @param itemId 要全额退款的订单明细ID
     * @param requestId 客户端提供的退款请求号，同一次申请的网络重试沿用此编号
     * @return 商城当前记录的退款结果，也是前端展示状态的依据
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RefundView apply(String userId, String itemId, String requestId) {
        // 校验 userId 和 子订单ID itemId
        InputValidator.requireText(userId, 15);
        InputValidator.requireText(itemId, 40);
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{16,64}")) {
            throw new BusinessException("退款 requestId 格式无效");
        }
        // 生成退款单号
        String refundId = StringTools.createPayOrderId();
        Reservation reservation;
        try {
            // execute 返回时事务 A 已提交，占用结果、渠道退款号和金额均已保存。
            reservation = transaction.execute(status -> reserve(userId, itemId, requestId, refundId));
        } catch (DuplicateKeyException duplicate) {
            // 并发撞到唯一键时，失败事务已回滚。另开事务查找已提交的原请求。
            RefundRequest request = transaction.execute(status -> requests.findByRequest(userId, requestId));
            if (request == null) throw duplicate;
            requireSameItem(request, itemId);
            return request.view();
        }
        RefundRequest request = reservation.request();
        // 仅新建退款单的请求发起首次渠道调用。重复请求直接返回已有记录的状态。
        if (!reservation.created()) {
            return request.view();
        }
        return process(request, request.version(), false);
    }

    /**
     * 在事务 A 中校验并占用明细。同一事务提交明细状态和退款单，任一写入失败时一起回滚。
     */
    private Reservation reserve(String userId, String itemId, String requestId, String refundId) {
        // 先定位明细所属主单。发货、确认收货和退款落库也会锁这条主单记录。
        OrderItem initial = items.selectByOrderItemId(itemId);
        if (initial == null) {
            throw new BusinessException("订单不存在");
        }
        //查询退款子订单的主订单
        OrderInfo order = orders.selectOrderForUpdate(initial.getOrderId());
        if (order == null || !userId.equals(order.getUserId())) {
            throw new BusinessException("订单不存在");
        }
        // 先按用户和请求号查历史结果，同一请求号重放时仍返回原结果，包括原请求已明确失败的情况。
        RefundRequest previous = requests.findByRequest(userId, requestId);
        if (previous != null) {
            requireSameItem(previous, itemId);
            return new Reservation(previous, false);
        }
        // 不同请求号也可能指向同一明细。有处理中、待确认或已成功的退款时，共享已有记录。
        RefundRequest active = requests.findActive(itemId);
        // 如果有处理中的退款，则共享同一笔退款。
        if (active != null) {
            return new Reservation(active, false);
        }
        // 检查订单状态是否允许退款
        if (order.getOrderStatus() == null || !REFUNDABLE.contains(order.getOrderStatus())) {
            throw new BusinessException("订单无法退款");
        }
        // 检查支付渠道是否有效
        if (PayChannelEnum.getByPayScene(order.getPayScene()) == null || order.getPayOrderId() == null || order.getPayOrderId().isBlank()) {
            throw new BusinessException("原订单支付渠道无效");
        }

        // 退款金额取自下单时保存的明细实付金额，可覆盖该明细中的多件同规格商品。
        BigDecimal amount = initial.getItemAmount();
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2) {
            throw new BusinessException("退款金额无效");
        }
        // CAS 条件更新。SET 写入 REFUNDING，WHERE 同时匹配明细 ID 和旧状态 NORMAL。
        if (items.updateByParam(itemStatus(REFUNDING), itemCondition(itemId, NORMAL)) != 1) {
            throw new BusinessException("该明细已退款或正在退款");
        }
        // 渠道退款号在建单时取商城退款单号。渠道按原支付单号和此退款号定位同一笔退款。
        RefundRequest request = new RefundRequest(refundId, userId, requestId, order.getOrderId(),
                itemId, order.getPayOrderId(), order.getPayScene(), refundId, amount, RefundStatus.PROCESSING, 0);
        requests.insert(request);

        return new Reservation(request, true);
    }

    /**
     * 记录本次是否刚建单，供申请入口决定是否发起首次渠道调用。
     */
    private record Reservation(RefundRequest request, boolean created) {}

    /**
     * 校验重放请求中的明细与原请求一致。
     */
    private static void requireSameItem(RefundRequest previous, String itemId) {
        if (!previous.orderItemId().equals(itemId)) throw new BusinessException("退款请求号已用于其他明细");
    }

    /**
     * 由定时任务扫描已到期的 PROCESSING 或 UNKNOWN 退款单。
     * PROCESSING 也需要补偿，例如应用可能在提交事务 A 后、获取渠道结果前退出。
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void reconcileDue() {
        for (RefundRequest request : requests.findDue(5)) {
            try {
                // 用查到的旧版本竞争领取。成功时数据库版本加一，并推迟下一次可领取时间。
                if (requests.claim(request.refundId(), request.version()) == 1) {
                    // 旧对象仍保存领取前的版本，落库必须使用加一后的版本。
                    process(request, request.version() + 1, true);
                }
            } catch (RuntimeException error) {
                // 单笔失败时保留记录供后续扫描，并继续处理本批次的其他退款单。
                LOG.warn("退款补偿暂未完成，refundId={}，异常类型={}", request.refundId(), error.getClass().getSimpleName());
            }
        }
    }

    /**
     * 在事务外执行渠道操作，再根据结果开启本地事务。
     *
     * @param request 已持久化的退款单，提供原支付单号、渠道退款号和金额
     * @param version 本次操作持有的数据库版本，补偿时为领取后的新版本
     * @param recovery 为 true 时先查渠道，为 false 时发起首次退款
     */
    private RefundView process(RefundRequest request, long version, boolean recovery) {
        RefundChannelResult result = RefundChannelResult.UNKNOWN;
        try {
            // 获取支付渠道
            PayChannel channel = (PayChannel) SpringContext.getBean(PayChannelEnum.getByPayScene(request.payScene()).getBeanName());
            if (recovery) {
                // 查询渠道退款结果
                result = channel.queryRefund(request.payOrderId(), request.channelRequestNo(), request.amount());
                if (result == RefundChannelResult.NOT_FOUND) {
                    // 渠道明确表示未找到这笔退款才重发，仍沿用原渠道退款号和金额。
                    result = channel.refund(request.payOrderId(), request.channelRequestNo(), request.amount());
                }
            } else {
                // 退款
                result = channel.refund(request.payOrderId(), request.channelRequestNo(), request.amount());
            }
        } catch (RuntimeException error) {
            LOG.warn("退款渠道暂未确认，refundId={}，异常类型={}", request.refundId(), error.getClass().getSimpleName());
        }

        try {
            // 成功才更新退款和库存。首次调用被明确拒绝时释放占用，补偿中的拒绝仍保留待确认状态。
            if (result == RefundChannelResult.SUCCESS) {
                //完成退款
                transaction.executeWithoutResult(status -> complete(request, version, RefundStatus.SUCCESS));
            } else if (result == RefundChannelResult.REJECTED && !recovery) {
                transaction.executeWithoutResult(status -> complete(request, version, RefundStatus.FAILED));
            } else {
                markUnknown(request, version, "CHANNEL_UNCONFIRMED");
            }
            // 渠道调用期间可能有其他实例接管，重新读取数据库中实际生效的结果。
            return requests.find(request.refundId()).view();
        } catch (RuntimeException error) {
            // 渠道可能已经完成退款。即使本地写入失败，也保留原渠道号以便后续查证。
            LOG.warn("退款渠道结果待落库，refundId={}，异常类型={}", request.refundId(), error.getClass().getSimpleName());
            try {
                markUnknown(request, version, "SETTLEMENT_PENDING");
                return requests.find(request.refundId()).view();
            } catch (RuntimeException unavailable) {
                // 数据库暂不可用时先向客户端返回待确认。原退款单可在数据库恢复后被再次扫描。
                LOG.warn("退款记录暂不可读，refundId={}，异常类型={}", request.refundId(), unavailable.getClass().getSimpleName());
                return new RefundView(request.refundId(), request.orderItemId(), request.amount(), RefundStatus.UNKNOWN);
            }
        }
    }

    /** 按版本标记待确认并安排下次查证。明细继续处于退款中，避免生成新的渠道退款号。 */
    private void markUnknown(RefundRequest request, long version, String error) {
        transaction.executeWithoutResult(status -> requests.resolve(request.refundId(), version, RefundStatus.UNKNOWN, error));
    }

    /**
     * 在事务 B 中落库明确的渠道结果。退款单、明细、主单、物流和库存的写入一同提交或回滚。
     * 退款单只有在版本匹配且仍未确认时才允许进入终态。
     */
    private void complete(RefundRequest request, long version, RefundStatus finalStatus) {
        //查询退款的主订单
        OrderInfo order = orders.selectOrderForUpdate(request.orderId());
        if (order == null) {
            throw new IllegalStateException("退款主订单不存在");
        }
        String error = finalStatus == RefundStatus.FAILED ? "CHANNEL_REJECTED" : null;
        // 更新行数为 0 表示当前版本已失效或退款单已经结束，本轮到此为止。
        if (requests.resolve(request.refundId(), version, finalStatus, error) != 1) {
            return;
        }
        // 成功后记录渠道退款号。明确失败后将明细恢复可申请状态，原退款单仍保留为失败记录。
        boolean success = finalStatus == RefundStatus.SUCCESS;
        OrderItem update = itemStatus(success ? REFUNDED : NORMAL);

        if (success) {
            update.setRefundOrderId(request.channelRequestNo());
        }
        //更改状态和记录渠道退款号
        if (items.updateByParam(update, itemCondition(request.orderItemId(), REFUNDING)) != 1) {
            throw new IllegalStateException("退款明细状态已变化");
        }
        if (!success) {
            return;
        }

        // 按实际物流状态判断是否回库，部分退款的主单状态无法区分商品是否已发出。
        // 只有待发货的明细会按原购买数量归还库存，退款终态更新确保库存只归还一次。
        OrderLogisticsInfo shipment = logistics.selectByOrderId(request.orderId());
        Integer shipmentStatus = shipment == null ? null : shipment.getLogisticsStatus();
        if (LogisticsStatusEnum.PENDING_SHIPMENT.getStatus().equals(shipmentStatus)) {
            OrderItem refunded = items.selectByOrderItemId(request.orderItemId());
            //归还商品库存
            if (stock.updateStockBatch(List.of(refunded)) != 1) {
                throw new IllegalStateException("退款库存归还失败");
            }
        }

        // 持有主单行锁时重新读取全部明细。仍在退款中的明细计入剩余明细，全部退款成功后才关闭主单。
        OrderItemQuery detailQuery = new OrderItemQuery();
        detailQuery.setOrderId(request.orderId());
        //查询主订单下的所有明细
        List<OrderItem> details = items.selectList(detailQuery);
        //查看是全部退款还是部分退款(allMatch:查看所有主订单下的子订单的状态是否都是REFUNDED已退款状态，如果是则主订单为已退款否则为部分退款)
        boolean fullyRefunded = details.stream().allMatch(item -> Integer.valueOf(REFUNDED).equals(item.getOrderItemStatus()));
        OrderInfo updateOrder = new OrderInfo();
        updateOrder.setOrderStatus((fullyRefunded ? OrderStatusEnum.REFUNDED : OrderStatusEnum.PARTIALLY_REFUNDED).getStatus());
        //更改退款子订单的主订单的状态
        if (!updateOrder.getOrderStatus().equals(order.getOrderStatus()) && orders.updateByOrderId(updateOrder, request.orderId()) != 1) {
            throw new IllegalStateException("退款订单状态更新失败");
        }
        // 整单已退且尚未发货时取消物流，剩余明细仍需履约的主单保留原物流状态。
        if (fullyRefunded && LogisticsStatusEnum.PENDING_SHIPMENT.getStatus().equals(shipmentStatus)) {
            OrderLogisticsInfo cancelled = new OrderLogisticsInfo();
            cancelled.setLogisticsStatus(LogisticsStatusEnum.CANCELLED.getStatus());
            logistics.updateByOrderId(cancelled, request.orderId());
        }
    }

    /** 读取当前用户该明细的退款状态。只查商城数据库，查不到记录时返回 null。 */
    public RefundView status(String userId, String itemId) {
        InputValidator.requireText(userId, 15);
        InputValidator.requireText(itemId, 40);
        return requests.findByItems(userId, List.of(itemId)).stream().findFirst().map(RefundRequest::view).orElse(null);
    }

    /** 批量填充订单列表的退款展示状态，一次查询覆盖当前页明细。 */
    public void fillStatuses(String userId, List<OrderItem> orderItems) {
        if (orderItems.isEmpty()) return;
        List<String> ids = orderItems.stream().map(OrderItem::getOrderItemId).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return;
        Map<String, RefundStatus> latest = new HashMap<>();
        // SQL 将每条明细的有效记录排在失败记录前，按创建时间倒序。每条明细只取第一条。
        for (RefundRequest request : requests.findByItems(userId, ids)) {
            latest.putIfAbsent(request.orderItemId(), request.status());
        }
        orderItems.forEach(item -> item.setRefundStatus(latest.get(item.getOrderItemId())));
    }

    /** 构造明细更新的 SET 参数，只设置要写入的新状态。 */
    private static OrderItem itemStatus(int value) {
        OrderItem update = new OrderItem();
        update.setOrderItemStatus(value);
        return update;
    }

    /** 构造明细更新的 WHERE 参数，要求明细 ID 和预期旧状态同时匹配。 */
    private static OrderItemQuery itemCondition(String id, int oldStatus) {
        OrderItemQuery query = new OrderItemQuery();
        query.setOrderItemId(id);
        query.setOrderItemStatus(oldStatus);
        return query;
    }
}
