package com.easymall.task;

import com.easymall.entity.constants.Constants;
import com.easymall.entity.dto.RagDataDTO;
import com.easymall.entity.enums.ExecutorServiceSignletionEnum;
import com.easymall.entity.enums.ProductStatusEnum;
import com.easymall.entity.enums.RagDataTypeEnum;
import com.easymall.entity.po.ProductInfo;
import com.easymall.entity.po.ProductPropertyValue;
import com.easymall.entity.po.ProductSku;
import com.easymall.entity.po.RagQuestion;
import com.easymall.entity.query.ProductInfoQuery;
import com.easymall.entity.query.ProductPropertyValueQuery;
import com.easymall.entity.query.ProductSkuQuery;
import com.easymall.mappers.ProductInfoMapper;
import com.easymall.mappers.ProductPropertyValueMapper;
import com.easymall.mappers.ProductSkuMapper;
import com.easymall.service.ProductInfoService;
import com.easymall.service.RagQuestionService;
import com.easymall.utils.StringTools;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
@Slf4j
public class RagDataTask {
    @Resource
    private RedissonClient redissonClient;

    @Resource
    private RagQuestionService ragQuestionService;

    @Resource
    private VectorStore vectorStore;

    @Resource
    private ProductInfoMapper<ProductInfo, ProductInfoQuery> productInfoMapper;

    @Resource
    private ProductPropertyValueMapper<ProductPropertyValue, ProductPropertyValueQuery> productPropertyValueMapper;

    @Resource
    private ProductSkuMapper<ProductSku,ProductSkuQuery> productSkuMapper;

    /**
     * 自动化订阅消息，把消息保存到向量数据库
     */
    @PostConstruct
    public void subscribe() {
        ExecutorServiceSignletionEnum.INSTANCE.getExecutorService().execute(() -> {
            RBlockingQueue<RagDataDTO> queue = redissonClient.getBlockingQueue(Constants.REDIS_QUEUE_RAG_DATA, JsonJacksonCodec.INSTANCE);
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    RagDataDTO ragDataDTO = queue.take();
                    RagDataTypeEnum ragDataTypeEnum = RagDataTypeEnum.getByType(ragDataDTO.getType());
                    switch (ragDataTypeEnum) {
                        case FAQ :
                            saveData2VectorStore4FAQ(ragDataDTO.getDataId());
                            break;
                        case PRODUCT :
                            log.info("收到商品信息 {}", ragDataDTO.getDataId());
                            saveData2VectorStore4Product(ragDataDTO.getDataId());
                            break;
                        default:
                            break;
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.info("消息消费被中断");
                    break;
                } catch (Exception e) {
                    log.error("处理消息失败", e);
                }
            }
        });
    }

    /**
     * 将商品数据保存到向量数据库
     * @param productId 商品ID
     */
    private void saveData2VectorStore4Product(String productId) {
        ProductInfo productInfo = this.productInfoMapper.selectByProductId(productId);
        if (!ProductStatusEnum.ON_SALE.getStatus().equals(productInfo.getStatus())) {
            vectorStore.delete(RagDataTypeEnum.PRODUCT.getType() + productId);
            return;
        }
        ProductPropertyValueQuery productPropertyValueQuery = new ProductPropertyValueQuery();
        productPropertyValueQuery.setProductId(productId);
        productPropertyValueQuery.setOrderBy("property_sort asc");
        List<ProductPropertyValue> productPropertyValueList = this.productPropertyValueMapper.selectList(productPropertyValueQuery);

        Map<String, ProductPropertyValue> propertyValueMap =
                productPropertyValueList.stream().collect(Collectors.toMap(item -> item.getPropertyValueId(),
                propertyValue -> propertyValue));

        ProductSkuQuery productSkuQuery = new ProductSkuQuery();
        productSkuQuery.setProductId(productId);
        List<ProductSku> allProductSkuList = this.productSkuMapper.selectList(productSkuQuery);
        List<Document> list = new ArrayList<>();
        Map<String , Object> metaData = new HashMap<>();
        metaData.put("dataType", RagDataTypeEnum.PRODUCT.getType());
        metaData.put("productId", productId);
        metaData.put("productName", productInfo.getProductName());
        Document doc = new Document(productId,productInfo.getProductName(),metaData);
        list.add(doc);

        List<Map<String,Object>> skuList = new ArrayList<>();
        for (ProductSku sku : allProductSkuList) {
            metaData = new HashMap<>();
            metaData.put("dataType", RagDataTypeEnum.PRODUCT.getType());
            metaData.put("productId", sku.getProductId());
            metaData.put("productName",productInfo.getProductName());
            StringBuilder content = new StringBuilder();
            content.append(productInfo.getProductName()).append(" ");
            Map<String,Object> skuProperty = new HashMap<>();
            String[] propertyValueIds = sku.getPropertyValueIds().split("-");
            StringBuilder skuContent = new StringBuilder();
            Integer index = 0;
            for (String propertyValueId : propertyValueIds) {
                index++;
                ProductPropertyValue propertyValue = propertyValueMap.get(propertyValueId);
                skuContent = skuContent.append(propertyValue.getPropertyName()).append("：").append(propertyValue.getPropertyValue());
                if (index < propertyValueIds.length) {
                    skuContent.append("，");
                }
            }
            content.append(skuContent);
            skuProperty.put("property",skuContent);
            skuList.add(skuProperty);
            metaData.put("skuList",skuList);
            doc = new Document(sku.getProductId() + "_" + sku.getPropertyValueIdHash(),content.toString(),metaData);
            list.add(doc);
            if (list.size() >= 10){
                //阿里百炼如果一次性往向量数据库添加超过10条数据会报错
                vectorStore.add(list);
                list.clear();
            }
        }

        if (!list.isEmpty()) {
            vectorStore.add(list);
        }
    }


    /**
     * 将FAQ数据(知识库问题)保存到向量数据库
     * @param questionId 问题ID
     */
    private void saveData2VectorStore4FAQ(String questionId) {
        //查询数据
        RagQuestion ragQuestion = ragQuestionService.getRagQuestionByQuestionId(Integer.parseInt(questionId));
        if (ragQuestion == null) {
            vectorStore.delete(RagDataTypeEnum.FAQ.getType() + questionId);
            return;
        }

        //构建Document
        List<Document> list = new ArrayList<>();
        //构建metaData设置问题的元数据信息
        Map<String , Object> metaData = new HashMap<>();
        metaData.put("dataType", RagDataTypeEnum.FAQ.getType());
        metaData.put("questionId", questionId);
        metaData.put("question", ragQuestion.getQuestion());
        metaData.put("answer", ragQuestion.getAnswer());
        if (!StringTools.isEmpty(ragQuestion.getSimilarQuestion())) {
            metaData.put("similarQuestion", ragQuestion.getSimilarQuestion());
        } else {
            metaData.put("similarQuestion", "暂无相似问题");
        }

        StringBuilder content = new StringBuilder();
        content.append(ragQuestion.getQuestion()).append(" ");
        if (!StringTools.isEmpty(ragQuestion.getSimilarQuestion())) {
            content.append("相似问题：").append(ragQuestion.getSimilarQuestion()).append(" ");
        }
        content.append("答案：").append(ragQuestion.getAnswer()).append(" ");

        //构建Document对象,添加到list中
        Document document = new Document(questionId,content.toString(),metaData);
        list.add(document);
        //添加数据到向量数据库
        vectorStore.add(list);
    }
}
