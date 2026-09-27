package com.easymall.service.Impl;

import java.util.List;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import com.easymall.entity.enums.PageSize;
import com.easymall.entity.query.OrderLogisticsInfoQuery;
import com.easymall.entity.po.OrderLogisticsInfo;
import com.easymall.entity.vo.PaginationResultVO;
import com.easymall.entity.query.SimplePage;
import com.easymall.mappers.OrderLogisticsInfoMapper;
import com.easymall.service.OrderLogisticsInfoService;
import com.easymall.utils.StringTools;


/**
 * 物流信息表 业务接口实现
 */
@Service("orderLogisticsInfoService")
public class OrderLogisticsInfoServiceImpl implements OrderLogisticsInfoService {

	@Resource
	private OrderLogisticsInfoMapper<OrderLogisticsInfo, OrderLogisticsInfoQuery> orderLogisticsInfoMapper;

	/**
	 * 根据条件查询列表
	 */
	@Override
	public List<OrderLogisticsInfo> findListByParam(OrderLogisticsInfoQuery param) {
		return this.orderLogisticsInfoMapper.selectList(param);
	}

	/**
	 * 根据条件查询列表
	 */
	@Override
	public Integer findCountByParam(OrderLogisticsInfoQuery param) {
		return this.orderLogisticsInfoMapper.selectCount(param);
	}

	/**
	 * 分页查询方法
	 */
	@Override
	public PaginationResultVO<OrderLogisticsInfo> findListByPage(OrderLogisticsInfoQuery param) {
		int count = this.findCountByParam(param);
		int pageSize = param.getPageSize() == null ? PageSize.SIZE15.getSize() : param.getPageSize();

		SimplePage page = new SimplePage(param.getPageNo(), count, pageSize);
		param.setSimplePage(page);
		List<OrderLogisticsInfo> list = this.findListByParam(param);
		PaginationResultVO<OrderLogisticsInfo> result = new PaginationResultVO(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
		return result;
	}

	/**
	 * 新增
	 */
	@Override
	public Integer add(OrderLogisticsInfo bean) {
		return this.orderLogisticsInfoMapper.insert(bean);
	}

	/**
	 * 批量新增
	 */
	@Override
	public Integer addBatch(List<OrderLogisticsInfo> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.orderLogisticsInfoMapper.insertBatch(listBean);
	}

	/**
	 * 批量新增或者修改
	 */
	@Override
	public Integer addOrUpdateBatch(List<OrderLogisticsInfo> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.orderLogisticsInfoMapper.insertOrUpdateBatch(listBean);
	}

	/**
	 * 多条件更新
	 */
	@Override
	public Integer updateByParam(OrderLogisticsInfo bean, OrderLogisticsInfoQuery param) {
		StringTools.checkParam(param);
		return this.orderLogisticsInfoMapper.updateByParam(bean, param);
	}

	/**
	 * 多条件删除
	 */
	@Override
	public Integer deleteByParam(OrderLogisticsInfoQuery param) {
		StringTools.checkParam(param);
		return this.orderLogisticsInfoMapper.deleteByParam(param);
	}

	/**
	 * 根据OrderId获取对象
	 */
	@Override
	public OrderLogisticsInfo getOrderLogisticsInfoByOrderId(String orderId) {
		return this.orderLogisticsInfoMapper.selectByOrderId(orderId);
	}

	/**
	 * 根据OrderId修改
	 */
	@Override
	public Integer updateOrderLogisticsInfoByOrderId(OrderLogisticsInfo bean, String orderId) {
		return this.orderLogisticsInfoMapper.updateByOrderId(bean, orderId);
	}

	/**
	 * 根据OrderId删除
	 */
	@Override
	public Integer deleteOrderLogisticsInfoByOrderId(String orderId) {
		return this.orderLogisticsInfoMapper.deleteByOrderId(orderId);
	}
}