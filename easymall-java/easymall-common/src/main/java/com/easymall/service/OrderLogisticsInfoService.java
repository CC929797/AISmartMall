package com.easymall.service;

import java.util.List;

import com.easymall.entity.query.OrderLogisticsInfoQuery;
import com.easymall.entity.po.OrderLogisticsInfo;
import com.easymall.entity.vo.PaginationResultVO;


/**
 * 物流信息表 业务接口
 */
public interface OrderLogisticsInfoService {

	/**
	 * 根据条件查询列表
	 */
	List<OrderLogisticsInfo> findListByParam(OrderLogisticsInfoQuery param);

	/**
	 * 根据条件查询列表
	 */
	Integer findCountByParam(OrderLogisticsInfoQuery param);

	/**
	 * 分页查询
	 */
	PaginationResultVO<OrderLogisticsInfo> findListByPage(OrderLogisticsInfoQuery param);

	/**
	 * 新增
	 */
	Integer add(OrderLogisticsInfo bean);

	/**
	 * 批量新增
	 */
	Integer addBatch(List<OrderLogisticsInfo> listBean);

	/**
	 * 批量新增/修改
	 */
	Integer addOrUpdateBatch(List<OrderLogisticsInfo> listBean);

	/**
	 * 多条件更新
	 */
	Integer updateByParam(OrderLogisticsInfo bean,OrderLogisticsInfoQuery param);

	/**
	 * 多条件删除
	 */
	Integer deleteByParam(OrderLogisticsInfoQuery param);

	/**
	 * 根据OrderId查询对象
	 */
	OrderLogisticsInfo getOrderLogisticsInfoByOrderId(String orderId);


	/**
	 * 根据OrderId修改
	 */
	Integer updateOrderLogisticsInfoByOrderId(OrderLogisticsInfo bean,String orderId);


	/**
	 * 根据OrderId删除
	 */
	Integer deleteOrderLogisticsInfoByOrderId(String orderId);

	/**
	 * 订单发货
	 */
	void delivery(OrderLogisticsInfo orderLogisticsInfo);

	/**
	 * 模拟订单物流
	 * @param orderId
	 */
    void mockOrderLogistics(String orderId);

	/**
	 * 获取订单物流信息
	 * @param userId
	 * @param orderId
	 * @return
	 */
	OrderLogisticsInfo getOrderLogisticsRecords(String userId,String orderId);
}