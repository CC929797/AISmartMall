package com.easymall.controller;

import com.easymall.annotation.GlobalInterceptor;
import com.easymall.component.ChatComponent;
import com.easymall.entity.po.AgentMessage;
import com.easymall.entity.query.AgentMessageQuery;
import com.easymall.entity.vo.PaginationResultVO;
import com.easymall.entity.vo.ResponseVO;
import com.easymall.service.AgentMessageService;
import com.easymall.security.CurrentUser;
import com.easymall.validation.AiCommentInputs;
import com.easymall.validation.InputLimits;
import com.easymall.validation.InputValidator;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/agent")
@Slf4j
public class AgentController extends ABaseController{
    @Resource
    private AgentMessageService agentMessageService;
    @Resource
    private ChatComponent chatComponent;

    @RequestMapping("/sendMessage")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO sendMessage(@NotBlank @Size(max = InputLimits.MAX_MESSAGE_LENGTH) String message) {
        CurrentUser currentUser = getCurrentUser();
        AiCommentInputs.requireMessage(currentUser.userId(), message);
        AgentMessage agentMessage = chatComponent.sendMessage(currentUser, message);
        return getSuccessResponseVO(agentMessage);
    }

    /**
     * 加载历史消息
     * @param pageNo
     * @param maxMessageId
     * @return
     */
    @RequestMapping("/loadHistoryMessage")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO loadHistoryMessage(@Min(1) @Max(InputLimits.MAX_PAGE_NO) Integer pageNo, @Min(1) Integer maxMessageId) {
        InputValidator.optionalPageNo(pageNo);
        if (maxMessageId != null) { InputValidator.requireRange(maxMessageId, 1, Integer.MAX_VALUE); }
        AgentMessageQuery agentMessageQuery = new AgentMessageQuery();
        agentMessageQuery.setOrderBy("message_id desc");
        agentMessageQuery.setPageNo(pageNo);
        agentMessageQuery.setUserId(getTokenUserInfo().getUserId());
        agentMessageQuery.setMaxMessageId(maxMessageId);
        PaginationResultVO<AgentMessage> resultVO = agentMessageService.findListByPage(agentMessageQuery);
        return getSuccessResponseVO(resultVO);
    }

    /**
     * 取消消息
     * @param messageId 消息Id
     * @return 取消成功
     */
    @RequestMapping("/cancelMessage")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO cancelMessage(@NotNull @Min(1) Integer messageId) {
        String userId = getTokenUserInfo().getUserId();
        AiCommentInputs.requireMessageId(userId, messageId);
        chatComponent.cancelMessage(userId,messageId);
        return getSuccessResponseVO(null);
    }
}
