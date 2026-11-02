package com.easymall.controller;

import com.easymall.component.ChatComponent;
import com.easymall.entity.dto.TokenUserInfoDTO;
import com.easymall.entity.po.AgentMessage;
import com.easymall.security.CurrentUser;
import com.easymall.service.OrderCommentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** 按当前前端的表单协议校验输入，非法请求不能进入 AI 或评论服务。 */
class AiCommentControllerBoundaryTest {
    private static final CurrentUser OWNER = new CurrentUser("INPUT00001", "合成用户", null, CurrentUser.Role.USER);
    private ChatComponent chat;
    private OrderCommentService reviews;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        chat = mock(ChatComponent.class);
        reviews = mock(OrderCommentService.class);
        AgentController agent = new AgentController() {
            @Override protected CurrentUser getCurrentUser() { return OWNER; }
            @Override protected TokenUserInfoDTO getTokenUserInfo() { return ownerToken(); }
        };
        OrderCommentController comments = new OrderCommentController() {
            @Override protected TokenUserInfoDTO getTokenUserInfo() { return ownerToken(); }
        };
        ReflectionTestUtils.setField(agent, "chatComponent", chat);
        ReflectionTestUtils.setField(comments, "orderCommentService", reviews);
        mvc = MockMvcBuilders.standaloneSetup(agent, comments)
                .setControllerAdvice(new AGlobalExceptionHandlerController()).build();
    }

    @Test
    void emptyAndOverlongAiMessagesAreRejected() throws Exception {
        mvc.perform(post("/agent/sendMessage"))
                .andExpect(jsonPath("$.code").value(600));
        for (String text : new String[]{"", " \t", "问".repeat(1001)}) {
            mvc.perform(post("/agent/sendMessage").param("message", text))
                    .andExpect(jsonPath("$.code").value(600));
        }
        verifyNoInteractions(chat);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void cancellationRequiresAPositiveMessageId(String messageId) throws Exception {
        mvc.perform(post("/agent/cancelMessage").param("messageId", messageId))
                .andExpect(jsonPath("$.code").value(600));
        verifyNoInteractions(chat);
    }

    @Test
    void missingMessageIdCannotReachCancellation() throws Exception {
        mvc.perform(post("/agent/cancelMessage"))
                .andExpect(jsonPath("$.code").value(600));
        verifyNoInteractions(chat);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "6"})
    void invalidRatingsCannotReachTheReviewService(String star) throws Exception {
        mvc.perform(post("/order/comment/postComment").param("orderId", "ORDER-INPUT")
                        .param("commentContent", "合成评价").param("star", star))
                .andExpect(jsonPath("$.code").value(600));
        verifyNoInteractions(reviews);
    }

    @Test
    void blankReviewAndTooManyImagesAreRejectedForInitialAndFollowUpReviews() throws Exception {
        for (String operation : new String[]{"postComment", "postReComment"}) {
            boolean initial = operation.equals("postComment");
            mvc.perform(post("/order/comment/" + operation).param("orderId", "ORDER-INPUT")
                            .param(initial ? "commentContent" : "reCommentContent", " \t")
                            .param("star", "5"))
                    .andExpect(jsonPath("$.code").value(600));
            mvc.perform(post("/order/comment/" + operation).param("orderId", "ORDER-INPUT")
                            .param(initial ? "commentContent" : "reCommentContent", "合成评价")
                            .param(initial ? "commentImages" : "reCommentImages", "a,b,c,d,e,f")
                            .param("star", "5"))
                    .andExpect(jsonPath("$.code").value(600));
        }
        verifyNoInteractions(reviews);
    }

    @Test
    void legalFormRequestsKeepTheirExistingResponseAndServiceContract() throws Exception {
        AgentMessage message = new AgentMessage();
        message.setMessageId(123);
        message.setUserId(OWNER.userId());
        when(chat.sendMessage(any(), anyString())).thenReturn(message);
        String text = "问".repeat(1000);
        mvc.perform(post("/agent/sendMessage").param("message", text))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.messageId").value(123));
        verify(chat).sendMessage(OWNER, text);

        mvc.perform(post("/order/comment/postComment").param("orderId", "ORDER-INPUT")
                        .param("commentContent", "合成评价").param("star", "5"))
                .andExpect(jsonPath("$.code").value(200));
        verify(reviews).postComment(OWNER.userId(), "ORDER-INPUT", "合成评价", null, 5);
    }

    private static TokenUserInfoDTO ownerToken() {
        TokenUserInfoDTO token = new TokenUserInfoDTO();
        token.setUserId(OWNER.userId());
        return token;
    }
}
