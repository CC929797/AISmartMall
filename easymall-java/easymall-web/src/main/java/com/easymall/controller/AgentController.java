package com.easymall.controller;

import com.easymall.annotation.GlobalInterceptor;
import com.easymall.component.ChatComponent;
import com.easymall.entity.po.AgentMessage;
import com.easymall.entity.query.AgentMessageQuery;
import com.easymall.entity.vo.PaginationResultVO;
import com.easymall.entity.vo.ResponseVO;
import com.easymall.service.AgentMessageService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.cms.PasswordRecipientInfo;
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
    public ResponseVO sendMessage(String message) {
        AgentMessage agentMessage = chatComponent.sendMessage(getTokenUserInfo().getUserId(), message);
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
    public ResponseVO loadHistoryMessage(Integer pageNo,Integer maxMessageId) {
        AgentMessageQuery agentMessageQuery = new AgentMessageQuery();
        agentMessageQuery.setOrderBy("message_id desc");
        agentMessageQuery.setPageNo(pageNo);
        agentMessageQuery.setUserId(getTokenUserInfo().getUserId());
        agentMessageQuery.setMaxMessageId(maxMessageId);
        PaginationResultVO<AgentMessage> resultVO = agentMessageService.findListByPage(agentMessageQuery);
        return getSuccessResponseVO(resultVO);
    }
}
