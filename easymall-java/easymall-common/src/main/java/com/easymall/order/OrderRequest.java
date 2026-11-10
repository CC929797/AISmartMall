package com.easymall.order;

import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 【正式业务】order_request的内部快照。状态CREATED只表示订单已创建，不表示已付款。 */
@Getter
@Setter
public class OrderRequest {
    private String userId;
    private String requestId;
    private String requestHash;
    private String status;
    /** 首次预留即确定，后续网络重试、接管和支付链接重试都不能更换。 */
    private String payOrderId;
    private BigDecimal amount;
    /** 一次处理尝试的身份，进程崩溃后重新接管会更换。 */
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    /** 由数据库UTC时间计算，不依赖应用机器的时钟。 */
    private boolean leaseActive;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
