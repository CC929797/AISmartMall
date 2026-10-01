package com.easymall.service;

import java.util.List;

import com.easymall.entity.query.OrderCommentQuery;
import com.easymall.entity.po.OrderComment;
import com.easymall.entity.vo.PaginationResultVO;
import jakarta.validation.constraints.*;


/**
 *  业务接口
 */
public interface OrderCommentService {

	/**
	 * 根据条件查询列表
	 */
	List<OrderComment> findListByParam(OrderCommentQuery param);

	/**
	 * 根据条件查询列表
	 */
	Integer findCountByParam(OrderCommentQuery param);

	/**
	 * 分页查询
	 */
	PaginationResultVO<OrderComment> findListByPage(OrderCommentQuery param);

	/**
	 * 新增
	 */
	Integer add(OrderComment bean);

	/**
	 * 批量新增
	 */
	Integer addBatch(List<OrderComment> listBean);

	/**
	 * 批量新增/修改
	 */
	Integer addOrUpdateBatch(List<OrderComment> listBean);

	/**
	 * 多条件更新
	 */
	Integer updateByParam(OrderComment bean,OrderCommentQuery param);

	/**
	 * 多条件删除
	 */
	Integer deleteByParam(OrderCommentQuery param);

	/**
	 * 根据OrderId查询对象
	 */
	OrderComment getOrderCommentByOrderId(String orderId);


	/**
	 * 根据OrderId修改
	 */
	Integer updateOrderCommentByOrderId(OrderComment bean,String orderId);


	/**
	 * 根据OrderId删除
	 */
	Integer deleteOrderCommentByOrderId(String orderId);

	/**
	 * 提交评价
	 * @param userId 用户ID
	 * @param orderId 订单ID
	 * @param commentContent 评价内容
	 * @param commentImages 评价图片
	 * @param star 评价星级
	 */
    void postComment(String userId, String orderId, String commentContent, String commentImages,Integer star);

	/**
	 * 追加评价
	 * @param userId 用户ID
	 * @param orderId 订单ID
	 * @param reCommentContent 评价内容
	 * @param reCommentImages 评价图片
	 */
	void postReComment(String userId, String orderId, String reCommentContent, String reCommentImages);

	/**
	 * 商家回复
	 * @param orderId 订单ID
	 * @param commentBizReply 回复内容
	 */
	void postBizComment(String orderId,String commentBizReply);
}