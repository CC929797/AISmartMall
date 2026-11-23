package com.easymall.commonent;

import com.easymall.entity.enums.PageSize;
import com.easymall.entity.enums.ProductStatusEnum;
import com.easymall.entity.enums.SearchFieldTypeEnum;
import com.easymall.entity.enums.SearchSortTypeEnum;
import com.easymall.entity.dto.request.ProductSearchRequest;
import com.easymall.entity.po.ProductInfo;
import com.easymall.entity.query.ProductInfoQuery;
import com.easymall.entity.vo.PaginationResultVO;
import com.easymall.entity.vo.ProductInfoDTO;
import com.easymall.mappers.ProductInfoMapper;
import com.easymall.sync.SearchSyncService;
import com.easymall.sync.SearchSyncState;
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
import org.springframework.data.elasticsearch.core.query.ScriptType;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Slf4j
public class EsSearchComponent {
    @Resource
    private ElasticsearchOperations elasticsearchOperations;
    @Resource
    private ProductInfoMapper<ProductInfo,ProductInfoQuery> productInfoMapper;
    @Resource
    private SearchSyncService searchSyncService;

    @PostConstruct
    public void createIndexWithIK() {
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
        }catch (Exception e) {
            log.error("创建索引失败，异常类型={}", e.getClass().getSimpleName());
        }
    }

    /**
     * 同一商品用固定文档 ID，由 ES 脚本在一次更新中比较版本和写入。
     * 下架或删除时留下商品 ID 和版本号，迟到的旧任务比较版本时会直接跳过。
     */
    public void writeProductVersion(String productId, long version) {
        ProductInfo product = productInfoMapper.selectByProductId(productId);
        Map<String, Object> document = new HashMap<>();
        document.put("productId", productId);
        document.put("syncVersion", version);
        if (product != null && ProductStatusEnum.ON_SALE.getStatus().equals(product.getStatus())) {
            document.put("productName", product.getProductName());
            document.put("cover", product.getCover());
            // ES 价格字段原本就是 double。脚本参数使用 JSON 数值，商城实际金额仍从 MySQL 读取。
            if (product.getMinPrice() != null) {
                document.put("minPrice", product.getMinPrice().doubleValue());
            }
            if (product.getMaxPrice() != null) {
                document.put("maxPrice", product.getMaxPrice().doubleValue());
            }
            document.put("totalSale", product.getTotalSale());
        }

        IndexOperations index = elasticsearchOperations.indexOps(ProductInfoDTO.class);
        if (!index.exists()) {
            createIndexWithIK();
            if (!index.exists()) {
                throw new IllegalStateException("商品索引不可用");
            }
        }
        // 同版本重试无副作用。旧文档无 syncVersion，由第一份新投影接管。
        UpdateQuery update = UpdateQuery.builder(productId)
                .withLang("painless")
                .withScriptType(ScriptType.INLINE)
                .withScript("""
                        if (ctx._source.syncVersion != null && ctx._source.syncVersion >= params.version) {
                          ctx.op = 'noop';
                        } else {
                          ctx._source.clear();
                          ctx._source.putAll(params.document);
                        }
                        """)
                .withParams(Map.of("version", version, "document", document))
                .withScriptedUpsert(true).withUpsert(Document.create()).withRetryOnConflict(3).build();
        elasticsearchOperations.update(update, index.getIndexCoordinates());
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
            String sortType, String softField, Integer pageNo) {
        //封装请求参数，统一进行校验
        ProductSearchRequest request = new ProductSearchRequest();
        request.setKeyWords(keyWords);
        request.setPriceFrom(priceFrom);
        request.setPriceTo(priceTo);
        request.setSortType(sortType);
        request.setSortField(softField);
        request.setPageNo(pageNo);
        // 非法输入必须在异常兜底之前拒绝，不能伪装为“未找到商品”。
        request.validate();
        try {
            pageNo = pageNo == null ? 1 : pageNo;
            pageNo = pageNo - 1;
            Integer pageSize = PageSize.SIZE15.getSize();

            Criteria criteria = new Criteria();

            //商品名称过滤
            if (keyWords.length() <= 2) {
                Criteria nameCriteria = new Criteria();
                nameCriteria = nameCriteria.or(new Criteria("productName").contains(keyWords));
                nameCriteria = nameCriteria.or(new Criteria("productName").expression("*" + keyWords + "*"));
                nameCriteria = nameCriteria.or(new Criteria("productName").matches(keyWords));
                criteria.and(nameCriteria);
            } else {
                criteria = criteria.and("productName").contains(keyWords);
            }

            //价格过滤
            if (priceFrom != null || priceTo != null) {
                Criteria priceCriteria = new Criteria();
                priceCriteria = priceCriteria.and("minPrice");
                if (priceFrom != null) {
                    priceCriteria = priceCriteria.greaterThanEqual(priceFrom);
                }
                if (priceTo != null) {
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

            // ES 的命中只是候选。返回页面前，用 MySQL 核对是否仍在售、文档版本是否有效。
            List<ProductInfoDTO> productInfoDTO = visibleProducts(searchHits.getSearchHits().stream()
                    .map(SearchHit::getContent).toList());

            long totalHits = searchHits.getTotalHits();
            int totalPage = (int) Math.ceil((double) totalHits / pageSize);

            return new PaginationResultVO<>((int)totalHits,pageSize,pageNo + 1,totalPage,productInfoDTO);
        }catch (Exception e) {
            log.error("搜索商品失败，异常类型={}", e.getClass().getSimpleName());
            return new PaginationResultVO<>(0,PageSize.SIZE15.getSize(),pageNo != null ? pageNo : 1,0,new ArrayList<>());
        }
    }

    /** 仅返回 MySQL 当前可售且版本已发布的商品。索引更新期间，本页可能暂时不足一页。 */
    private List<ProductInfoDTO> visibleProducts(List<ProductInfoDTO> hits) {
        if (hits.isEmpty()) {
            return hits;
        }
        List<String> keys = hits.stream().map(ProductInfoDTO::getProductId).filter(java.util.Objects::nonNull).distinct().toList();
        if (keys.isEmpty()) {
            return List.of();
        }
        ProductInfoQuery active = new ProductInfoQuery();
        active.setProductIdList(keys);
        active.setStatus(ProductStatusEnum.ON_SALE.getStatus());
        Set<String> visible = productInfoMapper.selectList(active).stream()
                .map(ProductInfo::getProductId).collect(Collectors.toSet());
        Map<String, SearchSyncState> states = searchSyncService.states(SearchSyncService.PRODUCT_SEARCH, keys);
        return hits.stream().filter(hit -> {
            SearchSyncState state = states.get(hit.getProductId());
            // 兼容未迁移的在售旧文档。进入版本化同步后，只放行当前已完整发布的新文档。
            return visible.contains(hit.getProductId()) && (state == null ? hit.getSyncVersion() == null
                    : hit.getSyncVersion() != null && hit.getSyncVersion() == state.requestedVersion()
                    && state.requestedVersion() == state.publishedVersion());
        }).toList();
    }
}
