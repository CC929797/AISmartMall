package com.easymall.service.Impl;

import java.util.List;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import com.easymall.entity.enums.PageSize;
import com.easymall.entity.query.UserAddressQuery;
import com.easymall.entity.po.UserAddress;
import com.easymall.entity.vo.PaginationResultVO;
import com.easymall.entity.query.SimplePage;
import com.easymall.mappers.UserAddressMapper;
import com.easymall.service.UserAddressService;
import com.easymall.utils.StringTools;


/**
 *  业务接口实现
 */
@Service("userAddressService")
public class UserAddressServiceImpl implements UserAddressService {

	@Resource
	private UserAddressMapper<UserAddress, UserAddressQuery> userAddressMapper;

	/**
	 * 根据条件查询列表
	 */
	@Override
	public List<UserAddress> findListByParam(UserAddressQuery param) {
		return this.userAddressMapper.selectList(param);
	}

	/**
	 * 根据条件查询列表
	 */
	@Override
	public Integer findCountByParam(UserAddressQuery param) {
		return this.userAddressMapper.selectCount(param);
	}

	/**
	 * 分页查询方法
	 */
	@Override
	public PaginationResultVO<UserAddress> findListByPage(UserAddressQuery param) {
		int count = this.findCountByParam(param);
		int pageSize = param.getPageSize() == null ? PageSize.SIZE15.getSize() : param.getPageSize();

		SimplePage page = new SimplePage(param.getPageNo(), count, pageSize);
		param.setSimplePage(page);
		List<UserAddress> list = this.findListByParam(param);
		PaginationResultVO<UserAddress> result = new PaginationResultVO(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
		return result;
	}

	/**
	 * 新增
	 */
	@Override
	public Integer add(UserAddress bean) {
		return this.userAddressMapper.insert(bean);
	}

	/**
	 * 批量新增
	 */
	@Override
	public Integer addBatch(List<UserAddress> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.userAddressMapper.insertBatch(listBean);
	}

	/**
	 * 批量新增或者修改
	 */
	@Override
	public Integer addOrUpdateBatch(List<UserAddress> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.userAddressMapper.insertOrUpdateBatch(listBean);
	}

	/**
	 * 多条件更新
	 */
	@Override
	public Integer updateByParam(UserAddress bean, UserAddressQuery param) {
		StringTools.checkParam(param);
		return this.userAddressMapper.updateByParam(bean, param);
	}

	/**
	 * 多条件删除
	 */
	@Override
	public Integer deleteByParam(UserAddressQuery param) {
		StringTools.checkParam(param);
		return this.userAddressMapper.deleteByParam(param);
	}

	/**
	 * 根据AddressId获取对象
	 */
	@Override
	public UserAddress getUserAddressByAddressId(String addressId) {
		return this.userAddressMapper.selectByAddressId(addressId);
	}

	/**
	 * 根据AddressId修改
	 */
	@Override
	public Integer updateUserAddressByAddressId(UserAddress bean, String addressId) {
		return this.userAddressMapper.updateByAddressId(bean, addressId);
	}

	/**
	 * 根据AddressId删除
	 */
	@Override
	public Integer deleteUserAddressByAddressId(String addressId) {
		return this.userAddressMapper.deleteByAddressId(addressId);
	}
}