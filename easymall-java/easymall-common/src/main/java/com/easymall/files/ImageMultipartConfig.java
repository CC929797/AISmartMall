package com.easymall.files;

import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 超限后仍有界地消费剩余请求体，使客户端能收到参数拒绝而不是连接重置。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = {"org.apache.catalina.connector.Connector", "org.apache.coyote.http11.AbstractHttp11Protocol"})
public class ImageMultipartConfig {
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> boundedMultipartDrain() {
        return factory -> factory.addConnectorCustomizers(connector -> {
            if (connector.getProtocolHandler() instanceof AbstractHttp11Protocol<?> protocol) {
                protocol.setMaxSwallowSize(10 * 1024 * 1024);
            }
        });
    }
}
