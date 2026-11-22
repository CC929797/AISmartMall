package com.easymall.mappers;

import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 订单信息 数据库操作接口
 */
public interface OrderInfoMapper<T,P> extends BaseMapper<T,P> {

	/**
	 * 根据OrderId更新
	 */
	 Integer updateByOrderId(@Param("bean") T t,@Param("orderId") String orderId);


	/**
	 * 根据OrderId删除
	 */
	 Integer deleteByOrderId(@Param("orderId") String orderId);


	/**
	 * 根据OrderId获取对象
	 */
	 T selectByOrderId(@Param("orderId") String orderId);

	 /** 退款、发货和收货都先锁主订单，再修改订单明细或物流。 */
	 T selectOrderForUpdate(@Param("orderId") String orderId);

	/** 按主单编号顺序锁定支付组；调用方校验支付组归属及状态。 */
	List<T> selectPaymentGroupForUpdate(@Param("payOrderId") String payOrderId);

	/** 按旧状态推进整组订单，同时写入渠道交易号或下次关单查证时间。 */
	int updatePaymentGroup(@Param("payOrderId") String payOrderId, @Param("oldStatus") int oldStatus,
			@Param("newStatus") int newStatus, @Param("channelOrderId") String channelOrderId, @Param("checkAt") Date checkAt);

	/** 事务内登记付款表单已签发，锁定支付组后才能调用。 */
	int markPaymentLinkIssued(@Param("payOrderId") String payOrderId);

	List<String> selectExpiredOrderIds(@Param("now") Date now, @Param("legacyCutoff") Date legacyCutoff, @Param("limit") int limit);

	List<String> selectDueCloseOrderIds(@Param("now") Date now, @Param("limit") int limit);

	/** 主动查单使用按支付单号翻页的游标，同一组订单只查询一次。 */
	List<T> selectAwaitingPaymentGroups(@Param("after") String after, @Param("limit") int limit);

	/**
	 * 批量更改订单状态
	 * @param orderStatus 更改后订单状态
	 * @param oldStatus 旧订单状态
	 * @param orderIdList 更改的订单Id列表
	 */
	 Integer updateOrderStatusBatch(@Param("orderStatus")Integer orderStatus,
								 @Param("oldStatus") Integer oldStatus,
								 @Param("orderIdList") List<String> orderIdList);


	/**
	 * 管理端首页获取数据(什么时间什么状态的订单总金额)
	 * @param orderTime 订单时间
	 * @param orderStatus 订单状态
	 * @return 订单总金额
	 */
    BigDecimal selectOrderTotalAmount(@Param("orderTime") String orderTime,@Param("orderStatus") Integer[] orderStatus);
}
