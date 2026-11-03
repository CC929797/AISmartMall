package com.easymall.security;

import com.easymall.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.Set;

/** 沙箱与真实支付显式区分，禁止将 SDK 凭据发送到任意配置地址。 */
@Component
public class PaymentGatewayPolicy {
    @Value("${payment.mode:sandbox}") private String mode = "sandbox";
    public void validate(String address) {
        try {
            URI uri = URI.create(address);
            String host = uri.getHost();
            boolean allowed = "sandbox".equals(mode)
                    ? Set.of("openapi-sandbox.dl.alipaydev.com", "openapi.alipaydev.com").contains(host)
                    : "live".equals(mode) && "openapi.alipay.com".equals(host);
            if (!allowed || !"https".equals(uri.getScheme()) || uri.getUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443) || !"/gateway.do".equals(uri.getPath())
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException e) { throw new BusinessException("支付网关与当前沙箱/真实模式不匹配，请检查配置"); }
    }
}
