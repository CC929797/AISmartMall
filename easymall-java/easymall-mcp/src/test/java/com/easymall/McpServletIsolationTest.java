package com.easymall;

import com.easymall.files.ImageMultipartConfig;
import com.easymall.utils.FileUtils;
import com.easymall.validation.RequestInputAdvice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.util.ClassUtils;

import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;

/** 使用 MCP 自身不含 Servlet/Tomcat 的测试类路径，防止公共配置污染 WebFlux。 */
class McpServletIsolationTest {
    @Test
    void mcpRuntimeDoesNotNeedTomcatOrServletApi() {
        ClassLoader loader = getClass().getClassLoader();
        assertThat(ClassUtils.isPresent("org.apache.catalina.connector.Connector", loader)).isFalse();
        assertThat(ClassUtils.isPresent("jakarta.servlet.http.HttpServletResponse", loader)).isFalse();
    }

    static Stream<Class<?>> servletComponents() {
        return Stream.of(ImageMultipartConfig.class, FileUtils.class, RequestInputAdvice.class);
    }

    @ParameterizedTest
    @MethodSource("servletComponents")
    void reactiveContextDoesNotLoadServletOnlyComponents(Class<?> component) {
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(component)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(component);
                });
    }
}
