package com.easymall.controller;

import com.easymall.annotation.GlobalInterceptor;
import com.easymall.entity.constants.Constants;
import com.easymall.entity.dto.TokenUserInfoDTO;
import com.easymall.entity.po.UserAddress;
import com.easymall.entity.query.UserAddressQuery;
import com.easymall.entity.vo.ResponseVO;
import com.easymall.service.UserAddressService;
import com.easymall.utils.StringTools;
import com.easymall.valid.CreateGroup;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

/**
 *  Controller
 */
@RestController
@RequestMapping("/userAddress")
@Validated
public class UserAddressController extends ABaseController{

	@Resource
	private UserAddressService userAddressService;

	/**
	 * 根据条件分页查询
	 */
	@RequestMapping("/loadDataList")
	@GlobalInterceptor(checkLogin = true)
	public ResponseVO loadDataList(){
		UserAddressQuery query = new UserAddressQuery();
		TokenUserInfoDTO tokenUserInfoDTO = getTokenUserInfo();
		query.setUserId(tokenUserInfoDTO.getUserId());
		query.setOrderBy("default_type desc");
		return getSuccessResponseVO(userAddressService.findListByParam(query));
	}

	/**
	 * 新增地址
	 * @param userAddress 用户输入地址
	 */
	@RequestMapping("/addAddress")
	@GlobalInterceptor(checkLogin = true)
	public ResponseVO addAddress(@Validated(CreateGroup.class) UserAddress userAddress){
		userAddress.setUserId(getTokenUserInfo().getUserId());
		userAddressService.saveAddress(userAddress);
		return getSuccessResponseVO(null);
	}

	/**
	 * 修改地址
	 * @param userAddress 修改用户地址
	 */
	@RequestMapping("/updateAddress")
	@GlobalInterceptor(checkLogin = true)
	public ResponseVO updateAddress(@Validated(CreateGroup.class) UserAddress userAddress){
		userAddress.setUserId(getTokenUserInfo().getUserId());
		userAddressService.saveAddress(userAddress);
		return getSuccessResponseVO(null);
	}

	/**
	 * 删除地址
	 * @param addressId 地址Id
	 */
	@RequestMapping("/delAddress")
	@GlobalInterceptor(checkLogin = true)
	public ResponseVO delAddress(@NotEmpty String addressId){
		UserAddressQuery userAddressQuery = new UserAddressQuery();
		userAddressQuery.setUserId(getTokenUserInfo().getUserId());
		userAddressQuery.setAddressId(addressId);

		userAddressService.deleteByParam(userAddressQuery);
		return getSuccessResponseVO(null);
	}

	/**
	 * 修改地址为默认地址
	 * @param addressId 修改的地址Id
	 */
	@RequestMapping("/updateDefault")
	@GlobalInterceptor(checkLogin = true)
	public ResponseVO updateDefault(@NotEmpty String addressId){
		String userId = getTokenUserInfo().getUserId();
		userAddressService.updateDefaultAddress(addressId,userId);
		return getSuccessResponseVO(null);
	}






}