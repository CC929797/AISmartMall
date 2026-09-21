package com.easymall.controller;

import java.util.List;

import com.easymall.annotation.GlobalInterceptor;
import com.easymall.entity.query.ProductCartQuery;
import com.easymall.entity.po.ProductCart;
import com.easymall.entity.vo.PaginationResultVO;
import com.easymall.entity.vo.ResponseVO;
import com.easymall.service.ProductCartService;
import jakarta.validation.constraints.NotEmpty;
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

	@RequestMapping("/loadProductCart")
	@GlobalInterceptor(checkLogin = true)
	public ResponseVO loadProductCart(Integer pageNo) {
		ProductCartQuery query = new ProductCartQuery();
		query.setUserId(getTokenUserInfo().getUserId());
		query.setPageNo(pageNo);
		query.setOrderBy("p.last_update_time desc");
		PaginationResultVO resultVO = productCartService.loadProductCart(query);
		return getSuccessResponseVO(resultVO);
	}

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

	/**
	 * 删除购物车当中的商品
	 * @param cartId
	 * @return
	 */
	@RequestMapping("/deleteCart")
	@GlobalInterceptor(checkLogin = true)
	public ResponseVO deleteCart(@NotEmpty String cartId) {
		ProductCartQuery productCartQuery = new ProductCartQuery();
		productCartQuery.setUserId(getTokenUserInfo().getUserId());
		productCartQuery.setCartId(cartId);
		productCartService.deleteByParam(productCartQuery);
		return getSuccessResponseVO(null);
	}
}