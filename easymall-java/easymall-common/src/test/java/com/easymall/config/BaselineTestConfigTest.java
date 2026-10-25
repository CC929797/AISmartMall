package com.easymall.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BaselineTestConfigTest {

    /**
     * Parses Boot ConfigData without creating an ApplicationContext or beans.
     * This checks resolved configuration, not full application startup isolation.
     */
    @Test
    void surefireLoadsOnlyTheExplicitIsolatedTestConfiguration() {
        String location = System.getProperty("spring.config.location");
        assertThat(location).isNotBlank().startsWith("file:");
        assertThat(location.replace('\\', '/'))
                .endsWith("/config/test/application-test.yml");
        assertThat(System.getProperty("spring.profiles.active")).isEqualTo("test");

        StandardEnvironment environment = new StandardEnvironment();
        ConfigDataEnvironmentPostProcessor.applyTo(environment);

        assertThat(environment.getActiveProfiles()).containsExactly("test");
        Map<String, String> expected = Map.ofEntries(
                Map.entry("spring.datasource.url",
                        "jdbc:mysql://127.0.0.1:1/easymall_test?connectTimeout=1000&socketTimeout=1000"),
                Map.entry("spring.datasource.username", "test-placeholder"),
                Map.entry("spring.datasource.password", "test-placeholder"),
                Map.entry("spring.data.redis.host", "127.0.0.1"),
                Map.entry("spring.data.redis.port", "1"),
                Map.entry("spring.elasticsearch.uris", "http://127.0.0.1:1"),
                Map.entry("project.auto-checkpay", "false"),
                Map.entry("alipay.serverUrl", "http://127.0.0.1:1"),
                Map.entry("spring.ai.model.chat", "none"),
                Map.entry("spring.ai.model.embedding", "none"),
                Map.entry("spring.ai.openai.base-url", "http://127.0.0.1:1"),
                Map.entry("spring.ai.openai.api-key", "test-placeholder"),
                Map.entry("spring.ai.mcp.client.enabled", "false"),
                Map.entry("spring.ai.mcp.server.enabled", "false"),
                Map.entry("spring.ai.vectorstore.elasticsearch.initialize-schema", "false"),
                Map.entry("spring.ai.vectorstore.elasticsearch.index-name", "easymall-test-vectorstore"));

        // Compare booleans so a failing assertion cannot print an unexpected credential.
        expected.forEach((key, value) -> assertThat(value.equals(environment.getProperty(key)))
                .as("%s must resolve to its isolated test value", key)
                .isTrue());
        assertThat(environment.containsProperty("spring.ai.dashscope.api-key")).isFalse();
        assertThat(environment.containsProperty("spring.ai.dashscope.base-url")).isFalse();
        assertThat(environment.getPropertySources().stream()
                .anyMatch(source -> source.getName().contains("application-dev")))
                .as("development ConfigData must not be loaded")
                .isFalse();
    }
}
