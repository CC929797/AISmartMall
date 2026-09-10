package com.easymall.controller;


import com.easymall.entity.constants.Constants;
import com.easymall.entity.enums.CommendTypeEnum;
import com.easymall.entity.enums.ProductStatusEnum;
import com.easymall.entity.po.ProductInfo;
import com.easymall.entity.po.SysCategory;
import com.easymall.entity.query.ProductInfoQuery;
import com.easymall.entity.query.SimplePage;
import com.easymall.entity.vo.PaginationResultVO;
import com.easymall.entity.vo.ResponseVO;
import com.easymall.service.ProductInfoService;
import com.easymall.service.SysCategoryService;
import jakarta.annotation.Resource;
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

}
