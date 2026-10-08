package com.easymall.controller;

import com.easymall.commonent.EsSearchComponent;
import com.easymall.commonent.RedisComponent;
import com.easymall.entity.enums.DateTimePatternEnum;
import com.easymall.entity.enums.ProductStatusEnum;
import com.easymall.entity.po.ProductInfo;
import com.easymall.entity.po.RagQuestion;
import com.easymall.entity.query.ProductInfoQuery;
import com.easymall.entity.query.RagQuestionQuery;
import com.easymall.entity.vo.ResponseVO;
import com.easymall.service.ProductInfoService;
import com.easymall.service.RagQuestionService;
import com.easymall.service.StatisticsInfoService;
import com.easymall.utils.DateUtil;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;
import java.util.List;

@RestController
@RequestMapping("/tool")
public class ToolController extends ABaseController{
    @Resource
    private StatisticsInfoService statisticsInfoService;
    @Resource
    private ProductInfoService productInfoService;
    @Resource
    private RedisComponent redisComponent;
    @Resource
    private EsSearchComponent esSearchComponent;
    @Resource
    private RagQuestionService ragQuestionService;

    /**
     * 手动同步统计数据
     * @return
     */
    @RequestMapping("/statistics")
    public ResponseVO statistics() {
        String beforeDate = DateUtil.getBeforeDay(7, DateTimePatternEnum.YYYY_MM_DD.getPattern());
        List<String> dateList = DateUtil.getDateRange(beforeDate, DateUtil.format(new Date(), DateTimePatternEnum.YYYY_MM_DD.getPattern()),
                DateTimePatternEnum.YYYY_MM_DD.getPattern());

        for (String date : dateList) {
            statisticsInfoService.statisticsData(date);
        }

        return getSuccessResponseVO(null);
    }

    /**
     * 手动同步商品数据
     * @return
     */
    @RequestMapping("/productData")
    public ResponseVO productData() {
        ProductInfoQuery productInfoQuery = new ProductInfoQuery();
        productInfoQuery.setStatus(ProductStatusEnum.ON_SALE.getStatus());
        List<ProductInfo> productInfoList = productInfoService.findListByParam(productInfoQuery);
        for (ProductInfo productInfo : productInfoList) {
            esSearchComponent.saveProduct(productInfo.getProductId());
            //TODO: 1.将商品数据保存到向量数据库中
        }
        return getSuccessResponseVO(null);
    }

    /**
     * 手动同步知识库数据
     * @return
     */
    @RequestMapping("/ragData")
    public ResponseVO ragData(){
        RagQuestionQuery ragQuestionQuery = new RagQuestionQuery();
        List<RagQuestion> ragQuestionList = ragQuestionService.findListByParam(ragQuestionQuery);
        for (RagQuestion ragQuestion : ragQuestionList) {
            //TODO: 1.将知识库写入向量数据库
        }
        return getSuccessResponseVO(null);
    }
}
