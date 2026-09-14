package com.easymall.controller;


import com.easymall.entity.constants.Constants;
import com.easymall.entity.enums.CommendTypeEnum;
import com.easymall.entity.enums.ProductStatusEnum;
import com.easymall.entity.po.ProductInfo;
import com.easymall.entity.po.SysCategory;
import com.easymall.entity.query.ProductInfoQuery;
import com.easymall.entity.query.SimplePage;
import com.easymall.entity.vo.PaginationResultVO;
import com.easymall.entity.vo.ProductInfoDetailVO;
import com.easymall.entity.vo.ProductListVO;
import com.easymall.entity.vo.ResponseVO;
import com.easymall.service.ProductInfoService;
import com.easymall.service.SysCategoryService;
import com.easymall.utils.StringTools;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/product")
public class ProductController extends ABaseController {
    @Resource
    private SysCategoryService sysCategoryService;
    @Resource
    private ProductInfoService productInfoService;

    @RequestMapping("/loadCategory")
    public ResponseVO loadCategory() {
        List<SysCategory> categoryList = sysCategoryService.getAllCategoryList();
        return getSuccessResponseVO(categoryList);
    }

    @RequestMapping("/loadCommendProduct")
    public ResponseVO loadCommendProduct(){
        ProductInfoQuery productInfoQuery = new ProductInfoQuery();
        productInfoQuery.setOrderBy("create_time desc");
        productInfoQuery.setStatus(ProductStatusEnum.ON_SALE.getStatus());
        productInfoQuery.setCommendType(CommendTypeEnum.COMMEND.getType());
        productInfoQuery.setSimplePage(new SimplePage(0,11));
        List<ProductInfo> productInfoList = productInfoService.findListByParam(productInfoQuery);

        return getSuccessResponseVO(productInfoList);
    }

    /**
     * 查询商品信息（根据是否有CategoryId判断是首页查询还是分类查询）
     */
    @RequestMapping("/loadProduct")
    public ResponseVO loadProduct(Integer pageNo, String categoryId) {
        ProductInfoQuery productInfoQuery = new ProductInfoQuery();
        productInfoQuery.setOrderBy("create_time desc");
        productInfoQuery.setStatus(ProductStatusEnum.ON_SALE.getStatus());
        productInfoQuery.setCategoryId(categoryId);
        productInfoQuery.setPageNo(pageNo);
        // 如果分类Id是空的话，代表的是首页查询
        if(StringTools.isEmpty(categoryId)) {
            productInfoQuery.setCommendType(CommendTypeEnum.NOT_COMMEND.getType());
        }
        PaginationResultVO<ProductInfo> resultVO = productInfoService.findListByPage(productInfoQuery);

        return getSuccessResponseVO(resultVO);
    }

    /**
     * 点击查看商品详细
     * @param productId 商品Id
     */
    @RequestMapping("/getProduct")
    public ResponseVO getProduct(@NotEmpty String productId) {
        return getSuccessResponseVO(productInfoService.getProductInfo(productId));
    }

}
