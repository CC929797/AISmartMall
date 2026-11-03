package com.easymall.security;

import com.easymall.tools.AdminPasswordHashTool;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class AdminPasswordHashToolTest {
    private Path config(String yaml) throws Exception {
        Path folder = Path.of("target", "password-tool-fixtures");
        Files.createDirectories(folder);
        Path file = folder.resolve(UUID.randomUUID() + ".private.yml").toAbsolutePath();
        Files.writeString(file, yaml);
        return file;
    }

    @Test
    void migratesOnlyCredentialAndPreservesOtherConfigAndLineEndings() throws Exception {
        Path file = config("admin:\r\n  account: synthetic-admin\r\n  password: 'Synthetic123'\r\n"
                + "logistics:\r\n  mode: simulated\r\nlog:\r\n  root:\r\n    level: debug\r\n");
        AdminPasswordHashTool.migrate(file);
        String updated = Files.readString(file);
        assertThat(updated).contains("account: synthetic-admin\r\n", "password-hash: '$argon2id$",
                "logistics:\r\n  mode: simulated\r\n", "    level: debug\r\n").doesNotContain("Synthetic123", "  password:");
        AdminPasswordHashTool.migrate(file);
        assertThat(Files.readString(file)).isEqualTo(updated);
    }

    @Test
    void ambiguousOrUnresolvedConfigIsNotWritten() throws Exception {
        String original = "admin:\n  password: '${ADMIN_PASSWORD}'\n";
        Path file = config(original);
        assertThatThrownBy(() -> AdminPasswordHashTool.migrate(file)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readString(file)).isEqualTo(original);
    }
}
