package com.easymall.controller;

import java.util.List;

import com.easymall.entity.query.UserAddressQuery;
import com.easymall.entity.po.UserAddress;
import com.easymall.entity.vo.ResponseVO;
import com.easymall.service.UserAddressService;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

/**
 *  Controller
 */
@RestController("userAddressController")
@RequestMapping("/userAddress")
public class UserAddressController extends ABaseController{

	@Resource
	private UserAddressService userAddressService;
	/**
	 * 根据条件分页查询
	 */
	@RequestMapping("/loadDataList")
	public ResponseVO loadDataList(UserAddressQuery query){
		return getSuccessResponseVO(userAddressService.findListByPage(query));
	}

	/**
	 * 新增
	 */
	@RequestMapping("/add")
	public ResponseVO add(UserAddress bean) {
		userAddressService.add(bean);
		return getSuccessResponseVO(null);
	}

	/**
	 * 批量新增
	 */
	@RequestMapping("/addBatch")
	public ResponseVO addBatch(@RequestBody List<UserAddress> listBean) {
		userAddressService.addBatch(listBean);
		return getSuccessResponseVO(null);
	}

	/**
	 * 批量新增/修改
	 */
	@RequestMapping("/addOrUpdateBatch")
	public ResponseVO addOrUpdateBatch(@RequestBody List<UserAddress> listBean) {
		userAddressService.addBatch(listBean);
		return getSuccessResponseVO(null);
	}

	/**
	 * 根据AddressId查询对象
	 */
	@RequestMapping("/getUserAddressByAddressId")
	public ResponseVO getUserAddressByAddressId(String addressId) {
		return getSuccessResponseVO(userAddressService.getUserAddressByAddressId(addressId));
	}

	/**
	 * 根据AddressId修改对象
	 */
	@RequestMapping("/updateUserAddressByAddressId")
	public ResponseVO updateUserAddressByAddressId(UserAddress bean,String addressId) {
		userAddressService.updateUserAddressByAddressId(bean,addressId);
		return getSuccessResponseVO(null);
	}

	/**
	 * 根据AddressId删除
	 */
	@RequestMapping("/deleteUserAddressByAddressId")
	public ResponseVO deleteUserAddressByAddressId(String addressId) {
		userAddressService.deleteUserAddressByAddressId(addressId);
		return getSuccessResponseVO(null);
	}
}