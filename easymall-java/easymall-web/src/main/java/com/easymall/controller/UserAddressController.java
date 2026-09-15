package com.easymall.controller;

import java.util.List;

import com.easymall.annotation.GlobalInterceptor;
import com.easymall.entity.dto.TokenUserInfoDTO;
import com.easymall.entity.query.UserAddressQuery;
import com.easymall.entity.po.UserAddress;
import com.easymall.entity.vo.ResponseVO;
import com.easymall.service.UserAddressService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestBody;
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


}