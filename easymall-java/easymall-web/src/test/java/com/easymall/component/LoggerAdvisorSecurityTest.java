package com.easymall.component;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.client.advisor.api.*;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoggerAdvisorSecurityTest {
    Logger logger;
    Level before;
    ListAppender<ILoggingEvent> logs;
    ChatClientRequest request;
    ChatClientResponse response;
    @BeforeEach void setup() {
        logger = (Logger) LoggerFactory.getLogger(LoggerAdvisor.class);
        before = logger.getLevel(); logger.setLevel(Level.INFO);
        logs = new ListAppender<>(); logs.start(); logger.addAppender(logs);
        request = mock(ChatClientRequest.class); response = mock(ChatClientResponse.class);
        when(request.toString()).thenReturn("fixture-password-token-api-key-prompt");
        when(response.toString()).thenReturn("fixture-private-order-address-answer");
    }
    @AfterEach void stop() { logger.detachAppender(logs); logger.setLevel(before); }
    @Test void defaultCallProducesNoModelLogs() {
        var chain = mock(CallAdvisorChain.class); when(chain.nextCall(request)).thenReturn(response);
        assertSame(response, new LoggerAdvisor().adviseCall(request, chain));
        assertTrue(logs.list.isEmpty());
    }
    @Test void enabledCallContainsOnlyTiming() {
        var chain = mock(CallAdvisorChain.class); when(chain.nextCall(request)).thenReturn(response);
        new LoggerAdvisor(true).adviseCall(request, chain);
        assertEquals(1, logs.list.size()); assertNoSecret();
    }
    @Test void streamPreservesChunksAndLogsOnlySummary() {
        var chain = mock(StreamAdvisorChain.class); when(chain.nextStream(request)).thenReturn(Flux.just(response, response));
        assertEquals(2, new LoggerAdvisor(true).adviseStream(request, chain).collectList().block().size());
        assertEquals(1, logs.list.size()); assertNoSecret();
        assertTrue(logs.list.getFirst().getFormattedMessage().contains("分片数=2"));
    }
    @Test void defaultStreamIsSilent() {
        var chain = mock(StreamAdvisorChain.class); when(chain.nextStream(request)).thenReturn(Flux.just(response));
        new LoggerAdvisor().adviseStream(request, chain).blockLast(); assertTrue(logs.list.isEmpty());
    }
    @Test void streamExceptionMessageDoesNotLeak() {
        var chain = mock(StreamAdvisorChain.class); when(chain.nextStream(request)).thenReturn(Flux.error(new IllegalStateException("fixture-secret")));
        assertThrows(IllegalStateException.class, () -> new LoggerAdvisor(true).adviseStream(request, chain).blockLast());
        assertNoSecret();
    }
    void assertNoSecret() {
        assertTrue(logs.list.stream().noneMatch(log -> log.getFormattedMessage().contains("fixture-") || log.getThrowableProxy() != null));
    }

    @Test void multipleSubscriptionsDoNotConsumeAdvisorChainAgain() {
        var chain = mock(StreamAdvisorChain.class); when(chain.nextStream(request)).thenReturn(Flux.just(response));
        var stream = new LoggerAdvisor(true).adviseStream(request, chain);
        stream.blockLast(); stream.blockLast();
        verify(chain, times(1)).nextStream(request);
        assertEquals(2, logs.list.size()); assertNoSecret();
    }
}
