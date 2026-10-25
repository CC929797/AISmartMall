package com.easymall.utils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSONException;
import com.easymall.entity.enums.ResponseCodeEnum;
import com.easymall.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class JsonUtilsLoggingTest {

    private static final String PASSWORD_CANARY = "synthetic-password-canary";
    private static final String API_KEY_CANARY = "synthetic-api-key-canary";

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private Level previousLevel;
    private boolean previousAdditivity;

    @BeforeEach
    void captureOnlyInMemory() {
        logger = (Logger) LoggerFactory.getLogger(JsonUtils.class);
        previousLevel = logger.getLevel();
        previousAdditivity = logger.isAdditive();
        logger.setLevel(Level.ERROR);
        // A red regression test must not forward the unsafe original log to the console.
        logger.setAdditive(false);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void restoreLogger() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
        logger.setAdditive(previousAdditivity);
    }

    @Test
    void invalidObjectLogsOnlyConversionMetadata() {
        String json = "{\"password\":\"" + PASSWORD_CANARY + "\",\"apiKey\":\""
                + API_KEY_CANARY + "\",\"unfinished\":@}";

        assertConversionFailure(() -> JsonUtils.convertJson2Obj(json, Map.class));
        assertSafeConversionLog(json);
    }

    @Test
    void invalidArrayLogsOnlyConversionMetadataWithoutTheParserThrowable() {
        String json = "[{\"password\":\"" + PASSWORD_CANARY + "\",\"apiKey\":\""
                + API_KEY_CANARY + "\"},@]";

        assertConversionFailure(() -> JsonUtils.convertJsonArray2List(json, Map.class));
        assertSafeConversionLog(json);
    }

    private void assertConversionFailure(Runnable conversion) {
        Throwable thrown = null;
        try {
            conversion.run();
        } catch (Throwable failure) {
            thrown = failure;
        }
        // Boolean comparisons ensure a failed assertion cannot print parser input.
        assertThat(thrown instanceof BusinessException)
                .as("conversion failures must retain the business exception contract")
                .isTrue();
        BusinessException businessException = (BusinessException) thrown;
        assertThat(Objects.equals(businessException.getCode(), ResponseCodeEnum.CODE_603.getCode()))
                .as("conversion failures must retain code 603")
                .isTrue();
        assertThat(Objects.equals(businessException.getMessage(), ResponseCodeEnum.CODE_603.getMsg()))
                .as("conversion failures must retain the generic conversion message")
                .isTrue();
        assertThat(businessException.getCause() == null)
                .as("the public business exception must not retain the raw parser failure")
                .isTrue();
    }

    private void assertSafeConversionLog(String input) {
        assertThat(appender.list.size()).as("one conversion failure event").isEqualTo(1);
        ILoggingEvent event = appender.list.getFirst();
        String message = event.getFormattedMessage();
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(!message.contains(input) && !message.contains(PASSWORD_CANARY)
                && !message.contains(API_KEY_CANARY) && !message.contains("password")
                && !message.contains("apiKey"))
                .as("failure logs must not contain source JSON or sensitive fields")
                .isTrue();
        assertThat(event.getThrowableProxy() == null)
                .as("failure logs must not attach a parser throwable containing input")
                .isTrue();
        assertThat(message.contains("targetType=" + Map.class.getName()))
                .as("failure logs must retain the target type")
                .isTrue();
        assertThat(message.contains("inputLength=" + input.length()))
                .as("failure logs must retain the input length")
                .isTrue();
        assertThat(message.contains("exceptionType=" + JSONException.class.getName()))
                .as("failure logs must retain only the parser exception class")
                .isTrue();
    }
}
