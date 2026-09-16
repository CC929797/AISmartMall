package com.easymall.service;

import java.util.List;

import com.easymall.entity.query.UserAddressQuery;
import com.easymall.entity.po.UserAddress;
import com.easymall.entity.vo.PaginationResultVO;


/**
 *  业务接口
 */
public interface UserAddressService {

	/**
	 * 根据条件查询列表
	 */
	List<UserAddress> findListByParam(UserAddressQuery param);

	/**
	 * 根据条件查询列表
	 */
	Integer findCountByParam(UserAddressQuery param);

	/**
	 * 分页查询
	 */
	PaginationResultVO<UserAddress> findListByPage(UserAddressQuery param);

	/**
	 * 新增
	 */
	Integer add(UserAddress bean);

	/**
	 * 批量新增
	 */
	Integer addBatch(List<UserAddress> listBean);

	/**
	 * 批量新增/修改
	 */
	Integer addOrUpdateBatch(List<UserAddress> listBean);

	/**
	 * 多条件更新
	 */
	Integer updateByParam(UserAddress bean,UserAddressQuery param);

	/**
	 * 多条件删除
	 */
	Integer deleteByParam(UserAddressQuery param);

	/**
	 * 根据AddressId查询对象
	 */
	UserAddress getUserAddressByAddressId(String addressId);


	/**
	 * 根据AddressId修改
	 */
	Integer updateUserAddressByAddressId(UserAddress bean,String addressId);


	/**
	 * 根据AddressId删除
	 */
	Integer deleteUserAddressByAddressId(String addressId);

	/**
	 * 修改地址为默认地址
	 * @param addressId 修改的地址Id
	 * @param userId 修改的用户Id
	 */
	void updateDefaultAddress(String addressId,String userId);

	/**
	 * 新增或修改地址信息
	 * @param userAddress
	 */
	void saveAddress(UserAddress userAddress);
}