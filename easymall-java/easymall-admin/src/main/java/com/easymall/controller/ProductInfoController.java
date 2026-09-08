package com.easymall.controller;

import java.util.List;

import com.easymall.entity.dto.ProductSaveDTO;
import com.easymall.entity.query.ProductInfoQuery;
import com.easymall.entity.po.ProductInfo;
import com.easymall.entity.vo.ProductInfoDetailVO;
import com.easymall.entity.vo.ResponseVO;
import com.easymall.service.ProductInfoService;
import com.easymall.valid.createGroup;
import com.easymall.valid.updateGroup;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

/**
 * 商品信息 Controller
 */
@RestController("productInfoController")
@RequestMapping("/productInfo")
public class ProductInfoController extends ABaseController{

	@Resource
	private ProductInfoService productInfoService;
	/**
	 * 根据条件分页查询
	 */
	@RequestMapping("/loadProduct")
	public ResponseVO loadProductList(String productNameFuzzy, Integer pageNo, String categoryIdOrPCategoryId, Integer commendType,Integer status) {
		ProductInfoQuery query = new ProductInfoQuery();
		query.setPageNo(pageNo);
		query.setProductNameFuzzy(productNameFuzzy);
		query.setCommendType(commendType);
		query.setOrderBy("p.create_time desc");
		query.setCategoryIdOrPCategoryId(categoryIdOrPCategoryId);
		query.setStatus(status);

		return getSuccessResponseVO(productInfoService.findListByPageListVO(query));
	}

	/**
	 * 新增商品
	 */
	@RequestMapping("/addProduct")
	public ResponseVO addProduct(@RequestBody @Validated(createGroup.class) ProductSaveDTO productSaveDTO) {
		productInfoService.saveProduct(productSaveDTO);
		return getSuccessResponseVO(null);
	}

	@RequestMapping("/getProductInfo")
	public ResponseVO getProductInfo(@NotEmpty String productId) {
		return getSuccessResponseVO(productInfoService.getProductInfo(productId));
	}

	/**
	 * 修改商品
	 */
	@RequestMapping("/updateProduct")
	public ResponseVO updateProduct(@RequestBody @Validated(updateGroup.class) ProductSaveDTO productSaveDTO) {
		productInfoService.saveProduct(productSaveDTO);
		return getSuccessResponseVO(null);
	}

	/**
	 * 修改商品的上架和下架状态
	 */
	@RequestMapping("/updateProductStatus")
	public ResponseVO updateProductStatus(@NotEmpty String productId, @NotNull Integer status) {
		productInfoService.updateProductStatus(productId, status);
		return getSuccessResponseVO(null);
	}

	/**
	 * 逻辑删除商品
	 * @param productId 商品Id
	 */
	@RequestMapping("/deleteProduct")
	public ResponseVO deleteProduct(@NotNull String productId) {
		productInfoService.deleteProduct(productId);
		return getSuccessResponseVO(null);
	}

	/**
	 * 商品推荐
	 */
	@RequestMapping("/commendProduct")
	public ResponseVO commendProduct(@NotEmpty String productId,@NotNull Integer commendType) {
		ProductInfo productInfo = new ProductInfo();
		productInfo.setCommendType(commendType);
		productInfoService.updateProductInfoByProductId(productInfo,productId);
		return getSuccessResponseVO(null);
	}

}