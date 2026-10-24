package com.easymall.component;

import com.easymall.commonent.RedisComponent;
import com.easymall.entity.constants.Constants;
import com.easymall.entity.dto.MessageSendDTO;
import com.easymall.entity.enums.*;
import com.easymall.entity.po.AgentMessage;
import com.easymall.entity.po.OrderInfo;
import com.easymall.entity.po.ProductInfo;
import com.easymall.entity.query.AgentMessageQuery;
import com.easymall.entity.query.OrderInfoQuery;
import com.easymall.entity.query.ProductInfoQuery;
import com.easymall.entity.query.SimplePage;
import com.easymall.service.AgentMessageService;
import com.easymall.service.OrderInfoService;
import com.easymall.service.ProductInfoService;
import com.easymall.utils.DateUtil;
import com.easymall.utils.JsonUtils;
import com.easymall.utils.StringTools;
import com.easymall.websocket.ChannelContextUtils;
import com.easymall.websocket.message.MessageHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 消息处理
 */
@RestController
@Slf4j
public class ChatComponent {
    @Resource
    private AgentMessageService agentMessageService;
    @Resource
    private MessageHandler messageHandler;
    @Resource
    private ChatClient chatClient;
    @Resource
    private VectorStore vectorStore;
    @Resource
    private ProductInfoService productInfoService;
    @Resource
    private OrderInfoService orderInfoService;
    @Resource
    private ToolCallbackProvider toolCallbackProvider;
    @Resource
    private RedisComponent redisComponent;

    /**
     * 发送消息
     * @param userId
     * @param message
     * @return
     */
    public AgentMessage sendMessage(String userId,String message) {
        AgentMessage agentMessage = agentMessageService.saveMessage(userId, message);

        ExecutorServiceSignletionEnum.INSTANCE.getExecutorService().execute(() -> {
            assistantAnswer(agentMessage);
        });

        return agentMessage;
    }

    /**
     * AI回复
     * @param agentMessage 消息
     */
    public void assistantAnswer(AgentMessage agentMessage) {
        //AI判断意图
        UserIntent userIntent = analyzeUserIntent(agentMessage.getUserId(), agentMessage.getUserMessage());
        //获取到AI判断的意图类型
        PromptTypeEnum promptTypeEnum = PromptTypeEnum.getByCode(userIntent.intent);
        promptTypeEnum = promptTypeEnum == null ? PromptTypeEnum.CHAT : promptTypeEnum;

        switch (promptTypeEnum) {
            case PRODUCT_SEARCH :
                //商品搜索意图
                searchProduct(agentMessage,userIntent.data);
                agentMessageService.completeMessage(agentMessage.getMessageId(), agentMessage.getBizType(), agentMessage.getAssistantMessage(), agentMessage.getBizData());
                break;
            case QUERY_ORDER :
                //查询订单意图
                queryOrder(agentMessage,userIntent.data);
                agentMessageService.completeMessage(agentMessage.getMessageId(), agentMessage.getBizType(), agentMessage.getAssistantMessage(), agentMessage.getBizData());
                break;
            default:
                chat(promptTypeEnum,agentMessage);
        }
    }

    /**
     * 从向量数据库当中获取商品
     */
    private void searchProduct(AgentMessage agentMessage,String keyword) {
        keyword = StringTools.isEmpty(keyword) ? agentMessage.getUserMessage() : keyword;
        SearchRequest request = SearchRequest.builder()
                .query(agentMessage.getUserMessage())
                .filterExpression(buildTypeFilter(RagDataTypeEnum.PRODUCT.getType())).
                similarityThreshold(0.45).topK(30).
                build();

        //查询向量数据库当中所有的商品信息
        List<Document> doc = vectorStore.similaritySearch(request);
        String prompt = getPrompt(PromptTypeEnum.PRODUCT_SEARCH, JsonUtils.convertObj2Json(doc), keyword,null);
        //AI根据提示的关键词去检索查询到的商品信息中更符合的商品
        List<ProductInfo> productInfos = getChatClientRequestSpec().user(prompt).call().entity(new ParameterizedTypeReference<>(){
        });
        //根据符合条件的商品id查询商品信息
        List<String> productIds = productInfos.stream().map(ProductInfo::getProductId).collect(Collectors.toList());
        List<ProductInfo> productInfoList = new ArrayList<>();
        if (!productInfos.isEmpty()){
            ProductInfoQuery productInfoQuery = new ProductInfoQuery();
            productInfoQuery.setProductIdList(productIds);
            productInfoQuery.setOrderBy("create_time desc");
            productInfoList = productInfoService.findListByParam(productInfoQuery);
        }
        //把查询到的商品信息转化为JSON字符串，设置到消息当中
        String assistantData = JsonUtils.convertObj2Json(productInfoList);
        agentMessage.setBizType(UserIntentEnum.PRODUCT_SEARCH.getKey());
        agentMessage.setBizData(productIds.isEmpty() ? null : JsonUtils.convertObj2Json(productIds));
        agentMessage.setAssistantMessage(assistantData);

        MessageSendDTO messageSendDTO = new MessageSendDTO();
        messageSendDTO.setUserId(agentMessage.getUserId());
        messageSendDTO.setOutPutType(MessageOutPutTypeEnum.DONE.getType());
        messageSendDTO.setAssistantMessage(assistantData);
        messageSendDTO.setBizType(UserIntentEnum.PRODUCT_SEARCH.getKey());
        messageSendDTO.setMessageId(agentMessage.getMessageId());
        messageSendDTO.setUserMessage(agentMessage.getUserMessage());

        messageHandler.sendMessage(messageSendDTO);
    }

    /**
     * AI查询订单
     * @param message 用户消息
     * @param orderId 订单Id
     */
    private void queryOrder(AgentMessage message,String orderId){
        //默认查询15天内的订单
        OrderInfoQuery orderInfoQuery = new OrderInfoQuery();
        orderInfoQuery.setOrderTimeStart(DateUtil.getBeforeDay(Constants.LATEST_ORDER_DAYS,DateTimePatternEnum.YYYY_MM_DD.getPattern()));
        orderInfoQuery.setOrderTimeEnd(DateUtil.format(new Date(), DateTimePatternEnum.YYYY_MM_DD.getPattern()));
        orderInfoQuery.setOrderId(orderId);
        orderInfoQuery.setUserId(message.getUserId());
        orderInfoQuery.setOrderBy("o.order_time desc");
        orderInfoQuery.setQueryItems(true);
        orderInfoQuery.setExecuteOrderStatusList(new Integer[]{OrderStatusEnum.DELETE.getStatus()});
        List<OrderInfo> orderInfoList = orderInfoService.findListByParam(orderInfoQuery);

        //将订单信息转化为JSON字符串
        String assistantData = JsonUtils.convertObj2Json(orderInfoList);
        message.setBizType(UserIntentEnum.QUERY_ORDER.getKey());
        message.setAssistantMessage(assistantData);

        List<String> orderIdList = orderInfoList.stream().map(OrderInfo::getOrderId).toList();
        message.setBizData(orderIdList.isEmpty() ? null : JsonUtils.convertObj2Json(orderIdList));

        //构建消息设置给DTO，最后给WebSocket发送消息
        MessageSendDTO messageSendDTO = new MessageSendDTO();
        messageSendDTO.setUserId(message.getUserId());
        messageSendDTO.setOutPutType(MessageOutPutTypeEnum.DONE.getType());
        messageSendDTO.setAssistantMessage(assistantData);
        messageSendDTO.setBizType(UserIntentEnum.QUERY_ORDER.getKey());
        messageSendDTO.setMessageId(message.getMessageId());
        messageSendDTO.setUserMessage(message.getUserMessage());

        messageHandler.sendMessage(messageSendDTO);
    }

    /**
     * 过滤元数据
      * @param dataType 数据类型
     */
    private String buildTypeFilter(String dataType){
        return String.format("dataType=='%s'", dataType);
    }

    /**
     * AI回答关于除了商品查找和订单查询之外的问题
     * @param promptTypeEnum 提示词类型
     * @param agentMessage 消息
     */
    public void chat(PromptTypeEnum promptTypeEnum, AgentMessage agentMessage) {
        List<String> chatMessage = new ArrayList<>();
        MessageSendDTO messageSendDTO = new MessageSendDTO();
        messageSendDTO.setUserId(agentMessage.getUserId());
        messageSendDTO.setMessageId(agentMessage.getMessageId());
        try {
            String ragData = "";
            if (promptTypeEnum == PromptTypeEnum.CHAT) {
                //获取本地知识库的内容
                SearchRequest request = SearchRequest.builder().
                        query(agentMessage.getUserMessage()).filterExpression(buildTypeFilter(RagDataTypeEnum.FAQ.getType())).
                        similarityThreshold(0.5).topK(15).
                        build();
                //查询向量数据中的内容
                List<Document> documents = vectorStore.similaritySearch(request);
                documents = documents.stream().distinct().collect(Collectors.toList());

                //获取到向量数据库中的片段，并用两个换行符进行连接
                //如果只用一个\n，当文档片段本身内部就有换行时，大模型可能分不清哪里是上一个片段的结尾，哪里是下一个片段的开头
                ragData = documents.stream().map(document -> document.getText()).collect(Collectors.joining("\n\n"));

            }
            String prompt = getPrompt(promptTypeEnum, agentMessage.getUserId(), agentMessage.getUserMessage(), ragData);
            getChatClientRequestSpec().toolCallbacks(toolCallbackProvider).messages(getHistoryMessages(agentMessage.getUserId(), prompt)).stream().chatResponse().doOnNext(response -> {
                log.info("流式分片：results数量={}，metadata={}", response.getResults().size(), response.getMetadata());
                if (response.getResults().isEmpty()) {
                    return; // 跳过没有正文结果的分片
                }
                String responseMessage = response.getResults().get(0).getOutput().getText();
                log.info("大模型返回消息 {}", responseMessage);
                if (!StringTools.isEmpty(responseMessage)) {
                    messageSendDTO.setOutPutType(MessageOutPutTypeEnum.OUTPUTTING.getType());
                    messageSendDTO.setAssistantMessage(responseMessage);

                    messageHandler.sendMessage(messageSendDTO);

                    chatMessage.add(responseMessage);
                }

            }).doOnComplete(()->{
                messageSendDTO.setOutPutType(MessageOutPutTypeEnum.DONE.getType());
                messageHandler.sendMessage(messageSendDTO);

                agentMessage.setAssistantMessage(chatMessage.stream().collect(Collectors.joining("")));
                agentMessageService.completeMessage(agentMessage.getMessageId(), PromptTypeEnum.CHAT.getKey(), agentMessage.getAssistantMessage(), null);
            }).doOnError(error -> {
                messageSendDTO.setOutPutType(MessageOutPutTypeEnum.ERROR.getType());
                messageSendDTO.setAssistantMessage(ResponseCodeEnum.CODE_500.getMsg());
                messageHandler.sendMessage(messageSendDTO);

                agentMessage.setAssistantMessage(chatMessage.stream().collect(Collectors.joining("")));
                agentMessageService.completeMessage(agentMessage.getMessageId(), PromptTypeEnum.CHAT.getKey(), agentMessage.getAssistantMessage(), null);
            }).subscribe();

        }catch (Exception e){
            log.info("调用大模型失败",e);
            messageSendDTO.setOutPutType(MessageOutPutTypeEnum.ERROR.getType());
            messageSendDTO.setMessageId(agentMessage.getMessageId());
            messageSendDTO.setAssistantMessage(ResponseCodeEnum.CODE_500.getMsg());
            messageHandler.sendMessage(messageSendDTO);

            agentMessage.setAssistantMessage(ResponseCodeEnum.CODE_500.getMsg());
            agentMessageService.completeMessage(agentMessage.getMessageId(), PromptTypeEnum.CHAT.getKey(), agentMessage.getAssistantMessage(), null);
        }

    }

    /**
     * 获取提示词
      * @param typeEnum 提示词类型
     * @param userId 用户ID
     * @param message 用户消息
     * @param ragData 联想数据
     * @return 提示词
     */
    private String getPrompt (PromptTypeEnum typeEnum,String userId,String message,String ragData) {
        //从Redis中获取提示词,因为提示词可能会被更新，更新的提示词会保存在Redis中
        String prompt = redisComponent.getPrompt(typeEnum.getKey());
        if (StringTools.isEmpty(prompt)) {
            prompt = typeEnum.getPrompt();
        }
        //根据提示词类型进行处理
        if (PromptTypeEnum.CHAT == typeEnum) {
            //若为聊天类型，则需要查询知识库将ragData进行处理
            ragData = StringTools.isEmpty(ragData) ? "知识库当中暂无相关内容" : ragData;
            prompt = String.format(prompt, ragData,userId,message);
            return prompt;
        } else if (PromptTypeEnum.GLOBAL != typeEnum) {
            //若为其他类型，则直接返回提示词
            return String.format(prompt, userId, message);
        }
        //以上所有类型都不满足，则为全局类型，直接返回提示词
        return prompt;
    }


    /**
     * 定义AI全局人设
     * @return
     */
    private ChatClient.ChatClientRequestSpec getChatClientRequestSpec() {
        String systemPrompt = getPrompt(PromptTypeEnum.GLOBAL, null, null, null);
        return chatClient.prompt().system(systemPrompt).advisors(new LoggerAdvisor());
    }

    /**
     * 以当前的聊天消息为背景，暂时替代历史聊天消息，进行大模型调用
     * @param userId
     * @param currentMessage
     * @return
     */
    private List<Message> getHistoryMessages(String userId,String currentMessage) {
        AgentMessageQuery agentMessageQuery = new AgentMessageQuery();
        agentMessageQuery.setUserId(userId);
        agentMessageQuery.setOrderBy("message_id desc");
        agentMessageQuery.setSimplePage(new SimplePage(1,Constants.LENGTH_10));
        List<AgentMessage> agentMessageList = agentMessageService.findListByParam(agentMessageQuery);
        agentMessageList.sort(Comparator.comparingInt(AgentMessage::getMessageId));
        List<Message> historyList = new ArrayList<>();

        for (AgentMessage agentMessage : agentMessageList) {
            if (ArrayUtils.contains(new String[]{UserIntentEnum.PRODUCT_SEARCH.getKey(),UserIntentEnum.QUERY_ORDER.getKey()}, agentMessage.getBizType()) && agentMessage.getBizData() != null){
                historyList.add(new UserMessage(agentMessage.getUserMessage()));
                historyList.add(new AssistantMessage(agentMessage.getBizData()));
                continue;
            }
            if (!StringTools.isEmpty(agentMessage.getAssistantMessage())){
                historyList.add(new UserMessage(agentMessage.getUserMessage()));
                historyList.add(new AssistantMessage(agentMessage.getAssistantMessage()));
            }
        }

        historyList.add(new UserMessage(currentMessage));
        return historyList;
    }

    /**
     * 分析用户意图
     * @param userId
     * @param message
     * @return
     */
    private UserIntent analyzeUserIntent(String userId,String message) {
        String prompt = getPrompt(PromptTypeEnum.USER_INTENT, userId, message, null);
        //调用大模型进行分析，查看分析出用户的意图是PromptTypeEnum.USER_INTENT中的哪一个intent
        UserIntent userIntent = getChatClientRequestSpec().messages(getHistoryMessages(userId, prompt)).call().entity(UserIntent.class);

        return userIntent;
    }


    record UserIntent(String intent,String data){

    }
}
