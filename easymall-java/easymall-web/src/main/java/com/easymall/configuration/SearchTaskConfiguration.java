package com.easymall.configuration;

import com.easymall.commonent.EsSearchComponent;
import com.easymall.sync.SearchSyncService;
import com.easymall.task.BusinessTaskHandler;
import com.easymall.task.BusinessTaskProperties;
import com.easymall.task.BusinessTaskService;
import com.easymall.task.BusinessTaskWorker;
import com.easymall.task.RagDataTask;
import com.easymall.task.TaskExecution;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.function.Consumer;

/** 三种搜索同步由单独的有界 Worker 执行，Embedding 变慢时仍能继续处理订单。 */
@Configuration(proxyBeanMethods = false)
public class SearchTaskConfiguration {
    /** 向量库首次被用到时才初始化。ES 停机时，Web 仍能启动并恢复数据库里的订单任务。 */
    @Bean
    static BeanFactoryPostProcessor lazySearchVectorStore() {
        return factory -> {
            for (String name : factory.getBeanNamesForType(VectorStore.class, false, false)) {
                factory.getBeanDefinition(name).setLazyInit(true);
            }
        };
    }

    /** 把数据库中的三种任务类型分派给 ES 关键词索引和 RAG 向量处理器。 */
    @Bean
    public BusinessTaskWorker searchSyncWorker(BusinessTaskService tasks, BusinessTaskProperties options,
                                               SearchSyncService sync, EsSearchComponent search, RagDataTask vectors) {
        return new BusinessTaskWorker(tasks, options, List.of(
                new Handler(SearchSyncService.PRODUCT_SEARCH,
                        task -> sync.synchronize(task, () -> search.writeProductVersion(task.bizKey(), task.bizVersion()))),
                new Handler(SearchSyncService.PRODUCT_VECTOR, vectors::syncProduct),
                new Handler(SearchSyncService.FAQ_VECTOR, vectors::syncQuestion)));
    }

    /** 将任务类型与实际处理方法关联，领取、续租和重试由公共 Worker 负责。 */
    private record Handler(String taskType, Consumer<TaskExecution> delegate) implements BusinessTaskHandler {
        @Override
        public void handle(TaskExecution task) {
            delegate.accept(task);
        }
    }
}
