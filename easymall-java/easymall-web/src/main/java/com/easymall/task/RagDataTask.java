package com.easymall.task;

import com.easymall.entity.constants.Constants;
import com.easymall.entity.dto.RagDataDTO;
import com.easymall.entity.enums.ExecutorServiceSignletionEnum;
import com.easymall.entity.enums.RagDataTypeEnum;
import com.easymall.entity.po.RagQuestion;
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

@Component
@Slf4j
public class RagDataTask {
    @Resource
    private RedissonClient redissonClient;

    @Resource
    private RagQuestionService ragQuestionService;

    @Resource
    private VectorStore vectorStore;

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
