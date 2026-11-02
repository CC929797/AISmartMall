package com.easymall.service;

import com.easymall.entity.po.AgentMessage;
import com.easymall.entity.query.AgentMessageQuery;
import com.easymall.exception.BusinessException;
import com.easymall.mappers.AgentMessageMapper;
import com.easymall.service.Impl.AgentMessageServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 验证绕过 Controller 直接调用服务时，非法消息也不会读写数据库。 */
@SuppressWarnings("unchecked")
class AiMessageInputBoundaryTest {
    private AgentMessageMapper<AgentMessage, AgentMessageQuery> messages;
    private AgentMessageServiceImpl service;

    @BeforeEach
    void setUp() {
        messages = mock(AgentMessageMapper.class);
        service = new AgentMessageServiceImpl();
        ReflectionTestUtils.setField(service, "agentMessageMapper", messages);
    }

    static Stream<String> invalidMessages() {
        return Stream.of(null, "", " \t\n", "x".repeat(1001));
    }

    @ParameterizedTest
    @MethodSource("invalidMessages")
    void invalidMessagesAreRejectedBeforeInsert(String text) {
        assertThatThrownBy(() -> service.saveMessage("INPUT00001", text))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(messages);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "USER1234567"})
    void userIdentityMustFitTheActualDatabaseColumn(String userId) {
        assertThatThrownBy(() -> service.saveMessage(userId, "合成问题"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(messages);
    }

    static Stream<Integer> invalidMessageIds() { return Stream.of(null, 0, -1); }

    @ParameterizedTest
    @MethodSource("invalidMessageIds")
    void invalidCancellationCannotBroadenAnUpdate(Integer messageId) {
        assertThatThrownBy(() -> service.cancelMessage("INPUT00001", messageId))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(messages);
    }

    @Test
    void aMessageAtTheFrontendLimitStillKeepsItsOwnerAndText() {
        String text = "问".repeat(1000);
        AgentMessage saved = service.saveMessage("INPUT00001", text);
        assertThat(saved.getUserId()).isEqualTo("INPUT00001");
        assertThat(saved.getUserMessage()).isEqualTo(text);
        verify(messages).insert(saved);
    }

    @Test
    void cancellationStillTargetsOnlyTheExplicitMessageAndOwner() {
        service.cancelMessage("INPUT00001", 123);
        ArgumentCaptor<AgentMessageQuery> query = ArgumentCaptor.forClass(AgentMessageQuery.class);
        verify(messages).updateByParam(any(), query.capture());
        assertThat(query.getValue().getMessageId()).isEqualTo(123);
        assertThat(query.getValue().getUserId()).isEqualTo("INPUT00001");
    }
}
