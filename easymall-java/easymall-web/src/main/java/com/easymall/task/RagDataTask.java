package com.easymall.task;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.json.JsonData;
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
import com.easymall.mappers.RagQuestionMapper;
import com.easymall.entity.query.RagQuestionQuery;
import com.easymall.mappers.SearchSyncMapper;
import com.easymall.sync.SearchSyncService;
import com.easymall.sync.SearchSyncState;
import jakarta.annotation.Resource;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 商品和 FAQ 的向量同步处理器。搜索 Worker 调用写入方法，AI 导购调用读取过滤方法。
 * 向量文档按版本保存。同步中断或新旧任务交错时，导购仍只使用 MySQL 已发布的当前版本。
 */
@Component
public class RagDataTask {
    private static final int EMBEDDING_BATCH = 10;
    private static final String SYNC_VERSION = "syncVersion";

    @Lazy
    @Resource
    private VectorStore vectorStore;
    @Value("${spring.ai.vectorstore.elasticsearch.index-name:easymall-index-vectorstore}")
    private String vectorIndex;
    @Resource
    private SearchSyncService searchSyncService;
    @Resource
    private ProductInfoMapper<ProductInfo, ProductInfoQuery> productInfoMapper;
    @Resource
    private ProductPropertyValueMapper<ProductPropertyValue, ProductPropertyValueQuery> productPropertyValueMapper;
    @Resource
    private ProductSkuMapper<ProductSku, ProductSkuQuery> productSkuMapper;
    @Resource
    private RagQuestionMapper<RagQuestion, RagQuestionQuery> ragQuestionMapper;
    @Resource
    private SearchSyncMapper searchSyncMapper;

    /**
     * 上架商品生成商品和 SKU 向量，已下架或不存在的商品只清理旧文档。
     */
    public void syncProduct(TaskExecution task) {
        searchSyncService.synchronize(task, () -> {
            ProductInfo product = productInfoMapper.selectByProductId(task.bizKey());
            if (product != null && ProductStatusEnum.ON_SALE.getStatus().equals(product.getStatus())) {
                addBatches(task, productDocuments(product, task.bizVersion()));
            }
            removeOlder(task, RagDataTypeEnum.PRODUCT, "productId");
        });
    }

    /**
     * 将问题、相似问法和答案一起向量化。FAQ 被删除时，只保留清理步骤。
     */
    public void syncQuestion(TaskExecution task) {
        searchSyncService.synchronize(task, () -> {
            RagQuestion question = ragQuestionMapper.selectByQuestionId(Integer.valueOf(task.bizKey()));
            if (question != null) {
                String similar = question.getSimilarQuestion() == null ? "" : question.getSimilarQuestion();
                String text = question.getQuestion() + "\n相似问题：" + similar + "\n答案：" + question.getAnswer();
                addBatches(task, List.of(new Document(
                        "faq:" + task.bizKey() + ":" + task.bizVersion(), text,
                        Map.of("dataType", "faq", "questionId", task.bizKey(), SYNC_VERSION, task.bizVersion()))));
            }
            removeOlder(task, RagDataTypeEnum.FAQ, "questionId");
        });
    }

    /**
     * 把一件商品整理成 AI 导购可以检索的文本
     * 一条商品名称文档和每个 SKU 一条文档，让检索既能匹配品名，也能匹配颜色、尺码等属性。
     */
    private List<Document> productDocuments(ProductInfo product, long version) {
        String productId = product.getProductId();
        String name = product.getProductName();
        var properties = new ProductPropertyValueQuery();
        properties.setProductId(productId);
        Map<String, String> labels = productPropertyValueMapper.selectList(properties).stream()
                .collect(Collectors.toMap(ProductPropertyValue::getPropertyValueId,
                        p -> p.getPropertyName() + "：" + p.getPropertyValue(), (first, next) -> first));
        var skuQuery = new ProductSkuQuery();
        skuQuery.setProductId(productId);
        List<Document> documents = new ArrayList<>();
        // ID 包含商品编号、版本和 SKU 标识。同版本重试写同一 ID，新旧版本各自独立。
        String prefix = "product:" + productId + ":" + version;
        var meta = Map.<String, Object>of("dataType", "product", "productId", productId,
                "productName", name, SYNC_VERSION, version);
        documents.add(new Document(prefix, name, meta));
        for (ProductSku sku : productSkuMapper.selectList(skuQuery)) {
            var skuLabels = new ArrayList<String>();
            if (sku.getPropertyValueIds() != null && !sku.getPropertyValueIds().isBlank()) {
                for (String id : sku.getPropertyValueIds().split("-")) {
                    String label = labels.get(id);
                    if (label != null) {
                        skuLabels.add(label);
                    }
                }
            }
            var skuMeta = new java.util.HashMap<>(meta);
            skuMeta.put("skuList", skuLabels.stream().map(label -> Map.of("property", label)).toList());
            documents.add(new Document(prefix + ":" + sku.getPropertyValueIdHash(),
                    name + " " + String.join("，", skuLabels), skuMeta));
        }
        return documents;
    }

    private void addBatches(TaskExecution task, List<Document> documents) {
        // 一些向量模型每次最多嵌入 10 条。任一批失败就不发布整版，重试复用相同的文档 ID。
        for (int offset = 0; offset < documents.size(); offset += EMBEDDING_BATCH) {
            task.requireActiveLease();
            vectorStore.add(List.copyOf(documents.subList(offset, Math.min(offset + EMBEDDING_BATCH, documents.size()))));
        }
    }

    private void removeOlder(TaskExecution task, RagDataTypeEnum type, String keyField) {
        task.requireActiveLease();
        // 按类型、对象和版本精确清理。旧任务晚到时，其清理范围仍不会包含任何更新版本。
        // 直接检查 ES 的执行结果，避免把部分删除或超时当成一次完整同步。
        ElasticsearchClient client = vectorStore.<ElasticsearchClient>getNativeClient().orElseThrow();
        String versionField = "metadata." + SYNC_VERSION;
        Query missingVersion = Query.of(q -> q.bool(b -> b.mustNot(qry -> qry.exists(e -> e.field(versionField)))));
        Query olderVersion = Query.of(q -> q.range(r -> r.untyped(v -> v.field(versionField)
                .lt(JsonData.of(task.bizVersion())))));
        Query obsolete = Query.of(q -> q.bool(b -> b.minimumShouldMatch("1").should(olderVersion, missingVersion)));
        Query matchingObject = Query.of(q -> q.bool(b -> b
                .filter(item -> item.term(term -> term.field("metadata.dataType.keyword").value(type.getType())))
                .filter(item -> item.term(term -> term.field("metadata." + keyField + ".keyword").value(task.bizKey())))
                .filter(obsolete)));
        try {
            var result = client.deleteByQuery(request -> request.index(vectorIndex).refresh(true).query(matchingObject));
            if (Boolean.TRUE.equals(result.timedOut()) || !result.failures().isEmpty()) {
                throw new IllegalStateException("向量旧版本清理未完成");
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /**
     * 相似度查询只提供候选。进入提示词之前，要确认商品仍在售或 FAQ 仍存在，再检查文档版本。
     * 已有同步状态的对象仅接受 requestedVersion 与 publishedVersion 一致的版本。
     */
    public List<Document> visibleDocuments(List<Document> candidates, RagDataTypeEnum type) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        boolean products = type == RagDataTypeEnum.PRODUCT;
        String keyField = products ? "productId" : "questionId";
        List<String> keys = candidates.stream().map(doc -> key(doc, keyField))
                .filter(id -> id != null && (products || id.matches("[1-9][0-9]{0,9}")))
                .distinct().toList();
        if (keys.isEmpty()) {
            return List.of();
        }

        Set<String> current;
        if (products) {
            var query = new ProductInfoQuery();
            query.setProductIdList(keys);
            query.setStatus(ProductStatusEnum.ON_SALE.getStatus());
            current = productInfoMapper.selectList(query).stream().map(ProductInfo::getProductId).collect(Collectors.toSet());
        } else {
            current = Set.copyOf(searchSyncMapper.existingQuestions(keys));
        }
        String taskType = products ? SearchSyncService.PRODUCT_VECTOR : SearchSyncService.FAQ_VECTOR;
        Map<String, SearchSyncState> states = searchSyncService.states(taskType, keys);
        return candidates.stream().filter(doc -> {
            String objectKey = key(doc, keyField);
            if (objectKey == null || !current.contains(objectKey)
                    || !type.getType().equals(doc.getMetadata().get("dataType"))) {
                return false;
            }
            SearchSyncState state = states.get(objectKey);
            Object version = doc.getMetadata().get(SYNC_VERSION);
            // 首次切换时保留尚未重同步且业务仍可见的旧文档。有同步版本后只接受完整发布的新版本。
            return state == null ? version == null
                    : version instanceof Number number && number.longValue() == state.requestedVersion()
                    && state.publishedVersion() == state.requestedVersion();
        }).toList();
    }

    private static String key(Document doc, String field) {
        Object key = doc.getMetadata().get(field);
        return key instanceof String value && !value.isBlank() ? value : null;
    }
}
