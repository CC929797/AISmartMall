package com.easymall.controller;

import java.util.List;

import com.easymall.annotation.GlobalInterceptor;
import com.easymall.entity.query.ProductCartQuery;
import com.easymall.entity.po.ProductCart;
import com.easymall.entity.vo.ResponseVO;
import com.easymall.service.ProductCartService;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

/**
 * 购物车 Controller
 */
@RestController("productCartController")
@RequestMapping("/productCart")
public class ProductCartController extends ABaseController{

	@Resource
	private ProductCartService productCartService;

	/**
	 * 添加商品进购物车
	 * @param productCart
	 * @return
	 */
	@RequestMapping("/add2Cart")
	@GlobalInterceptor(checkLogin = true)
	public ResponseVO add2Cart(ProductCart productCart) {
		productCart.setUserId(getTokenUserInfo().getUserId());
		productCartService.add2Cart(productCart);
		return getSuccessResponseVO(null);
	}
}