package com.easymall.service;

import java.math.BigDecimal;
import java.util.List;

import com.easymall.entity.dto.PayInfoDTO;
import com.easymall.entity.dto.PayOrderNotifyDTO;
import com.easymall.entity.dto.PostOrderDTO;
import com.easymall.entity.enums.OrderStatusEnum;
import com.easymall.entity.query.OrderInfoQuery;
import com.easymall.entity.po.OrderInfo;
import com.easymall.entity.vo.PaginationResultVO;
import jakarta.validation.constraints.NotEmpty;


/**
 * 订单信息 业务接口
 */
public interface OrderInfoService {

	/**
	 * 根据条件查询列表
	 */
	List<OrderInfo> findListByParam(OrderInfoQuery param);

	/**
	 * 根据条件查询列表
	 */
	Integer findCountByParam(OrderInfoQuery param);

	/**
	 * 分页查询
	 */
	PaginationResultVO<OrderInfo> findListByPage(OrderInfoQuery param);

	/**
	 * 新增
	 */
	Integer add(OrderInfo bean);

	/**
	 * 批量新增
	 */
	Integer addBatch(List<OrderInfo> listBean);

	/**
	 * 批量新增/修改
	 */
	Integer addOrUpdateBatch(List<OrderInfo> listBean);

	/**
	 * 多条件更新
	 */
	Integer updateByParam(OrderInfo bean,OrderInfoQuery param);

	/**
	 * 多条件删除
	 */
	Integer deleteByParam(OrderInfoQuery param);

	/**
	 * 根据OrderId查询对象
	 */
	OrderInfo getOrderInfoByOrderId(String orderId);


	/**
	 * 根据OrderId修改
	 */
	Integer updateOrderInfoByOrderId(OrderInfo bean,String orderId);


	/**
	 * 根据OrderId删除
	 */
	Integer deleteOrderInfoByOrderId(String orderId);

	/**
	 * 订单提交
	 * @param userId 用户Id
	 * @param postOrder 订单
	 */
	PayInfoDTO postOrder(String userId, PostOrderDTO postOrder);

	/**
	 * 取消订单
	 */
    void cancelOrder(String userId, String orderId, OrderStatusEnum orderStatusEnum);

	/**
	 * 订单支付成功
	 * @param payOrderNotifyDTO
	 */
	void payOrderSuccess(PayOrderNotifyDTO payOrderNotifyDTO);

	/**
	 * 确认收获
	 * @param userId
	 * @param orderId
	 */
    void confirmOrder(String userId, String orderId);

	/**
	 * 删除订单
	 * @param orderId
	 */
    void deleteOrder(String userId,String orderId);

	/**
	 * 退款
	 * @param orderItemId 订单子订单Id
	 */
	void refundByOrderItemId(String userId, String orderItemId);

	/**
	 * 管理端首页获取数据(什么时间什么状态的订单总金额)
	 * @param orderTime 订单时间
	 * @param orderStatus 订单状态
	 * @return 订单总金额
	 */
	BigDecimal getOrderTotalAmount(String orderTime,Integer[] orderStatus);
}