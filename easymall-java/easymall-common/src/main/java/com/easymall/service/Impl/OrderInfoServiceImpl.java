package com.easymall.service.Impl;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.easymall.commonent.RedisComponent;
import com.easymall.commonent.SpringContext;
import com.easymall.entity.config.AppConfig;
import com.easymall.entity.dto.*;
import com.easymall.entity.enums.*;
import com.easymall.entity.po.*;
import com.easymall.entity.query.*;
import com.easymall.exception.BusinessException;
import com.easymall.mappers.*;
import com.easymall.service.PayChannel;
import com.easymall.validation.InputValidator;
import com.easymall.order.CheckoutInput;
import com.easymall.order.OrderRequest;
import com.easymall.order.OrderRequestService;
import com.easymall.order.PaymentCloseResult;
import com.easymall.order.PaymentQueryResult;
import com.easymall.refund.RefundPendingException;
import com.easymall.refund.RefundService;
import com.easymall.refund.RefundStatus;
import com.easymall.task.BusinessTaskService;
import com.easymall.task.TaskSubmission;
import com.easymall.utils.JsonUtils;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.Resource;

import org.apache.commons.lang3.ArrayUtils;
import org.springframework.stereotype.Service;

import com.easymall.entity.vo.PaginationResultVO;
import com.easymall.service.OrderInfoService;
import com.easymall.utils.StringTools;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;


/**
 * 订单信息 业务接口实现
 */
@Service("orderInfoService")
public class OrderInfoServiceImpl implements OrderInfoService {

	private static final Logger log = LoggerFactory.getLogger(OrderInfoServiceImpl.class);
	// 给付款截止时正在处理的渠道请求留出时间，避免刚到期就按查无交易归还库存。
	private static final long EXPIRED_PAYMENT_GRACE_MILLIS = 60_000L;
	@Resource
	private OrderRequestService orderRequestService;
	@Resource
	private RefundService refundService;
	@Resource
	private BusinessTaskService businessTaskService;
	@Resource
	private OrderInfoMapper<OrderInfo, OrderInfoQuery> orderInfoMapper;
	@Resource
	private UserAddressMapper<UserAddress,String> userAddressMapper;
	@Resource
	private ProductInfoMapper<ProductInfo,ProductInfoQuery> productInfoMapper;
	@Resource
	private ProductPropertyValueMapper<ProductPropertyValue,ProductPropertyValueQuery> productPropertyValueMapper;
	@Resource
	private ProductSkuMapper<ProductSku,ProductSkuQuery> productSkuMapper;
	@Resource
	private OrderItemMapper<OrderItem,OrderItemQuery> orderItemMapper;
	@Resource
	private ProductCartMapper<ProductCart,ProductCartQuery> productCartMapper;
	@Resource
	private RedisComponent redisComponent;
	@Resource
	private AppConfig appConfig;
	@Resource
	private OrderLogisticsInfoMapper<OrderLogisticsInfo,OrderLogisticsInfoQuery> orderLogisticsInfoMapper;

	/**
	 * 根据条件查询列表
	 */
	@Override
	public List<OrderInfo> findListByParam(OrderInfoQuery param) {
		return this.orderInfoMapper.selectList(param);
	}

	/**
	 * 根据条件查询列表
	 */
	@Override
	public Integer findCountByParam(OrderInfoQuery param) {
		return this.orderInfoMapper.selectCount(param);
	}

	/**
	 * 分页查询方法
	 */
	@Override
	public PaginationResultVO<OrderInfo> findListByPage(OrderInfoQuery param) {
		int count = this.findCountByParam(param);
		int pageSize = param.getPageSize() == null ? PageSize.SIZE15.getSize() : param.getPageSize();

		SimplePage page = new SimplePage(param.getPageNo(), count, pageSize);
		param.setSimplePage(page);
		List<OrderInfo> list = this.findListByParam(param);
		PaginationResultVO<OrderInfo> result = new PaginationResultVO(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
		return result;
	}

	/**
	 * 新增
	 */
	@Override
	public Integer add(OrderInfo bean) {
		return this.orderInfoMapper.insert(bean);
	}

	/**
	 * 批量新增
	 */
	@Override
	public Integer addBatch(List<OrderInfo> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.orderInfoMapper.insertBatch(listBean);
	}

	/**
	 * 批量新增或者修改
	 */
	@Override
	public Integer addOrUpdateBatch(List<OrderInfo> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.orderInfoMapper.insertOrUpdateBatch(listBean);
	}

	/**
	 * 多条件更新
	 */
	@Override
	public Integer updateByParam(OrderInfo bean, OrderInfoQuery param) {
		StringTools.checkParam(param);
		return this.orderInfoMapper.updateByParam(bean, param);
	}

	/**
	 * 多条件删除
	 */
	@Override
	public Integer deleteByParam(OrderInfoQuery param) {
		StringTools.checkParam(param);
		return this.orderInfoMapper.deleteByParam(param);
	}

	/**
	 * 根据OrderId获取对象
	 */
	@Override
	public OrderInfo getOrderInfoByOrderId(String orderId) {
		return this.orderInfoMapper.selectByOrderId(orderId);
	}

	/**
	 * 根据OrderId修改
	 */
	@Override
	public Integer updateOrderInfoByOrderId(OrderInfo bean, String orderId) {
		return this.orderInfoMapper.updateByOrderId(bean, orderId);
	}

	/**
	 * 根据OrderId删除
	 */
	@Override
	public Integer deleteOrderInfoByOrderId(String orderId) {
		return this.orderInfoMapper.deleteByOrderId(orderId);
	}

	/**
	 * 订单提交
	 * @param userId 用户Id
	 * @param postOrderDTO 订单信息
	 */
	@Override
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public PayInfoDTO postOrder(String userId, PostOrderDTO postOrderDTO) {
		// 校验用户Id
		InputValidator.requireText(userId, 15);
		// 校验postOrderDTO 校验参数、合并相同商品/SKU并排序
		CheckoutInput input = CheckoutInput.normalize(postOrderDTO);
		// Redis中的发货地址只是可选快照；在数据库事务外读取，避免外部等待占用订单锁。
		LogisticsSendDTO sender = null;
		try {
			sender = redisComponent.getLogisticsInfo();
		} catch (RuntimeException unavailable) {
			log.warn("发件人快照不可用; code=SENDER_CACHE_UNAVAILABLE");
		}
		LogisticsSendDTO senderSnapshot = sender;
		//进行判断是否是重复提交或处理并发请求(主要还是校验)
		OrderRequest request = orderRequestService.place(userId, input,
				payOrderId -> createOrders(userId, input.order(), payOrderId, senderSnapshot));

		// 下单成功，获取支付信息
		// 由OrderRequest封装道支付信息里
		PayInfoDTO result = requestResult(request);
		if (!"CREATED".equals(request.getStatus())){
			return result;
		}
		//获取到支付订单号对应的主订单信息
		List<OrderInfo> orders = paymentGroup(userId, request.getPayOrderId());
		// 支付截止时间已随订单持久化，数据库扫描负责超时关单。
		return paymentForGroup(orders, result);
	}

	/** 【正式业务】仅由幂等服务的数据库事务回调调用；不请求支付渠道、不投递Redis任务。 */
	private BigDecimal createOrders(String userId, PostOrderDTO postOrderDTO, String payOrderId, LogisticsSendDTO sendDTO) {
		PayChannelEnum payChannelEnum = PayChannelEnum.getByPayScene(postOrderDTO.getPayMethod());
		if (payChannelEnum == null) {
			throw new BusinessException(ResponseCodeEnum.CODE_600);
		}
		OrderFromTypeEnum orderFromTypeEnum = OrderFromTypeEnum.getByType(postOrderDTO.getOrderFrom());
		if (orderFromTypeEnum == null) {
			throw new BusinessException(ResponseCodeEnum.CODE_600);
		}

		List<PostOrderItemDTO> itemDTOList = postOrderDTO.getOrderList();
		List<String> productIdList = itemDTOList.stream().map(PostOrderItemDTO::getProductId).distinct().toList();

		//查询地址信息
		UserAddress userAddress = userAddressMapper.selectByAddressId(postOrderDTO.getAddressId());
		if (userAddress == null || !userAddress.getUserId().equals(userId)) {
			throw new BusinessException("地址信息不存在");
		}
		//查询商品信息
		ProductInfoQuery productInfoQuery = new ProductInfoQuery();
		productInfoQuery.setProductIdList(productIdList);
		List<ProductInfo> productInfoList = this.productInfoMapper.selectList(productInfoQuery);
		Map<String, ProductInfo> tempProductInfoMap = productInfoList.stream().collect(Collectors.toMap(ProductInfo::getProductId, Function.identity(),
				(data1, data2) -> data2));

		//查询商品属性信息
		ProductPropertyValueQuery propertyValueQuery = new ProductPropertyValueQuery();
		propertyValueQuery.setProductIdList(productIdList);
		List<ProductPropertyValue> propertyValueList = this.productPropertyValueMapper.selectList(propertyValueQuery);
		Map<List<String>, ProductPropertyValue> productPropertyValueMap = propertyValueList.stream().collect(Collectors.toMap(item -> List.of(item.getProductId(), item.getPropertyValueId()), Function.identity(),
				(data1,data2) -> data2));

		//查询商品Sku信息
		ProductSkuQuery skuQuery = new ProductSkuQuery();
		skuQuery.setProductIdList(productIdList);
		List<ProductSku> skuList = this.productSkuMapper.selectList(skuQuery);
		Map<List<String>, ProductSku> productSkuMap = skuList.stream().collect(Collectors.toMap(item -> List.of(item.getProductId(), item.getPropertyValueIds()), Function.identity(),
				(data1,data2) -> data2));

		Date curDate = new Date();

		//主订单
		List<OrderInfo> orderInfoList = new ArrayList<>();
		//订单详情
		List<OrderItem> orderItemList = new ArrayList<>();
		//购物车信息
		List<ProductCart> productCartList = new ArrayList<>();
		//订单物流信息
		//物流信息
		List<OrderLogisticsInfo> orderLogisticsInfoList = new ArrayList<>();

		Map<String,OrderInfo> orderInfoMap = new HashMap<>();

		// 支付业务号由order_request首次预留后固定复用，不能因重试重新生成。

		for (PostOrderItemDTO itemDTO : itemDTOList) {
			ProductInfo productInfo = tempProductInfoMap.get(itemDTO.getProductId());
			if (productInfo == null || !ProductStatusEnum.ON_SALE.getStatus().equals(productInfo.getStatus())) {
				throw new BusinessException("商品不存在或者已下架");
			}
			String propertyValueIds = itemDTO.getPropertyValueIds();
			String[] propertyIdArray = propertyValueIds.split("-");
			List<String> propertyData = new ArrayList<>();
			String cover = null;
			for (String propertyValueId : propertyIdArray) {
				ProductPropertyValue propertyValue = productPropertyValueMap.get(List.of(itemDTO.getProductId(), propertyValueId));
				if (propertyValue == null) {
					throw new BusinessException("商品属性不存在");
				}
				propertyData.add(propertyValue.getPropertyName() + propertyValue.getPropertyValue());
				if (cover == null && !StringTools.isEmpty(propertyValue.getPropertyCover())) {
					cover = propertyValue.getPropertyCover();
				}
			}

			ProductSku productSku = productSkuMap.get(List.of(itemDTO.getProductId(), propertyValueIds));
			if (productSku == null || !StringTools.encodeByMD5(propertyValueIds).equals(productSku.getPropertyValueIdHash())) {
				throw new BusinessException("商品Sku不存在");
			}
			if (productSku.getPrice() == null || productSku.getPrice().signum() <= 0 || productSku.getPrice().scale() > 2) {
				throw new BusinessException("商品价格无效，请联系商家");
			}
			if(productSku.getStock() == null || productSku.getStock() < itemDTO.getBuyCount()){
				throw new BusinessException("商品【" + productInfo.getProductName() + "】库存不足");
			}

			OrderInfo orderInfo = orderInfoMap.get(itemDTO.getProductId());
			if (orderInfo == null) {
				orderInfo = new OrderInfo();
				orderInfo.setOrderId(StringTools.createProductOrderId());
				orderInfo.setUserId(userId);
				orderInfo.setOrderTime(curDate);
				orderInfo.setPaymentExpiresAt(new Date(curDate.getTime() + appConfig.getOrderExpireMinute() * 60_000L));
				orderInfo.setPaymentLinkIssued(false);
				orderInfo.setOrderStatus(OrderStatusEnum.WAIT_PAYMENT.getStatus());
				orderInfo.setAmount(new BigDecimal("0.00"));
				orderInfo.setCommentStatus(OrderCommentStatusEnum.NOT_EVALUATED.getStatus());

				orderInfo.setPayChannel(payChannelEnum.getPayChannel());
				orderInfo.setPayScene(payChannelEnum.getPayScene());
				orderInfo.setPayOrderId(payOrderId);

				orderInfoMap.put(itemDTO.getProductId(), orderInfo);
				orderInfoList.add(orderInfo);


				List<OrderItem> orderItems = new ArrayList<>();
				orderInfo.setOrderItemList(orderItems);

				//记录地址信息
				OrderLogisticsInfo orderLogisticsInfo = new OrderLogisticsInfo();
				orderLogisticsInfo.setOrderId(orderInfo.getOrderId());
				orderLogisticsInfo.setUserId(userId);
				orderLogisticsInfo.setLogisticsStatus(LogisticsStatusEnum.PENDING_SHIPMENT.getStatus());
				orderLogisticsInfo.setReceiverName(userAddress.getAddressee());
				orderLogisticsInfo.setReceiverPhone(userAddress.getPhone());
				orderLogisticsInfo.setReceiverAddress(userAddress.getAddress());

				//设置默认的发货信息
				if (sendDTO != null){
					orderLogisticsInfo.setSenderName(sendDTO.getSenderName());
					orderLogisticsInfo.setSenderPhone(sendDTO.getSenderPhone());
					orderLogisticsInfo.setSenderAddress(sendDTO.getSenderAddress());
				}
				orderLogisticsInfoList.add(orderLogisticsInfo);

			}

			cover = cover == null ? productInfo.getCover().split(",")[0] : cover;
			OrderItem orderItem = new OrderItem();
			orderItem.setOrderId(orderInfo.getOrderId());
			orderItem.setOrderItemId(orderInfo.getOrderId() + "_" + (orderInfo.getOrderItemList().size() + 1));
			orderItem.setCover(cover);
			orderItem.setProductId(itemDTO.getProductId());
			orderItem.setProductName(productInfo.getProductName());
			orderItem.setPropertyValueIdHash(StringTools.encodeByMD5(propertyValueIds));
			orderItem.setPropertyInfo(String.join(":", propertyData));
			orderItem.setItemAmount(productSku.getPrice().multiply(new BigDecimal(itemDTO.getBuyCount())));
			orderItem.setBuyCount(itemDTO.getBuyCount());
			orderItem.setOrderItemStatus(OrderItemStatusEnum.NORMAL.getStatus());
			orderItem.setRemark(itemDTO.getRemark());
			orderInfo.setAmount(orderInfo.getAmount().add(orderItem.getItemAmount()));

			if (orderInfo.getAmount().compareTo(new BigDecimal("99999999.99")) > 0) {
				throw new BusinessException("订单金额超过支持范围");
			}

			orderItemList.add(orderItem);

			//添加到订单详情
			orderInfo.getOrderItemList().add(orderItem);

			//删除购物车
			if (OrderFromTypeEnum.CART == orderFromTypeEnum) {
				//如果是从购物车订单,需要把订单中的商品添加到productCartList购物车集合当中，为了后续删除购物车中的这些东西
				ProductCart productCart = new ProductCart();
				productCart.setUserId(userId);
				productCart.setProductId(itemDTO.getProductId());
				productCart.setPropertyValueIdHash(StringTools.encodeByMD5(propertyValueIds));
				productCartList.add(productCart);
			}
		}

		this.orderInfoMapper.insertBatch(orderInfoList);

		this.orderItemMapper.insertBatch(orderItemList);

		//记录物流信息
		this.orderLogisticsInfoMapper.insertBatch(orderLogisticsInfoList);

		// 不改写已保存明细的正数量，单独构造扣减量；SQL还会再次聚合同一SKU。
		// -1 不表示只扣1件，而是把购买数量转换成负数。
		List<OrderItem> stockChanges = stockChanges(orderItemList, -1);
		Integer updateCount = this.productSkuMapper.updateStockBatch(stockChanges);
		if (!Objects.equals(updateCount, stockChanges.size())) {
			throw new BusinessException("库存不足");
		}

		//如果是购物车订单，删除购物车
		if (OrderFromTypeEnum.CART == orderFromTypeEnum) {
			this.productCartMapper.deleteBatch(productCartList);
		}

		// 与订单和库存共同提交的创建事件，供后续订单创建类任务接入。
		businessTaskService.enqueue(new TaskSubmission("ORDER_CREATED", payOrderId, 0,
				JsonUtils.convertObj2Json(Map.of("payOrderId", payOrderId, "orderIds", orderInfoList.stream().map(OrderInfo::getOrderId).toList())), Duration.ZERO));

		return orderInfoList.stream().map(OrderInfo::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	/**
	 * 申请取消整组订单。事务 A 禁止继续签发支付表单，并持久化下次查证时间。
	 * 事务提交后才访问支付宝，获得确定结果后再用事务 B 决定是否归还库存。
	 */
	@Override
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public OrderStatusEnum cancelOrder(String userId, String orderId, OrderStatusEnum target) {
		if (target != OrderStatusEnum.CANCELLED && target != OrderStatusEnum.CLOSED) {
			throw new BusinessException("取消状态无效");
		}
		return advanceClose(userId, orderId, target, false);
	}

	/**
	 *  申请取消整组订单。事务 A 禁止继续签发支付表单，并持久化下次查证时间。
	 *  事务提交后才访问支付宝，获得确定结果后再用事务 B 决定是否归还库存。
	 * @param userId 用户ID
	 * @param orderId 订单ID
	 * @param target 目标状态
	 * @param onlyDue 是否仅在订单到期时取消
	 * @return
	 */
	private OrderStatusEnum advanceClose(String userId, String orderId, OrderStatusEnum target, boolean onlyDue) {
		// 获取要取消的订单信息
		OrderInfo order = orderInfoMapper.selectByOrderId(orderId);
		if (order == null || (userId != null && !userId.equals(order.getUserId()))) {
			throw new BusinessException("订单不存在");
		}
		CloseAttempt attempt = orderRequestService.inBusinessTransaction(() -> claimClose(order, target, onlyDue));
		// 如果未成功领取关单权，则返回当前状态
		if (!attempt.claimed()) {
			return attempt.status();
		}

		// 新订单明确从未签发付款表单时，外部没有可用的收银台入口。历史数据为空时仍须查证。
		if (attempt.neverIssued()) {
			// 从未签发付款表单时，直接关闭订单
			return finishClose(order.getPayOrderId(), attempt.status());
		}

		try {
			PayChannel channel = paymentChannel(order.getPayScene());
			PaymentCloseResult closeResult = channel.closeOrder(order.getPayOrderId());
			if (closeResult == PaymentCloseResult.CLOSED) {
				return finishClose(order.getPayOrderId(), attempt.status());
			}
			// 关单没有给出明确的成功结果时，再用原支付单号查一次，防止漏掉已经到账的付款。
			PaymentQueryResult result = channel.queryPayment(order.getPayOrderId());
			// 若进入分支，出现的原因是：订单已经付款，支付宝也收到账，但是支付成功返回超时，导致订单状态未改变或者改变失败，最终又被定时任务查证
			if (result != null && result.status() == PaymentQueryResult.Status.PAID) {
				payOrderSuccess(result.payment());
				return OrderStatusEnum.PAID;
			}
			// 如果此时订单是未支付关闭状态，则完成关单(出现这种情况的原因是：之前已经关闭了订单并且未支付，在改变订单状态时候出错)
			if (result != null && result.status() == PaymentQueryResult.Status.CLOSED_UNPAID) {
				return finishClose(order.getPayOrderId(), attempt.status());
			}
			// 用户可能领到付款表单后就离开，渠道侧未建单。表单过期并度过宽限期后，
			// 同一轮的关单和查单都明确查无此单，且渠道允许此类收尾，才进入原关单事务。
			if (attempt.expiredIssuedForms() && closeResult == PaymentCloseResult.NOT_FOUND
					&& result != null && result.status() == PaymentQueryResult.Status.NOT_FOUND
					&& channel.supportsClosingExpiredMissingTrades()) {
				return finishClose(order.getPayOrderId(), attempt.status());
			}
		} catch (RuntimeException failure) {
			// 请求或落库失败后，保留事务 A 已提交的关单记录，下次按原支付单号查证。
			log.warn("关单结果待确认，payOrderId={}，异常类型={}", order.getPayOrderId(), failure.getClass().getSimpleName());
		}
		return attempt.status();
	}

	/**
	 * 行锁内领取整组关单权，其他请求只读当前状态。进程退出后，到期的记录可被其他实例接管。
	 */
	private CloseAttempt claimClose(OrderInfo order, OrderStatusEnum target, boolean onlyDue) {
		//校验同一支付订单中的所有主订单是否属于同一用户和渠道
		List<OrderInfo> group = lockPaymentGroup(order.getPayOrderId());
		//校验所有主订单的状态
		OrderStatusEnum current = OrderStatusEnum.getByStatus(group.getFirst().getOrderStatus());
		if (current == OrderStatusEnum.CANCELLED || current == OrderStatusEnum.CLOSED) {
			return new CloseAttempt(current, false, false, false);
		}
		if (current != OrderStatusEnum.WAIT_PAYMENT && !OrderStatusEnum.isClosing(group.getFirst().getOrderStatus())) {
			if (onlyDue) {
				return new CloseAttempt(current, false, false, false);
			}
			throw new BusinessException("订单状态已变化，无法取消");
		}
		// 校验支付组订单状态一致
		requireGroupStatus(group, current);

		long now = System.currentTimeMillis();
		// 自动取消的过程中校验同一个支付订单下的所有主订单是否都是等待支付状态且未过期，如果存在未过期的订单则返回当前状态(校验所有主订单创建时间和支付截止时间要一致)
		if (onlyDue && current == OrderStatusEnum.WAIT_PAYMENT && group.stream().anyMatch(item -> expiry(item) > now)) {
			return new CloseAttempt(current, false, false, false);
		}
		// 判断此时订单是否都是等待关闭状态且下次查证时间是否已到
		if (OrderStatusEnum.isClosing(current.getStatus()) && group.stream().anyMatch(item ->
				item.getPaymentCloseCheckAt() != null && item.getPaymentCloseCheckAt().getTime() > now)) {
			return new CloseAttempt(current, false, false, false);
		}

		OrderStatusEnum pending = current;
		//判断订单是要超时关闭还是手动取消
		if (current == OrderStatusEnum.WAIT_PAYMENT) {
			pending = target == OrderStatusEnum.CANCELLED ? OrderStatusEnum.CANCEL_PENDING : OrderStatusEnum.CLOSE_PENDING;
		}
		// 更新支付组订单状态
		changePaymentGroup(group, current, pending, null, new Date(now + 60_000L));
		boolean neverIssued = group.stream().allMatch(item -> Boolean.FALSE.equals(item.getPaymentLinkIssued()));
		// 只使用发给支付宝的固定截止时间。历史订单按配置推算的时间仅用于调度查证。
		// 同一支付单必须整组满足条件。任何主订单已有渠道交易号，就继续等明确的渠道终态。
		boolean expiredIssuedForms = group.stream().allMatch(item -> Boolean.TRUE.equals(item.getPaymentLinkIssued())
				&& item.getPaymentExpiresAt() != null && item.getPaymentExpiresAt().getTime() <= now - EXPIRED_PAYMENT_GRACE_MILLIS
				&& StringTools.isEmpty(item.getChannelOrderId()));
		return new CloseAttempt(pending, true, neverIssued, expiredIssuedForms);
	}

	/**
	 * 关单尝试结果记录
	 * @param status 关单状态
	 * @param claimed 是否成功领取关单权
	 * @param neverIssued 是否从未签发付款表单
	 * @param expiredIssuedForms 整组已签发表单且已超过固定截止时间和宽限期
	 */
	private record CloseAttempt(OrderStatusEnum status, boolean claimed, boolean neverIssued, boolean expiredIssuedForms) {}

	/**
	 * 可信关单结果落库。状态推进和归还库存同事务提交，重复处理不会再次归还库存。
	 */
	private OrderStatusEnum finishClose(String payOrderId, OrderStatusEnum pending) {
		return orderRequestService.inBusinessTransaction(() -> {
			//校验整组属于同一用户和渠道
			List<OrderInfo> group = lockPaymentGroup(payOrderId);
			OrderStatusEnum current = OrderStatusEnum.getByStatus(group.getFirst().getOrderStatus());
			if (current != pending) {
				// 例如另一线程已完成同一次关单，或可信付款通知先完成了支付状态更新。
				return current;
			}
			// 校验支付组订单状态一致
			requireGroupStatus(group, pending);
			// 更新支付组订单状态
			OrderStatusEnum target = pending == OrderStatusEnum.CANCEL_PENDING ? OrderStatusEnum.CANCELLED : OrderStatusEnum.CLOSED;
			changePaymentGroup(group, pending, target, null, null);

			OrderItemQuery query = new OrderItemQuery();
			query.setOrderIdList(group.stream().map(OrderInfo::getOrderId).toList());
			// 构造归还库存的明细，SQL还会再次聚合同一SKU
			List<OrderItem> changes = stockChanges(orderItemMapper.selectList(query), 1);
			// 还原库存
			if (changes.isEmpty() || !Objects.equals(productSkuMapper.updateStockBatch(changes), changes.size())) {
				throw new BusinessException("库存归还未完成，关单结果暂未落库");
			}
			businessTaskService.enqueue(new TaskSubmission("ORDER_CANCELLED", payOrderId, 0,
					JsonUtils.convertObj2Json(Map.of("payOrderId", payOrderId)), Duration.ZERO));
			return target;
		});
	}

	/**
	 * 定时任务
	 * 数据库是超时关单和补偿的来源，重启后仍能读到尚未关闭的支付组。
	 */
	@Override
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public void reconcilePaymentClosures() {
		Date now = new Date();
		Date legacyCutoff = new Date(now.getTime() - appConfig.getOrderExpireMinute() * 60_000L);
		// 获取到处于取消确认/关闭确认中的订单的ID添加到集合
		List<String> orderIds = new ArrayList<>(orderInfoMapper.selectDueCloseOrderIds(now, 8));
		//把已经超过付款截止时间、但仍处于「待付款」的订单ID添加到集合
		orderIds.addAll(orderInfoMapper.selectExpiredOrderIds(now, legacyCutoff, 8));
		for (String id : orderIds) {
			try {
				advanceClose(null, id, OrderStatusEnum.CLOSED, true);
			} catch (RuntimeException failure) {
				log.warn("关单扫描暂未完成，orderId={}，异常类型={}", id, failure.getClass().getSimpleName());
			}
		}
	}

	/** 主动查单按支付组分页，单笔渠道失败时继续处理同批其余订单。 */
	@Override
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public String pollUnpaidPayments(String afterPayOrderId) {
		List<OrderInfo> groups = orderInfoMapper.selectAwaitingPaymentGroups(afterPayOrderId, 20);
		for (OrderInfo group : groups) {
			try {
				PaymentQueryResult result = paymentChannel(group.getPayScene()).queryPayment(group.getPayOrderId());
				if (result != null && result.status() == PaymentQueryResult.Status.PAID) {
					payOrderSuccess(result.payment());
				}
			} catch (RuntimeException failure) {
				log.warn("主动查单暂未完成，payOrderId={}，异常类型={}", group.getPayOrderId(), failure.getClass().getSimpleName());
			}
		}
		return groups.size() < 20 ? "" : groups.getLast().getPayOrderId();
	}

	/**
	 * 回调、关单和签发付款链接使用相同的行锁顺序，校验整组属于同一用户和渠道。
	 */
	private List<OrderInfo> lockPaymentGroup(String payOrderId) {
		List<OrderInfo> group = orderInfoMapper.selectPaymentGroupForUpdate(payOrderId);
		if (group.isEmpty()) {
			throw new BusinessException("支付订单不存在");
		}
		// 确保支付组内所有订单属于同一用户和渠道
		OrderInfo first = group.getFirst();
		if (group.stream().anyMatch(order -> !Objects.equals(first.getUserId(), order.getUserId())
				|| !Objects.equals(first.getPayScene(), order.getPayScene()))) {
			throw new BusinessException("支付组归属或渠道异常");
		}
		return group;
	}

	/**
	 * 校验支付组订单状态一致
	 */
	private static void requireGroupStatus(List<OrderInfo> group, OrderStatusEnum status) {
		if (status == null || group.stream().anyMatch(order -> !status.getStatus().equals(order.getOrderStatus()))) {
			throw new BusinessException("支付组订单状态不一致");
		}
	}

	/**
	 * 更新支付组订单状态
	 */
	private void changePaymentGroup(List<OrderInfo> group, OrderStatusEnum oldStatus, OrderStatusEnum newStatus,
	                                String channelOrderId, Date checkAt) {
		int changed = orderInfoMapper.updatePaymentGroup(group.getFirst().getPayOrderId(), oldStatus.getStatus(),
				newStatus.getStatus(), channelOrderId, checkAt);
		if (changed != group.size()) {
			throw new BusinessException("支付组订单状态已变化");
		}
	}

	private static PayChannel paymentChannel(String payScene) {
		PayChannelEnum channel = PayChannelEnum.getByPayScene(payScene);
		if (channel == null) {
			throw new BusinessException("原订单支付渠道无效");
		}
		return (PayChannel) SpringContext.getBean(channel.getBeanName());
	}

	/**
	 * 获取支付信息(进行在订单页面中点击付款)
	 * @param userId 用户Id
	 * @param orderId 主订单Id
	 * @return 支付信息
	 */
	@Override
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public PayInfoDTO getPayInfo(String userId, String orderId) {
		OrderInfo orderInfo = orderInfoMapper.selectByOrderId(orderId);
		if (orderInfo == null || !orderInfo.getUserId().equals(userId)) {
			throw new BusinessException("订单不存在");
		}

		// 复用原支付号并计算整组金额，避免一个购物车支付组被拆成不同支付号或少付金额。
		PayInfoDTO result = new PayInfoDTO(null, orderInfo.getPayOrderId(), null);
		result.setRequestStatus("CREATED");
		return paymentForGroup(paymentGroup(userId, orderInfo.getPayOrderId()), result);
	}

	/** 查询本人的下单请求；只查询状态，不创建订单，也不请求支付渠道。 */
	@Override
	public PayInfoDTO getOrderRequest(String userId, String requestId) {
		InputValidator.requireText(userId, 15);
		if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{16,64}")) throw new BusinessException("requestId无效");
		OrderRequest request = orderRequestService.find(userId, requestId);
		if (request == null) throw new BusinessException("下单请求不存在");
		return requestResult(request);
	}

	/**
	 * 封装支付信息
	 * @param request 下单请求
	 * @return 支付信息
	 */
	private PayInfoDTO requestResult(OrderRequest request) {
		PayInfoDTO result = new PayInfoDTO(null, request.getPayOrderId(), request.getAmount());
		result.setRequestId(request.getRequestId());
		result.setRequestStatus("PROCESSING".equals(request.getStatus()) && !request.isLeaseActive() ? "RETRY" : request.getStatus());
		return result;
	}

	/**
	 * 获取到支付订单号对应的主订单信息
	 * @param userId 用户Id
	 * @param payOrderId 支付订单号
	 * @return 主订单信息
	 */
	private List<OrderInfo> paymentGroup(String userId, String payOrderId) {
		OrderInfoQuery query = new OrderInfoQuery(); query.setUserId(userId); query.setPayOrderId(payOrderId);
		List<OrderInfo> orders = orderInfoMapper.selectList(query);
		if (orders.isEmpty()) throw new BusinessException("订单不存在");
		return orders;
	}

	/** 支付链接失败不回滚已提交的订单；客户端保留原业务号，可从原订单继续支付。 */
	private PayInfoDTO paymentForGroup(List<OrderInfo> orders, PayInfoDTO result) {
		result.setOrderId(orders.get(0).getOrderId());
		result.setAmount(orders.stream().map(OrderInfo::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
		// 获取到订单支付截止时间
		long deadline = orders.stream().mapToLong(this::expiry).min().orElseThrow();
		// 判断订单是否可支付
		if (deadline <= System.currentTimeMillis()
				|| orders.stream().anyMatch(order -> !OrderStatusEnum.WAIT_PAYMENT.getStatus().equals(order.getOrderStatus()))) {
			result.setPaymentStatus("NOT_PAYABLE");
			return result;
		}
		PayChannelEnum channel = PayChannelEnum.getByPayScene(orders.getFirst().getPayScene());
		try {
			PayChannel payChannel = paymentChannel(orders.getFirst().getPayScene());
			// 生成表单可能访问外部资源，先在事务外完成。表单在签发登记成功前不会发给客户端。
			PayInfoDTO link = payChannel.getPayUrl(channel, result.getPayOrderId(), "商城订单 " + result.getPayOrderId(),
					result.getAmount(), Instant.ofEpochMilli(deadline));
			if (link == null || StringTools.isEmpty(link.getPayInfo())) {
				throw new BusinessException("支付信息暂不可用");
			}
			boolean issued = orderRequestService.inBusinessTransaction(() -> {
				List<OrderInfo> current = lockPaymentGroup(result.getPayOrderId());
				if (deadline <= System.currentTimeMillis() || current.stream()
						.anyMatch(order -> !OrderStatusEnum.WAIT_PAYMENT.getStatus().equals(order.getOrderStatus()))) {
					// 生成表单期间可能已开始关单。行锁内二次核对，丢弃尚未发出的表单。
					return false;
				}
				// 演示页面无法扣款，因此不会把新演示订单误标为已签发真实表单。
				// 计算未签发表单的订单数
				long unmarked = current.stream().filter(order -> !Boolean.TRUE.equals(order.getPaymentLinkIssued())).count();
				if (!"DEMO".equals(link.getPaymentStatus()) && unmarked > 0
						&& orderInfoMapper.markPaymentLinkIssued(result.getPayOrderId()) != unmarked) {
					throw new BusinessException("支付表单签发登记未完成");
				}
				return true;
			});
			if (!issued) {
				result.setPaymentStatus("NOT_PAYABLE");
				return result;
			}
			result.setPayInfo(link.getPayInfo());
			result.setPaymentStatus("READY");
		} catch (RuntimeException unavailable) {
			log.warn("支付链接不可用; payOrderId={}, code=PAYMENT_LINK_UNAVAILABLE", result.getPayOrderId());
			result.setPaymentStatus("UNAVAILABLE");
		}
		return result;
	}

	/**
	 * 订单支付截止时间
	 * @param order
	 * @return
	 */
	private long expiry(OrderInfo order) {
		if (order.getPaymentExpiresAt() != null) {
			return order.getPaymentExpiresAt().getTime();
		}
		if (order.getOrderTime() == null) {
			throw new BusinessException("订单创建时间无效");
		}
		return order.getOrderTime().getTime() + appConfig.getOrderExpireMinute() * 60_000L;
	}

	/** 为相同SKU累加正/负变更量并排序，兼容历史重复明细；库存回写数量与实际SKU行数比较。 */
	private List<OrderItem> stockChanges(List<OrderItem> items, int sign) {
		Map<List<String>, OrderItem> merged = new HashMap<>();
		for (OrderItem item : items) {
			if (item.getBuyCount() == null || item.getBuyCount() <= 0) {
				throw new BusinessException("订单明细数量无效");
			}
			List<String> key = List.of(item.getProductId(), item.getPropertyValueIdHash());
			OrderItem change = merged.computeIfAbsent(key, ignored -> {
				OrderItem fresh = new OrderItem(); fresh.setProductId(item.getProductId());
				fresh.setPropertyValueIdHash(item.getPropertyValueIdHash()); fresh.setBuyCount(0); return fresh;
			});
			change.setBuyCount(Math.addExact(change.getBuyCount(), Math.multiplyExact(item.getBuyCount(), sign)));
		}

		return merged.values().stream().sorted(Comparator.comparing(OrderItem::getProductId).thenComparing(OrderItem::getPropertyValueIdHash)).toList();
	}

	/**
	 * 经过渠道校验的付款通知和主动查单共用这一入口。
	 * 确认支付和登记 ORDER_PAID 一起提交，渠道重复通知可在核对交易号后直接应答。
	 */
	@Override
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public void payOrderSuccess(PayOrderNotifyDTO payment) {
		if (payment == null) {
			throw new BusinessException("支付结果无效");
		}
		InputValidator.requireText(payment.getPayOrderId(), 32);
		InputValidator.requireText(payment.getChannelOrderId(), 50);
		orderRequestService.inBusinessTransaction(() -> {
			List<OrderInfo> group = lockPaymentGroup(payment.getPayOrderId());
			BigDecimal expected = group.stream().map(OrderInfo::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
			if (payment.getAmount() == null || expected.compareTo(payment.getAmount()) != 0) {
				throw new BusinessException("支付金额与订单金额不一致");
			}
			if (group.stream().allMatch(order -> payment.getChannelOrderId().equals(order.getChannelOrderId()))) {
				// 订单可能已经发货、退款或完成。相同交易号对应同一次支付，直接幂等应答。
				return null;
			}
			OrderStatusEnum current = OrderStatusEnum.getByStatus(group.getFirst().getOrderStatus());
			if (current != OrderStatusEnum.WAIT_PAYMENT && !OrderStatusEnum.isClosing(group.getFirst().getOrderStatus())) {
				log.warn("付款结果与商城状态冲突，payOrderId={}，status={}", payment.getPayOrderId(), current);
				throw new BusinessException("支付结果与商城订单状态冲突");
			}
			requireGroupStatus(group, current);
			if (group.stream().anyMatch(order -> order.getChannelOrderId() != null && !order.getChannelOrderId().isBlank())) {
				throw new BusinessException("支付组存在冲突的渠道交易号");
			}
			changePaymentGroup(group, current, OrderStatusEnum.PAID, payment.getChannelOrderId(), null);
			businessTaskService.enqueue(new TaskSubmission("ORDER_PAID", payment.getPayOrderId(), 0,
					JsonUtils.convertObj2Json(Map.of("payOrderId", payment.getPayOrderId())), Duration.ZERO));

			return null;
		});
	}

	/**
	 * 确认收获
	 * @param userId
	 * @param orderId
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public void confirmOrder(String userId, String orderId) {
		OrderInfo current = orderInfoMapper.selectOrderForUpdate(orderId);
		if (current == null || (userId != null && !userId.equals(current.getUserId()))) {
			throw new BusinessException("该订单无法进行确认收货");
		}
		if (OrderStatusEnum.PARTIALLY_REFUNDED.getStatus().equals(current.getOrderStatus())) {
			OrderLogisticsInfo shipment = orderLogisticsInfoMapper.selectByOrderId(orderId);
			Integer shipmentStatus = shipment == null ? null : shipment.getLogisticsStatus();
			if (!LogisticsStatusEnum.IN_TRANSIT.getStatus().equals(shipmentStatus)
					&& !LogisticsStatusEnum.DELIVERED.getStatus().equals(shipmentStatus)) {
				throw new BusinessException("该订单尚未发货");
			}
		}
		OrderItemQuery pending = new OrderItemQuery();
		pending.setOrderId(orderId);
		pending.setOrderItemStatus(OrderItemStatusEnum.REFUNDING.getStatus());
		//确认订单时查看确认的主订单下的子订单是否有退款中的子订单，若有则抛出异常
		if (orderItemMapper.selectCount(pending) > 0) {
			throw new RefundPendingException();
		}

		OrderInfo updateInfo = new OrderInfo();
		updateInfo.setOrderStatus(OrderStatusEnum.COMPLETED.getStatus());

		OrderInfoQuery orderInfoQuery = new OrderInfoQuery();
		orderInfoQuery.setOrderId(orderId);
		orderInfoQuery.setUserId(userId);
		orderInfoQuery.setOrderStatusList(new Integer[]{OrderStatusEnum.SHIPPED.getStatus(),OrderStatusEnum.PARTIALLY_REFUNDED.getStatus()});
		Integer updateCount = this.orderInfoMapper.updateByParam(updateInfo, orderInfoQuery);
		if (updateCount == 0) {
			throw new BusinessException("该订单无法进行确认收货");
		}

		//设置销量
		OrderItemQuery orderItemQuery = new OrderItemQuery();
		orderItemQuery.setOrderId(orderId);
		orderItemQuery.setOrderItemStatus(OrderItemStatusEnum.NORMAL.getStatus());
		List<OrderItem> orderItemList = this.orderItemMapper.selectList(orderItemQuery);
		Integer buyCount = orderItemList.stream().mapToInt(OrderItem::getBuyCount).sum();
		this.productInfoMapper.updateProductTotalSale(orderItemList.get(0).getProductId(), buyCount);
	}

	/**
	 * 删除订单
	 * @param userId 用户Id
	 * @param orderId 订单Id
	 */
	@Override
	public void deleteOrder(String userId, String orderId) {
		OrderInfo orderInfo = orderInfoMapper.selectByOrderId(orderId);

		if(orderInfo == null || !orderInfo.getUserId().equals(userId)){
			throw new BusinessException("订单不存在");
		}

		Integer[] statusList = new Integer[]{OrderStatusEnum.CANCELLED.getStatus(),OrderStatusEnum.CLOSED.getStatus(),OrderStatusEnum.COMPLETED.getStatus()};
		if (!ArrayUtils.contains(statusList, orderInfo.getOrderStatus())){
			throw new BusinessException("订单无法删除");
		}
		OrderInfo updateOrderInfo = new OrderInfo();
		updateOrderInfo.setOrderStatus(OrderStatusEnum.DELETE.getStatus());

		OrderInfoQuery orderInfoQuery = new OrderInfoQuery();
		orderInfoQuery.setOrderId(orderId);
		orderInfoQuery.setUserId(userId);
		orderInfoQuery.setOrderStatusList(statusList);
		orderInfoMapper.updateByParam(updateOrderInfo, orderInfoQuery);
	}

	/**
	 * 退款
	 * @param orderItemId 订单子订单Id
	 */
	@Override
	public void refundByOrderItemId(String userId, String orderItemId) {
		// 兼容旧客户端：渠道确认后才返回旧接口的成功响应。
		RefundStatus status = refundService.apply(userId, orderItemId, UUID.randomUUID().toString()).status();

		if (status == RefundStatus.FAILED) {
			throw new BusinessException("退款未成功，请刷新订单后查看");
		}
		if (status != RefundStatus.SUCCESS) {
			throw new RefundPendingException();
		}
	}

	/**
	 * 管理端首页获取数据(什么时间什么状态的订单总金额)
	 * @param orderTime 订单时间
	 * @param orderStatus 订单状态
	 * @return 订单总金额
	 */
	@Override
	public BigDecimal getOrderTotalAmount(String orderTime, Integer[] orderStatus) {
		return this.orderInfoMapper.selectOrderTotalAmount(orderTime,orderStatus);
	}
}
