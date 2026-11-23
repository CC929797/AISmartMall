package com.easymall.component;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.easymall.commonent.EsSearchComponent;
import com.easymall.entity.enums.ProductStatusEnum;
import com.easymall.entity.enums.RagDataTypeEnum;
import com.easymall.entity.po.ProductInfo;
import com.easymall.entity.query.ProductInfoQuery;
import com.easymall.entity.vo.ProductInfoDTO;
import com.easymall.mappers.ProductInfoMapper;
import com.easymall.mappers.RagQuestionMapper;
import com.easymall.mappers.SearchSyncMapper;
import com.easymall.sync.SearchSyncService;
import com.easymall.sync.SearchSyncState;
import com.easymall.task.RagDataTask;
import com.easymall.task.TaskExecution;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchConfiguration;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchClients;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 仅用于测试。固定连接隔离 ES 端口，用真实 ES 验证写入、版本保护和向量清理，不调用真实 Embedding。 */
@EnabledIfSystemProperty(named = "search.es.integration", matches = "true")
class SearchProjectionEsIT {
    private static RestClient transport;
    private static ElasticsearchClient client;

    @BeforeAll
    static void connectIsolatedElasticsearch() throws IOException {
        transport = RestClient.builder(new HttpHost("127.0.0.1", 19200)).build();
        client = ElasticsearchClients.createImperative(transport);
        assertThat(client.info().clusterName()).isEqualTo("easymall-baseline");
    }

    @AfterAll
    static void closeTransport() throws IOException {
        if (transport != null) {
            transport.close();
        }
    }

    @Test
    void olderWritesCannotOverwriteUpdatedOrOffSaleProduct() {
        String key = "IT11B-" + UUID.randomUUID();
        var configuration = new ElasticsearchConfiguration() {
            @Override
            public ClientConfiguration clientConfiguration() {
                return ClientConfiguration.create("127.0.0.1:19200");
            }
        };
        var conversions = configuration.elasticsearchCustomConversions();
        var mapping = configuration.elasticsearchMappingContext(conversions);
        var converter = configuration.elasticsearchEntityMapper(mapping, conversions);
        var operations = new ElasticsearchTemplate(client, converter);
        var search = new EsSearchComponent();
        @SuppressWarnings("unchecked")
        ProductInfoMapper<ProductInfo, ProductInfoQuery> products = mock(ProductInfoMapper.class);
        var sync = mock(SearchSyncService.class);
        ReflectionTestUtils.setField(search, "elasticsearchOperations", operations);
        ReflectionTestUtils.setField(search, "productInfoMapper", products);
        ReflectionTestUtils.setField(search, "searchSyncService", sync);
        ProductInfo product = new ProductInfo();
        product.setProductId(key);
        product.setProductName("migrationtest");
        product.setStatus(ProductStatusEnum.ON_SALE.getStatus());
        product.setMinPrice(new BigDecimal("10.00"));
        when(products.selectByProductId(key)).thenReturn(product);
        when(products.selectList(any())).thenReturn(List.of(product));
        when(sync.states(SearchSyncService.PRODUCT_SEARCH, List.of(key)))
                .thenReturn(Map.of(key, new SearchSyncState(key, 2, 2)));

        search.writeProductVersion(key, 1);
        product.setProductName("projectiontest");
        search.writeProductVersion(key, 2);
        product.setProductName("lateoldwrite");
        search.writeProductVersion(key, 1);

        var stored = operations.get(key, ProductInfoDTO.class);
        assertThat(stored.getProductName()).isEqualTo("projectiontest");
        assertThat(stored.getSyncVersion()).isEqualTo(2);
        operations.indexOps(ProductInfoDTO.class).refresh();
        assertThat(search.searchProduct("projectiontest", null, null, null, null, 1).getList())
                .extracting(ProductInfoDTO::getProductId).contains(key);

        when(products.selectByProductId(key)).thenReturn(null);
        search.writeProductVersion(key, 3);
        when(products.selectByProductId(key)).thenReturn(product);
        search.writeProductVersion(key, 2);
        stored = operations.get(key, ProductInfoDTO.class);
        assertThat(stored.getProductName()).isNull();
        assertThat(stored.getSyncVersion()).isEqualTo(3);
    }

    @Test
    void vectorCleanupDeletesOnlyOlderDocumentsForTheSameObject() throws IOException {
        String index = "it11b-vector-" + UUID.randomUUID();
        client.indices().create(request -> request.index(index));
        put(index, "legacy", "42", null);
        put(index, "old", "42", 1L);
        put(index, "current", "42", 2L);
        put(index, "future", "42", 3L);
        put(index, "other", "142", 1L);
        client.indices().refresh(request -> request.index(index));

        var vectors = new RagDataTask();
        var store = mock(VectorStore.class);
        var sync = mock(SearchSyncService.class);
        var questions = mock(RagQuestionMapper.class);
        var mapper = mock(SearchSyncMapper.class);
        ReflectionTestUtils.setField(vectors, "vectorStore", store);
        ReflectionTestUtils.setField(vectors, "vectorIndex", index);
        ReflectionTestUtils.setField(vectors, "searchSyncService", sync);
        ReflectionTestUtils.setField(vectors, "ragQuestionMapper", questions);
        ReflectionTestUtils.setField(vectors, "searchSyncMapper", mapper);
        when(store.getNativeClient()).thenReturn(Optional.of(client));
        when(mapper.existingQuestions(any())).thenReturn(List.of("42"));
        when(sync.states(SearchSyncService.FAQ_VECTOR, List.of("42", "142")))
                .thenReturn(Map.of("42", new SearchSyncState("42", 2, 2)));
        doAnswer(call -> {
            call.getArgument(1, Runnable.class).run();
            return null;
        }).when(sync).synchronize(any(), any());

        vectors.syncQuestion(new TaskExecution("synthetic", SearchSyncService.FAQ_VECTOR, "42", 2,
                "{}", 1, () -> true));
        assertThat(client.search(request -> request.index(index), Object.class).hits().hits())
                .extracting(hit -> hit.id()).containsExactlyInAnyOrder("current", "future", "other");

        var candidates = List.of(vector("legacy", "42", null), vector("old", "42", 1L),
                vector("current", "42", 2L), vector("future", "42", 3L), vector("other", "142", 1L));
        assertThat(vectors.visibleDocuments(candidates, RagDataTypeEnum.FAQ))
                .extracting(Document::getId).containsExactly("current");
    }

    private void put(String index, String id, String question, Long version) throws IOException {
        client.index(request -> request.index(index).id(id).document(Map.of("metadata", metadata(question, version))));
    }

    private Document vector(String id, String question, Long version) {
        return new Document(id, "Synthetic FAQ", metadata(question, version));
    }

    private Map<String, Object> metadata(String question, Long version) {
        return version == null ? Map.of("dataType", "faq", "questionId", question)
                : Map.of("dataType", "faq", "questionId", question, "syncVersion", version);
    }
}

