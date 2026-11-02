package com.easymall.component;

import com.easymall.exception.BusinessException;
import com.easymall.security.CurrentUser;
import com.easymall.service.AgentMessageService;
import com.easymall.commonent.RedisComponent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 非法输入必须在保存消息或提交异步模型任务之前被拒绝。 */
class ChatInputBoundaryTest {
    private ChatComponent component;
    private AgentMessageService messages;
    private RedisComponent redis;

    @BeforeEach
    void setUp() {
        component = new ChatComponent();
        messages = mock(AgentMessageService.class);
        redis = mock(RedisComponent.class);
        ReflectionTestUtils.setField(component, "agentMessageService", messages);
        ReflectionTestUtils.setField(component, "redisComponent", redis);
        // 若旧代码误入保存步骤，立即终止，防止修改前测试启动任何异步任务。
        when(messages.saveMessage(anyString(), anyString()))
                .thenThrow(new IllegalStateException("synthetic premature save"));
    }

    @Test
    void overlongAndBlankMessagesNeverStartAnAsyncTask() {
        CurrentUser owner = new CurrentUser("INPUT00001", "合成用户", null, CurrentUser.Role.USER);
        for (String text : new String[]{"", " \t", "问".repeat(1001)}) {
            assertThatThrownBy(() -> component.sendMessage(owner, text)).isInstanceOf(BusinessException.class);
        }
        verifyNoInteractions(messages, redis);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void invalidCancelIdsCannotTouchTheDatabaseOrRedis(int messageId) {
        assertThatThrownBy(() -> component.cancelMessage("INPUT00001", messageId))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(messages, redis);
    }
}
