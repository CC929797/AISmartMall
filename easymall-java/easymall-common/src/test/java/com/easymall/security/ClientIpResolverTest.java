package com.easymall.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class ClientIpResolverTest {
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"198.51.100.1", "198.51.100.1, 127.0.0.1", "attacker.example", "unknown"})
    void directClientCannotSelectIdentityWithForwardedHeader(String header) {
        assertThat(new ClientIpResolver("").resolve("192.0.2.10", header)).isEqualTo("192.0.2.10");
    }

    @Test
    void onlyTrustedSuffixOfProxyChainIsRemoved() {
        var resolver = new ClientIpResolver("127.0.0.1,192.0.2.20");
        assertThat(resolver.resolve("127.0.0.1", "198.51.100.7, 192.0.2.20")).isEqualTo("198.51.100.7");
        assertThat(resolver.resolve("127.0.0.1", "198.51.100.99, 203.0.113.5")).isEqualTo("203.0.113.5");
        assertThat(resolver.resolve("127.0.0.1", "not-an-ip")).isEqualTo("127.0.0.1");
    }

    @Test
    void malformedProxyConfigurationIsRejected() {
        assertThatThrownBy(() -> new ClientIpResolver("example.com")).isInstanceOf(IllegalArgumentException.class);
    }
}
