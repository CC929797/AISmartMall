package com.easymall.security;

import com.alipay.api.internal.util.AlipaySignature;
import org.junit.jupiter.api.Test;
import java.security.KeyPairGenerator;
import java.util.Base64;
import static org.assertj.core.api.Assertions.assertThat;

/** 合并 BC 依赖后检查现有 SDK 的本地 RSA2 签名与验签，不连接支付渠道。 */
class CryptoDependencyCompatibilityTest {
    @Test
    void paymentSdkCanSignAndVerifyWithSyntheticKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        String privateKey = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        String signature = AlipaySignature.rsa256Sign("synthetic-password-migration-probe", privateKey, "UTF-8");
        assertThat(AlipaySignature.verify("synthetic-password-migration-probe", signature, publicKey, "UTF-8", "RSA2"))
                .isTrue();
        assertThat(AlipaySignature.verify("tampered-probe", signature, publicKey, "UTF-8", "RSA2"))
                .isFalse();
    }
}
