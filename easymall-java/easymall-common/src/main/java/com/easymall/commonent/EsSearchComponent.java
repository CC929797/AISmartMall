package com.easymall.commonent;

import com.easymall.entity.enums.PageSize;
import com.easymall.entity.enums.ProductStatusEnum;
import com.easymall.entity.enums.SearchFieldTypeEnum;
import com.easymall.entity.enums.SearchSortTypeEnum;
import com.easymall.entity.po.ProductInfo;
import com.easymall.entity.query.ProductInfoQuery;
import com.easymall.entity.vo.PaginationResultVO;
import com.easymall.entity.vo.ProductInfoDTO;
import com.easymall.exception.BusinessException;
import com.easymall.mappers.ProductInfoMapper;
import com.easymall.utils.CopyTools;
import com.easymall.utils.JsonUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class EsSearchComponent {
    @Resource
    private ElasticsearchOperations elasticsearchOperations;
    @Resource
    private ProductInfoMapper<ProductInfo,ProductInfoQuery> productInfoMapper;

    @PostConstruct
    public void createIndexWithIK(){
        try {
            IndexOperations indexOperations = elasticsearchOperations.indexOps(ProductInfoDTO.class);
            if (indexOperations.exists()) {
                return;
            }
            String json = """
                    {
                      "analysis": {
                        "analyzer": {
                          "ik_max_word": {
                            "type": "custom",
                            "tokenizer": "ik_max_word"
                          },
                          "ik_smart": {
                            "type": "custom",
                            "tokenizer": "ik_smart"
                          }
                        }
                      }
                    }
                    """;
            indexOperations.create(JsonUtils.convertJson2Obj(json, Map.class));
            Document mapping = indexOperations.createMapping(ProductInfoDTO.class);
            indexOperations.putMapping(mapping);
            log.info("创建索引成功");
        }catch (Exception e){
            log.error("创建索引失败",e);
            throw new BusinessException("创建索引失败");
        }
    }

    /**
     * 在ElasticSearch中保存/删除商品
     * @param productId 商品ID
     */
    public void saveProduct(String productId){
        ProductInfo productInfo = productInfoMapper.selectByProductId(productId);
        ProductInfoDTO productInfoDTO = CopyTools.copy(productInfo, ProductInfoDTO.class);
        if (ProductStatusEnum.ON_SALE.getStatus().equals(productInfo.getStatus())) {
            elasticsearchOperations.save(productInfoDTO);
        }else {
            elasticsearchOperations.delete(productId, ProductInfoDTO.class);
        }
    }

    /**
     * 从ElasticSearch中搜索商品 分页获取到商品信息
     * @param keyWords 关键词
     * @param priceFrom 价格下限
     * @param priceTo 价格上限
     * @param sortType 排序类型
     * @param softField 排序字段
     * @param pageNo 页码
     * @return 分页商品信息
     */
    public PaginationResultVO<ProductInfoDTO> searchProduct(String keyWords,
            BigDecimal priceFrom, BigDecimal priceTo,
            String sortType, String softField, Integer pageNo){
        try {
            pageNo = pageNo == null ? 1 : pageNo;
            pageNo = pageNo - 1;
            Integer pageSize = PageSize.SIZE15.getSize();

            Criteria criteria = new Criteria();

            //商品名称过滤
            if (keyWords.length() <= 2){
                Criteria nameCriteria = new Criteria();
                nameCriteria = nameCriteria.or(new Criteria("productName").contains(keyWords));
                nameCriteria = nameCriteria.or(new Criteria("productName").expression("*" + keyWords + "*"));
                nameCriteria = nameCriteria.or(new Criteria("productName").matches(keyWords));
                criteria.and(nameCriteria);
            }else {
                criteria = criteria.and("productName").contains(keyWords);
            }

            //价格过滤
            if(priceFrom != null || priceTo != null){
                Criteria priceCriteria = new Criteria();
                priceCriteria = priceCriteria.and("minPrice");
                if(priceFrom != null){
                    priceCriteria = priceCriteria.greaterThanEqual(priceFrom);
                }
                if(priceTo != null){
                    priceCriteria = priceCriteria.lessThanEqual(priceTo);
                }
                criteria = criteria.and(priceCriteria);
            }

            //设置是顺序还是逆序
            SearchSortTypeEnum sortTypeEnum = SearchSortTypeEnum.getByType(sortType);
            sortTypeEnum = sortTypeEnum == null ? SearchSortTypeEnum.DESC : sortTypeEnum;

            //设置按照字段来查询
            SearchFieldTypeEnum fieldTypeEnum = SearchFieldTypeEnum.getByFieldType(softField);
            fieldTypeEnum = fieldTypeEnum == null ? SearchFieldTypeEnum.COMPOSITE : fieldTypeEnum;

            //把排序给添加进排序的对象
            Sort sort = Sort.by(sortTypeEnum.getDirection(),fieldTypeEnum.getField());
            //排序联合分页封装进Pageable
            Pageable pageable = PageRequest.of(pageNo, pageSize, sort);

            //把条件封装进CriteriaQuery查询参数中
            CriteriaQuery query = new CriteriaQuery(criteria);
            query.setPageable(pageable);
            SearchHits<ProductInfoDTO> searchHits = elasticsearchOperations.search(query, ProductInfoDTO.class);

            //从获取到的searchHit获取到List<ProductInfoDTO>集合
            List<ProductInfoDTO> productInfoDTO = searchHits.getSearchHits().stream().map(hit -> hit.getContent()).toList();

            long totalHits = searchHits.getTotalHits();
            int totalPage = (int) Math.ceil((double) totalHits / pageSize);

            return new PaginationResultVO<>((int)totalHits,pageSize,pageNo + 1,totalPage,productInfoDTO);
        }catch (Exception e){
            log.error("搜索商品失败", e);
            return new PaginationResultVO<>(0,PageSize.SIZE15.getSize(),pageNo != null ? pageNo : 1,0,new ArrayList<>());
        }
    }


}
