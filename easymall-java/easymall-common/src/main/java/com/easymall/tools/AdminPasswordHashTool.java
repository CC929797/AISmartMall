package com.easymall.tools;

import com.easymall.security.PasswordService;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ByteArrayResource;

import java.io.Console;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;

/** 独立本机工具，不启动 Spring 或连接数据库，不在参数、控制台和日志输出密码。 */
public final class AdminPasswordHashTool {
    private AdminPasswordHashTool() {
    }

    public static void main(String[] args) {
        try {
            if (args.length != 2) {
                throw new IllegalArgumentException();
            }
            Path path = Path.of(args[1]).toAbsolutePath().normalize();
            if ("--migrate-config".equals(args[0])) {
                migrate(path);
            } else if ("--new-config".equals(args[0])) {
                create(path);
            } else {
                throw new IllegalArgumentException();
            }
            System.out.println("管理员密码哈希配置已就绪；未输出密码或哈希。");
        } catch (Exception e) {
            System.err.println("管理员配置处理失败：请检查参数、文件格式及已有配置；原始密码不会输出。");
            System.exit(1);
        }
    }

    public static void migrate(Path path) throws Exception {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException();
        }
        String text = Files.readString(path, StandardCharsets.UTF_8);
        Properties before = properties(text);
        PasswordService passwords = new PasswordService();
        String existing = before.getProperty("admin.password-hash");
        if (passwords.isModernEncoding(existing) && !before.containsKey("admin.password")) {
            return;
        }
        String raw = before.getProperty("admin.password");
        if (existing != null || !passwords.validInput(raw) || raw.contains("${")) {
            throw new IllegalArgumentException();
        }
        String[] lines = text.split("(?<=\\n)", -1);
        boolean admin = false;
        int found = -1;
        var root = Pattern.compile("^admin\\s*:\\s*(?:#.*)?$");
        var property = Pattern.compile("^(\\s+)password\\s*:\\s*([^\\r\\n]*)(\\r?\\n)?$");
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].stripTrailing();
            if (root.matcher(line).matches()) {
                admin = true;
                continue;
            }
            if (!line.isBlank() && !line.startsWith("#") && !Character.isWhitespace(line.charAt(0))) {
                admin = false;
            }
            var match = property.matcher(lines[index]);
            if (admin && match.matches()) {
                if (found != -1 || match.group(2).startsWith("|") || match.group(2).startsWith(">")) {
                    throw new IllegalArgumentException();
                }
                found = index;
            }
        }
        if (found == -1) {
            throw new IllegalArgumentException();
        }
        var match = property.matcher(lines[found]);
        match.matches();
        String encoded = passwords.encode(raw);
        lines[found] = match.group(1) + "password-hash: '" + encoded + "'" + Objects.toString(match.group(3), "");
        String updated = String.join("", lines);
        Properties after = properties(updated);
        before.remove("admin.password");
        after.remove("admin.password-hash");
        if (!before.equals(after)) {
            throw new IllegalStateException();
        }
        // 临时文件与原文件位于同一目录，原子替换前原配置始终保持完整。
        Path temporary = path.resolveSibling(path.getFileName() + ".password-migration.tmp");
        Files.writeString(temporary, updated, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void create(Path path) throws Exception {
        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException();
        }
        char[] raw = console.readPassword("请输入新的管理员密码：");
        char[] confirm = console.readPassword("请再次输入：");
        try {
            if (!Arrays.equals(raw, confirm)) {
                throw new IllegalArgumentException();
            }
            PasswordService passwords = new PasswordService();
            passwords.validateNewPassword(new String(raw));
            String encoded = passwords.encode(new String(raw));
            Files.writeString(path, "admin:\n  password-hash: '" + encoded + "'\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
        } finally {
            Arrays.fill(raw, '\0');
            Arrays.fill(confirm, '\0');
        }
    }

    private static Properties properties(String yaml) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));
        return Objects.requireNonNull(factory.getObject());
    }
}
