package com.easymall.mappers;

import org.apache.ibatis.annotations.Param;

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

	/**
	 * 批量更改订单状态
	 * @param orderStatus 更改后订单状态
	 * @param oldStatus 旧订单状态
	 * @param orderIdList 更改的订单Id列表
	 */
	 Integer updateOrderStatusBatch(@Param("orderStatus")Integer orderStatus,
								 @Param("oldStatus") Integer oldStatus,
								 @Param("orderIdList") List<String> orderIdList);


}
