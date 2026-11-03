package com.easymall.files;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import static org.assertj.core.api.Assertions.assertThat;

/** 隔离无 Tomcat 的应用，同时验证 Web/Admin 所需的上传拒绝连接修复仍然有效。 */
class ImageMultipartIsolationTest {
    @Test
    void nonWebContextDoesNotCreateServletCustomizer() {
        new ApplicationContextRunner().withUserConfiguration(ImageMultipartConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("boundedMultipartDrain");
                });
    }

    @Test
    void servletContextWithoutTomcatDoesNotLoadTomcatCustomizer() {
        new WebApplicationContextRunner()
                .withClassLoader(new FilteredClassLoader("org.apache.catalina", "org.apache.coyote"))
                .withUserConfiguration(ImageMultipartConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("boundedMultipartDrain");
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void tomcatServletContextKeepsBoundedMultipartDrain() {
        new WebApplicationContextRunner().withUserConfiguration(ImageMultipartConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    WebServerFactoryCustomizer<TomcatServletWebServerFactory> customizer =
                            context.getBean("boundedMultipartDrain", WebServerFactoryCustomizer.class);
                    var factory = new TomcatServletWebServerFactory();
                    customizer.customize(factory);
                    var connector = new Connector();
                    factory.getTomcatConnectorCustomizers().forEach(item -> item.customize(connector));
                    assertThat(((AbstractHttp11Protocol<?>) connector.getProtocolHandler()).getMaxSwallowSize())
                            .isEqualTo(10 * 1024 * 1024);
                });
    }
}
