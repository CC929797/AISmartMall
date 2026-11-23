package com.easymall.component;

import com.easymall.commonent.RedisComponent;
import com.easymall.entity.constants.Constants;
import com.easymall.entity.dto.MessageSendDTO;
import com.easymall.entity.dto.OrderToolTarget;
import com.easymall.entity.dto.ToolResult;
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
import com.easymall.task.RagDataTask;
import com.easymall.utils.DateUtil;
import com.easymall.utils.JsonUtils;
import com.easymall.utils.StringTools;
import com.easymall.security.CurrentUser;
import com.easymall.validation.AiCommentInputs;
import com.easymall.websocket.message.MessageHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
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
    @Lazy
    @Resource
    private VectorStore vectorStore;
    @Resource
    private RagDataTask ragDataTask;
    @Resource
    private ProductInfoService productInfoService;
    @Resource
    private OrderInfoService orderInfoService;
    @Resource
    private RedisComponent redisComponent;
    @org.springframework.beans.factory.annotation.Value("${security.logging.model-summary:false}")
    private boolean modelLogSummary;

    /**
     * 发送消息
     * @param currentUser 启动异步任务前获取的不可变认证身份快照
     * @param message
     * @return
     */
    public AgentMessage sendMessage(CurrentUser currentUser,String message) {
        if (currentUser == null || currentUser.role() != CurrentUser.Role.USER || StringTools.isEmpty(currentUser.userId())) {
            throw new com.easymall.exception.BusinessException(ResponseCodeEnum.CODE_901);
        }
        // 异步任务提交前完成校验，非法输入不能写消息或启动模型调用。
        AiCommentInputs.requireMessage(currentUser.userId(), message);
        AgentMessage agentMessage = agentMessageService.saveMessage(currentUser.userId(), message);

        ExecutorServiceSignletionEnum.INSTANCE.getExecutorService().execute(() -> {
            // 异步任务显式携带可信身份快照，不从请求或 ThreadLocal 中读取当前用户。
            agentMessage.setUserId(currentUser.userId());
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
        String intent = userIntent == null || userIntent.intent() == null ? "" : userIntent.intent().strip().toUpperCase(Locale.ROOT);
        PromptTypeEnum promptTypeEnum = PromptTypeEnum.getByCode(intent);
        promptTypeEnum = promptTypeEnum == null ? PromptTypeEnum.CHAT : promptTypeEnum;

        if (redisComponent.hasCancelMessage(agentMessage.getUserId(), agentMessage.getMessageId())) {
            return;
        }

        switch (promptTypeEnum) {
            case PRODUCT_SEARCH :
                //商品搜索意图
                searchProduct(agentMessage, userIntent.data());
                agentMessageService.completeMessage(agentMessage.getMessageId(), agentMessage.getBizType(), agentMessage.getAssistantMessage(), agentMessage.getBizData());
                break;
            case QUERY_ORDER :
                //若意图为订单查询
                //查询订单意图
                OrderToolTarget target = userIntent.target();
                if (target.invalidOrderLookup()) {
                    sendGuidance(agentMessage, ToolResult.rejected("INVALID_ARGUMENT",
                            "请输入主订单号，或前往 [我的订单](/my/orders) 查询。"));
                    break;
                }
                //查询订单
                queryOrder(agentMessage, target.orderId());
                //完成消息,返回bizData
                agentMessageService.completeMessage(agentMessage.getMessageId(), agentMessage.getBizType(), agentMessage.getAssistantMessage(), agentMessage.getBizData());
                break;
            case REFUND, CANCEL_ORDER, CONFIRM_RECEIPT, PRODUCT_REVIEW, QUERY_LOGISTICS:
                //若意图为订单操作
                sendGuidance(agentMessage, orderGuidance(promptTypeEnum, userIntent.target()));
                break;
            default:
                //默认聊天
                chat(agentMessage);
        }
    }

    /**
     * 订单操作指导
     * @param intent 意图
     * @param target 订单目标
     * @return 操作结果
     */
    private ToolResult<Void> orderGuidance(PromptTypeEnum intent, OrderToolTarget target) {
        if (intent == PromptTypeEnum.REFUND && target.invalidRefundTarget()) {
            return ToolResult.rejected("INVALID_ARGUMENT",
                    "退款需要选择具体商品明细。请前往 [我的订单](/my/orders)，找到对应商品后点击「退款」。");
        }
        //目前先暂停订单操作功能
        //TODO: 后续再补充并完善订单操作
        String action = switch (intent) {
            case REFUND -> "退款";
            case CANCEL_ORDER -> "取消订单";
            case CONFIRM_RECEIPT -> "确认收货";
            case PRODUCT_REVIEW -> "评价";
            case QUERY_LOGISTICS -> "查看物流";
            default -> throw new IllegalArgumentException("未定义的订单操作");
        };
        return ToolResult.rejected("ACTION_UNAVAILABLE",
                "请前往 [我的订单](/my/orders)，找到对应订单后点击「" + action + "」。");
    }

    /**
     * 系统引导复用聊天完成消息，历史记录同时保留可供程序判断的结果码。
     */
    private void sendGuidance(AgentMessage message, ToolResult<Void> result) {
        String bizType = UserIntentEnum.CHAT.getKey();
        message.setBizType(bizType);
        message.setAssistantMessage(result.userMessage());
        message.setBizData(JsonUtils.convertObj2Json(result));
        agentMessageService.completeMessage(message.getMessageId(), bizType, result.userMessage(), message.getBizData());

        MessageSendDTO reply = new MessageSendDTO();
        reply.setMessageId(message.getMessageId());
        reply.setUserId(message.getUserId());
        reply.setUserMessage(message.getUserMessage());
        reply.setBizType(bizType);
        reply.setAssistantMessage(result.userMessage());
        reply.setOutPutType(MessageOutPutTypeEnum.DONE.getType());
        messageHandler.sendMessage(reply);
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

        // 检索相似商品，并先过滤已下架或向量版本过期的文档，再把候选交给模型。
        List<Document> doc = ragDataTask.visibleDocuments(vectorStore.similaritySearch(request), RagDataTypeEnum.PRODUCT);
        java.util.Set<Object> allowedProducts = doc.stream().map(item -> item.getMetadata().get("productId"))
                .collect(Collectors.toSet());
        String prompt = String.format(getPromptTemplate(PromptTypeEnum.PRODUCT_SEARCH), JsonUtils.convertObj2Json(doc), keyword);
        //AI根据提示的关键词去检索查询到的商品信息中更符合的商品
        List<ProductInfo> productInfos = getChatClientRequestSpec().user(prompt).call().entity(new ParameterizedTypeReference<>() {
        });
        // 模型给出的商品 ID 必须在本次候选里，回查 MySQL 时再确认商品仍处于上架状态。
        List<String> productIds = productInfos.stream().map(ProductInfo::getProductId)
                .filter(allowedProducts::contains).distinct().toList();
        List<ProductInfo> productInfoList = new ArrayList<>();
        if (!productIds.isEmpty()) {
            ProductInfoQuery productInfoQuery = new ProductInfoQuery();
            productInfoQuery.setProductIdList(productIds);
            productInfoQuery.setStatus(com.easymall.entity.enums.ProductStatusEnum.ON_SALE.getStatus());
            productInfoQuery.setOrderBy("create_time desc");
            productInfoList = productInfoService.findListByParam(productInfoQuery);
        }
        //把查询到的商品信息转化为JSON字符串，设置到消息当中
        String assistantData = JsonUtils.convertObj2Json(productInfoList);
        agentMessage.setBizType(UserIntentEnum.PRODUCT_SEARCH.getKey());
        agentMessage.setBizData(productInfoList.isEmpty() ? null
                : JsonUtils.convertObj2Json(productInfoList.stream().map(ProductInfo::getProductId).toList()));
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
    private void queryOrder(AgentMessage message,String orderId) {
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
    private String buildTypeFilter(String dataType) {
        return String.format("dataType=='%s'", dataType);
    }

    /**
     * AI回答普通聊天
     * @param agentMessage 消息
     */
    private void chat(AgentMessage agentMessage) {
        List<String> chatMessage = new ArrayList<>();
        MessageSendDTO messageSendDTO = new MessageSendDTO();
        messageSendDTO.setUserId(agentMessage.getUserId());
        messageSendDTO.setMessageId(agentMessage.getMessageId());
        try {
            SearchRequest request = SearchRequest.builder()
                    .query(agentMessage.getUserMessage()).filterExpression(buildTypeFilter(RagDataTypeEnum.FAQ.getType()))
                    .similarityThreshold(0.5).topK(15).build();
            // FAQ 从向量库命中后，先核对当前版本和业务记录，再拼接给模型参考。
            String ragData = ragDataTask.visibleDocuments(vectorStore.similaritySearch(request), RagDataTypeEnum.FAQ)
                    .stream().map(Document::getText).distinct().collect(Collectors.joining("\n\n"));
            if (StringTools.isEmpty(ragData)) {
                ragData = "知识库当中暂无相关内容";
            }
            String prompt = String.format(getPromptTemplate(PromptTypeEnum.CHAT), ragData, agentMessage.getUserMessage());
            getChatClientRequestSpec().messages(getHistoryMessages(agentMessage.getUserId(), prompt)).stream().chatResponse().doOnNext(response -> {
                // 分片直接投递，不把模型元数据写入日志。
                if (response.getResults().isEmpty()) {
                    return; // 跳过没有正文结果的分片
                }
                String responseMessage = response.getResults().get(0).getOutput().getText();
                // 回复正文只发给对应用户，不输出到服务器日志。
                if (!StringTools.isEmpty(responseMessage)) {
                    messageSendDTO.setOutPutType(MessageOutPutTypeEnum.OUTPUTTING.getType());
                    messageSendDTO.setAssistantMessage(responseMessage);

                    messageHandler.sendMessage(messageSendDTO);

                    chatMessage.add(responseMessage);
                }

            }).doOnComplete(() -> {
                if (chatMessage.isEmpty()) {
                    messageSendDTO.setBizType(UserIntentEnum.CHAT.getKey());
                    messageSendDTO.setAssistantMessage("暂时无法回答这个问题，请换一种说法再试。");
                    chatMessage.add(messageSendDTO.getAssistantMessage());
                }
                messageSendDTO.setOutPutType(MessageOutPutTypeEnum.DONE.getType());
                messageHandler.sendMessage(messageSendDTO);

                agentMessage.setAssistantMessage(chatMessage.stream().collect(Collectors.joining("")));
                agentMessageService.completeMessage(agentMessage.getMessageId(), UserIntentEnum.CHAT.getKey(), agentMessage.getAssistantMessage(), null);
            }).doOnError(error -> {
                messageSendDTO.setOutPutType(MessageOutPutTypeEnum.ERROR.getType());
                messageSendDTO.setAssistantMessage(ResponseCodeEnum.CODE_500.getMsg());
                messageHandler.sendMessage(messageSendDTO);

                agentMessage.setAssistantMessage(chatMessage.stream().collect(Collectors.joining("")));
                agentMessageService.completeMessage(agentMessage.getMessageId(), UserIntentEnum.CHAT.getKey(), agentMessage.getAssistantMessage(), null);
            }).subscribe();

        }catch (Exception e) {
            log.warn("调用大模型失败，异常类型={}", e.getClass().getSimpleName());
            messageSendDTO.setOutPutType(MessageOutPutTypeEnum.ERROR.getType());
            messageSendDTO.setMessageId(agentMessage.getMessageId());
            messageSendDTO.setAssistantMessage(ResponseCodeEnum.CODE_500.getMsg());
            messageHandler.sendMessage(messageSendDTO);

            agentMessage.setAssistantMessage(ResponseCodeEnum.CODE_500.getMsg());
            agentMessageService.completeMessage(agentMessage.getMessageId(), UserIntentEnum.CHAT.getKey(), agentMessage.getAssistantMessage(), null);
        }

    }

    /**
     * 获取提示词
      * @param typeEnum 提示词类型
     * @return 提示词
     */
    private String getPromptTemplate(PromptTypeEnum typeEnum) {
        String prompt = redisComponent.getPrompt(typeEnum.getKey());
        return StringTools.isEmpty(prompt) ? typeEnum.getPrompt() : prompt;
    }


    /**
     * 定义AI全局人设
     * @return
     */
    private ChatClient.ChatClientRequestSpec getChatClientRequestSpec() {
        String systemPrompt = getPromptTemplate(PromptTypeEnum.GLOBAL)
                + "\n聊天只提供查询与操作指引。涉及退款、取消订单、确认收货、评价和物流时，"
                + "请引导用户到我的订单页面。只能陈述已获得的查询结果。";
        return chatClient.prompt().system(systemPrompt).advisors(new LoggerAdvisor(modelLogSummary));
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
            if (ArrayUtils.contains(new String[]{UserIntentEnum.PRODUCT_SEARCH.getKey(),UserIntentEnum.QUERY_ORDER.getKey()}, agentMessage.getBizType()) && agentMessage.getBizData() != null) {
                historyList.add(new UserMessage(agentMessage.getUserMessage()));
                historyList.add(new AssistantMessage(agentMessage.getBizData()));
                continue;
            }
            if (!StringTools.isEmpty(agentMessage.getAssistantMessage())) {
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
        String prompt = String.format(getPromptTemplate(PromptTypeEnum.USER_INTENT), message);
        //调用大模型进行分析，查看分析出用户的意图是PromptTypeEnum.USER_INTENT中的哪一个intent
        UserIntent userIntent = getChatClientRequestSpec().messages(getHistoryMessages(userId, prompt)).call().entity(UserIntent.class);

        return userIntent;
    }

    /**
     * 用户意图类
     * @param intent 意图
     * @param data AI根据发送的消息生成的关键词
     * @param orderId 主订单ID
     * @param orderItemId 订单中某件商品的明细号
     * @param payOrderId 支付单号，一笔支付可能关联多个主订单
     */
    record UserIntent(String intent, String data, String orderId, String orderItemId, String payOrderId) {
        OrderToolTarget target() {
            return new OrderToolTarget(orderId, orderItemId, payOrderId);
        }
    }

    /**
     * 取消消息
     * @param messageId 消息ID
     */
    public void cancelMessage(String userId,Integer messageId) {
        AiCommentInputs.requireMessageId(userId, messageId);
        agentMessageService.cancelMessage(userId,messageId);
        redisComponent.cancelMessage(userId, messageId);
    }
}
