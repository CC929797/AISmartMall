package com.easymall.mappers;

import com.easymall.refund.RefundRequest;
import com.easymall.refund.RefundStatus;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface RefundRequestMapper {
    RefundRequest find(@Param("refundId") String refundId);
    RefundRequest findByRequest(@Param("userId") String userId, @Param("requestId") String requestId);
    RefundRequest findActive(@Param("orderItemId") String orderItemId);
    List<RefundRequest> findByItems(@Param("userId") String userId, @Param("itemIds") List<String> itemIds);
    int insert(RefundRequest request);
    List<RefundRequest> findDue(@Param("limit") int limit);
    int claim(@Param("refundId") String refundId, @Param("version") long version);
    int resolve(@Param("refundId") String refundId, @Param("version") long version,
                @Param("status") RefundStatus status, @Param("error") String error);
}
