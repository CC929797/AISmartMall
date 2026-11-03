package com.easymall.security;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/** 记录本机实际哈希耗时，不设不稳定的计时断言，也不输出密码或编码。 */
class PasswordCostTest {
    @Test
    void recordsBoundedPasswordHashCostWithoutCredentials() throws Exception {
        PasswordService passwords = new PasswordService();
        String raw = "Synthetic123";
        passwords.matches(raw, passwords.encode(raw));
        List<Double> encode = new ArrayList<>();
        List<Double> verify = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            long start = System.nanoTime();
            String stored = passwords.encode(raw);
            encode.add((System.nanoTime() - start) / 1_000_000.0);
            start = System.nanoTime();
            assertThat(passwords.matches(raw, stored)).isTrue();
            verify.add((System.nanoTime() - start) / 1_000_000.0);
        }
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("algorithm", "Argon2id");
        result.put("memoryKiB", PasswordService.MEMORY_KIB);
        result.put("iterations", PasswordService.ITERATIONS);
        result.put("parallelism", 1);
        result.put("saltBytes", 16);
        result.put("hashBytes", 32);
        result.put("samples", 10);
        result.put("encodeAverageMs", encode.stream().mapToDouble(Double::doubleValue).average().orElseThrow());
        result.put("verifyAverageMs", verify.stream().mapToDouble(Double::doubleValue).average().orElseThrow());
        result.put("encodeMaxMs", encode.stream().mapToDouble(Double::doubleValue).max().orElseThrow());
        result.put("verifyMaxMs", verify.stream().mapToDouble(Double::doubleValue).max().orElseThrow());
        new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValue(Path.of("target", "password-cost.json").toFile(), result);
    }
}
