package com.easymall.component;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;
import java.util.concurrent.atomic.AtomicLong;

/** 默认不记录模型调用；开启时仅记录耗时和分片数，不接触提示词或元数据。 */
@Slf4j
public class LoggerAdvisor implements CallAdvisor, StreamAdvisor {
    private final boolean summaryEnabled;
    public LoggerAdvisor() {
        this(false);
    }
    public LoggerAdvisor(boolean summaryEnabled) {
        this.summaryEnabled = summaryEnabled;
    }
    @Override public String getName() {
        return getClass().getSimpleName();
    }
    @Override public int getOrder() {
        return 0;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long started = System.nanoTime();
        ChatClientResponse response = chain.nextCall(request);
        if (summaryEnabled) {
            log.info("模型调用完成，耗时毫秒={}", elapsed(started));
        }
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        Flux<ChatClientResponse> responses = chain.nextStream(request);
        if (!summaryEnabled) {
            return responses;
        }
        return responses.transformDeferred(stream -> {
            long started = System.nanoTime();
            AtomicLong chunks = new AtomicLong();
            return stream.doOnNext(response -> chunks.incrementAndGet())
                    .doFinally(signal -> {
                        if (summaryEnabled) {
                            log.info("模型流结束，结束类型={}，分片数={}，耗时毫秒={}", signal, chunks.get(), elapsed(started));
                        }
                    });
        });
    }
    private long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}