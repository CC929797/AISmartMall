package com.easymall.mappers;

import com.easymall.order.OrderRequest;
import org.apache.ibatis.annotations.Param;
import java.math.BigDecimal;

/** 【正式业务】下单幂等记录Mapper，事务及接管规则由OrderRequestService管理。 */
public interface OrderRequestMapper {
    OrderRequest find(@Param("userId") String userId, @Param("requestId") String requestId);
    /** NOWAIT避免另一个活跃下单事务持锁时，本次重试长时间等待。 */
    OrderRequest lock(@Param("userId") String userId, @Param("requestId") String requestId);
    /** 当前持有者等待短暂的并发唯一键检查结束；业务事务仍有有限超时。 */
    OrderRequest lockOwned(@Param("userId") String userId, @Param("requestId") String requestId);
    int insert(@Param("row") OrderRequest row, @Param("leaseSeconds") int leaseSeconds);
    int reclaim(@Param("userId") String userId, @Param("requestId") String requestId, @Param("owner") String owner, @Param("leaseSeconds") int leaseSeconds);
    int complete(@Param("userId") String userId, @Param("requestId") String requestId,
                 @Param("owner") String owner, @Param("amount") BigDecimal amount);
    int release(@Param("userId") String userId, @Param("requestId") String requestId, @Param("owner") String owner);
}
