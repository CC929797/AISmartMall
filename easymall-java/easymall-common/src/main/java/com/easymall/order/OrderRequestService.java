package com.easymall.order;

import com.easymall.exception.BusinessException;
import com.easymall.mappers.OrderRequestMapper;
import com.easymall.utils.StringTools;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 【正式业务】一次购买意图只创建一组订单。
 * 先用短事务持久化请求及固定支付号，再以行锁保护订单/库存/事件事务，最后由调用方在事务外生成支付链接。
 * 崩溃留下的PROCESSING记录120秒后可接管；若旧事务仍持有行锁，NOWAIT会返回处理中而不是并行建单。
 */
@Service
public class OrderRequestService {
    private static final Logger log = LoggerFactory.getLogger(OrderRequestService.class);
    private final OrderRequestMapper orderRequestMapper;
    private final TransactionTemplate reservation;
    private final TransactionTemplate business;
    private final int leaseSeconds;

    public OrderRequestService(OrderRequestMapper orderRequestMapper, PlatformTransactionManager manager,
                               @Value("${checkout.request-lease-seconds:120}") int leaseSeconds) {
        if (leaseSeconds < 3 || leaseSeconds > 300) throw new IllegalArgumentException("Invalid checkout request lease");
        this.leaseSeconds = leaseSeconds;
        this.orderRequestMapper = orderRequestMapper;
        reservation = transaction(manager, 3); business = transaction(manager, Math.min(20, leaseSeconds - 1));
    }

    /**
     * 一次购买意图只创建一组订单。
     * @param userId 用户ID
     * @param input 下单请求
     * @param createOrders 创建订单的函数
     * @return 下单请求
     */
    public OrderRequest place(String userId, CheckoutInput input, Function<String, BigDecimal> createOrders) {
        // 获取请求ID
        String requestId = input.order().getRequestId();
        // 生成租约所有者
        String owner = UUID.randomUUID().toString().replace("-", "");
        // 尝试获取订单请求
        OrderRequest row = find(userId, requestId);
        if (row == null) {
            // 首次请求需创建订单请求
            OrderRequest candidate = new OrderRequest();
            candidate.setUserId(userId);
            candidate.setRequestId(requestId);
            candidate.setRequestHash(input.digest());
            candidate.setPayOrderId(StringTools.createPayOrderId());
            candidate.setLeaseOwner(owner);
            try {
                // 尝试插入订单请求
                row = reservation.execute(status -> {
                    orderRequestMapper.insert(candidate, leaseSeconds);
                    // 在同一独立事务内读取，避免恢复外层会话后命中之前缓存的空结果。
                    return orderRequestMapper.find(userId, requestId);
                });
            }
            catch (DuplicateKeyException concurrent) {
                row = find(userId, requestId);
            }
        }
        //判断是否是同一个request请求
        requireSame(row, input.digest());

        if ("CREATED".equals(row.getStatus())) {
            return row;
        }
        if (!owner.equals(row.getLeaseOwner())) {
            if ("PROCESSING".equals(row.getStatus()) && row.isLeaseActive()) {
                return row;
            }
            try {
                row = reservation.execute(status -> {
                    // 尝试获取订单请求
                    OrderRequest current = orderRequestMapper.lock(userId, requestId);
                    requireSame(current, input.digest());
                    if ("CREATED".equals(current.getStatus()) || ("PROCESSING".equals(current.getStatus()) && current.isLeaseActive())) {
                        return current;
                    }
                    orderRequestMapper.reclaim(userId, requestId, owner, leaseSeconds);
                    return orderRequestMapper.find(userId, requestId);
                });
            } catch (PessimisticLockingFailureException | QueryTimeoutException busy) {
                // 接管没成功，不能释放其他处理者的租约。
                return find(userId, requestId);
            }
        }

        //判断第一次请求的身份资格是否跟第二次一样
        if (!owner.equals(row.getLeaseOwner())) {
            return row;
        }

        try {
            return business.execute(status -> {
                // 尝试获取订单请求
                OrderRequest current = orderRequestMapper.lockOwned(userId, requestId);
                // 再次判断请求是否一致
                requireSame(current, input.digest());
                if (!"PROCESSING".equals(current.getStatus()) || !owner.equals(current.getLeaseOwner()) || !current.isLeaseActive()) {
                    return current;
                }
                // 回调只执行数据库操作；禁止在这里请求支付渠道或投递Redis任务。、
                // 执行方法创建订单，并返回订单总金额
                BigDecimal amount = createOrders.apply(current.getPayOrderId());
                // 更新订单请求状态
                if (orderRequestMapper.complete(userId, requestId, owner, amount) != 1) {
                    throw new BusinessException("请求处理资格已变化，请使用原请求重试");
                }
                //返回最终的订单请求
                return orderRequestMapper.find(userId, requestId);
            });
        } catch (PessimisticLockingFailureException | QueryTimeoutException busy) {
            // 本次业务事务已回滚，立即允许原请求重试，不必等120秒租约自然到期。
            releaseAttempt(userId, requestId, owner);
            log.warn("下单事务已回滚，可使用原请求重试; code=ORDER_BUILD_BUSY, exceptionType={}", busy.getClass().getSimpleName());
            return find(userId, requestId);
        } catch (RuntimeException failure) {
            // 业务事务已回滚才释放本轮资格；条件更新不会覆盖另一个owner或已提交的CREATED结果。
            releaseAttempt(userId, requestId, owner);
            throw failure;
        }
    }

    /**
     * 查询仅按可信当前用户定位，不允许拿别人的requestId获取支付号。
     * NOT_SUPPORTED仍可能存在Spring同步上下文并复用MyBatis会话；独立短事务隔离其一级缓存，
     * 防止后续独立事务已提交，但查询仍返回之前的空结果或PROCESSING快照。
     */
    public OrderRequest find(String userId, String requestId) {
        return reservation.execute(status -> orderRequestMapper.find(userId, requestId));
    }

    private void releaseAttempt(String userId, String requestId, String owner) {
        try { reservation.executeWithoutResult(status -> orderRequestMapper.release(userId, requestId, owner)); }
        catch (RuntimeException unavailable) {
            // 数据库暂不可用时保留租约，原请求仍可在到期后恢复；不输出异常正文或购买信息。
            log.warn("下单处理资格暂未释放，等待租约到期恢复; code=ORDER_LEASE_RELEASE_UNAVAILABLE, exceptionType={}", unavailable.getClass().getSimpleName());
        }
    }

    /**
     * 取消等数据库操作也使用同一事务边界；渠道调用必须放在本方法返回之后。
     */
    public <T> T inBusinessTransaction(Supplier<T> work) {
        return business.execute(status -> work.get());
    }

    private static void requireSame(OrderRequest row, String hash) {
        if (row == null) {
            throw new BusinessException("下单请求尚未确认，请使用原请求重试");
        }
        if (!hash.equals(row.getRequestHash())) {
            throw new BusinessException("相同requestId不能提交不同的购买内容");
        }
    }
    private static TransactionTemplate transaction(PlatformTransactionManager manager, int timeout) {
        TransactionTemplate tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); tx.setTimeout(timeout);
        return tx;
    }
}
