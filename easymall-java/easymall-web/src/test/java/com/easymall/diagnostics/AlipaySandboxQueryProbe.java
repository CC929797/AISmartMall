package com.easymall.diagnostics;

import com.alipay.api.AlipayClient;
import com.alipay.api.AlipayConfig;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayTradeQueryModel;
import com.alipay.api.internal.util.AlipayLogger;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.response.AlipayTradeQueryResponse;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 仅用于人工测试：使用商城现有的证书配置，独立调用支付宝沙箱查单接口。
 * 在 IDEA 中运行本类 main 即可，不启动 Spring Boot、数据库、Redis 或后台轮询。
 * 默认查询一个故意不存在的订单号；实际 payOrderId 可以通过 Program arguments 传入。
 * 只执行一次查单，不下单、不支付、不关单、不退款，也不修改商城订单状态。
 * 本类没有 JUnit 的 @Test，运行 Maven 自动化测试不会发送真实支付宝请求。
 */
public final class AlipaySandboxQueryProbe {
    // 仅测试用：不存在的订单用于观察支付宝能否正常返回“交易不存在”。
    private static final String DEFAULT_ORDER = "053313847837857809859793050566";
    private static final String SANDBOX_GATEWAY = "https://openapi-sandbox.dl.alipaydev.com/gateway.do";

    private AlipaySandboxQueryProbe() { }

    public static void main(String[] args) {
        // SDK 默认错误日志可能包含签名和响应正文；独立测试进程只输出下面明确列出的字段。
        AlipayLogger.setNeedEnableLogger(false);
        int exitCode = run(args, System.out, DefaultAlipayClient::new);
        if (exitCode != 0) System.exit(exitCode);
    }

    /** 仅测试入口：工厂参数让自动化测试可以使用模拟客户端，完全不联网。 */
    static int run(String[] args, PrintStream output, ClientFactory factory) {
        try {
            String orderId = DEFAULT_ORDER;
            Path configFile = null;
            boolean checkOnly = false;
            boolean hasOrder = false;
            for (String arg : args) {
                if ("--check".equals(arg)) checkOnly = true;
                else if (arg.startsWith("--config=")) configFile = Path.of(arg.substring("--config=".length()));
                else if (arg.startsWith("--") || hasOrder) throw new ProbeInputException("参数格式： [payOrderId] [--check] [--config=配置文件完整路径]");
                else { orderId = arg; hasOrder = true; }
            }
            if (!orderId.matches("[A-Za-z0-9_-]{1,64}")) throw new ProbeInputException("payOrderId 只能包含 1～64 位字母、数字、下划线或连字符。");
            if (configFile == null) configFile = findDefaultConfig();
            configFile = configFile.toAbsolutePath().normalize();
            AlipayConfig config = loadConfig(configFile);

            // 输出调用身份，方便与网页调试工具对照；不打印私钥或完整配置。
            output.println("configFile=" + configFile);
            output.println("appId=" + config.getAppId());
            output.println("serverUrl=" + config.getServerUrl());
            output.println("signMode=证书模式 / RSA2");
            AlipayClient client = factory.create(config);
            if (checkOnly) {
                output.println("本地配置及证书已被 SDK 加载；未发送请求。此检查不代表支付宝已认可配置或订单。");
                return 0;
            }

            // 与正式 PayChannel4Alipay.queryOrder 相同：使用 payOrderId 作为 out_trade_no。
            AlipayTradeQueryModel model = new AlipayTradeQueryModel();
            model.setOutTradeNo(orderId);
            AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
            request.setBizModel(model);
            output.println("out_trade_no=" + orderId);
            output.println("开始执行一次只读查单……");
            AlipayTradeQueryResponse response = client.certificateExecute(request);
            output.println("code=" + safeText(response.getCode()));
            output.println("msg=" + safeText(response.getMsg()));
            output.println("sub_code=" + safeText(response.getSubCode()));
            output.println("sub_msg=" + safeText(response.getSubMsg()));
            output.println("trade_status=" + safeText(response.getTradeStatus()));
            output.println("查单结束。以上是支付宝返回结果；本工具没有修改商城订单状态。");
            return 0;
        } catch (ProbeInputException invalid) {
            output.println("测试未执行：" + invalid.getMessage());
            return 2;
        } catch (Exception failure) {
            // YAML 解析异常、SDK 异常正文都可能夹带配置或签名，因此只显示异常类型。
            output.println("测试未完成，异常类型=" + failure.getClass().getSimpleName());
            output.println("请核对配置文件、证书文件和密钥格式；未确认订单的支付状态。");
            return 2;
        }
    }

    /** 直接读取 YAML，不创建 Spring 应用上下文；系统属性及环境变量仍可覆盖同名属性。 */
    private static AlipayConfig loadConfig(Path file) throws Exception {
        if (!Files.isRegularFile(file)) throw new ProbeInputException("配置文件不存在，请通过 --config=完整路径 指定。");
        StandardEnvironment environment = new StandardEnvironment();
        for (var source : new YamlPropertySourceLoader().load("alipay-query-probe", new FileSystemResource(file))) {
            environment.getPropertySources().addLast(source);
        }
        AlipayConfig config = new AlipayConfig();
        config.setAppId(required(environment, "alipay.appid"));
        if (!config.getAppId().matches("[0-9]{16}")) throw new ProbeInputException("alipay.appid 应为 16 位数字。");
        config.setServerUrl(required(environment, "alipay.serverUrl"));
        if (!SANDBOX_GATEWAY.equals(config.getServerUrl())) throw new ProbeInputException("本测试类只支持项目当前的支付宝沙箱网关。");
        config.setPrivateKey(required(environment, "alipay.appPrivateKey"));
        Path folder = Path.of(required(environment, "project.folder"));
        config.setAppCertPath(certificatePath(environment, folder, "alipay.appCertPath"));
        config.setAlipayPublicCertPath(certificatePath(environment, folder, "alipay.alipayPublicCertPath"));
        config.setRootCertPath(certificatePath(environment, folder, "alipay.alipayRootCertPath"));
        config.setCharset("UTF8");
        config.setSignType("RSA2");
        config.setFormat("json");
        config.setConnectTimeout(5000);
        config.setReadTimeout(10000);
        return config;
    }

    /** 支持从工作区根目录、easymall-java 或 easymall-web 目录运行。 */
    private static Path findDefaultConfig() {
        for (Path current = Path.of("").toAbsolutePath(); current != null; current = current.getParent()) {
            for (String relative : new String[]{
                    "easymall-web/src/main/resources/application-dev.yml",
                    "easymall-java/easymall-web/src/main/resources/application-dev.yml"}) {
                Path candidate = current.resolve(relative);
                if (Files.isRegularFile(candidate)) return candidate;
            }
        }
        throw new ProbeInputException("未找到商城端 application-dev.yml，请通过 --config=完整路径 指定。");
    }

    private static String required(StandardEnvironment environment, String key) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) throw new ProbeInputException("配置缺少有效的 " + key);
        return value.trim();
    }

    /** 相对证书路径与正式代码一样以 project.folder 为基准，也支持完整路径。 */
    private static String certificatePath(StandardEnvironment environment, Path folder, String key) {
        Path certificate = folder.resolve(required(environment, key)).toAbsolutePath().normalize();
        if (!Files.isRegularFile(certificate)) throw new ProbeInputException("证书文件不存在，请检查 " + key);
        return certificate.toString();
    }

    private static String safeText(String value) {
        return value == null ? "（未返回）" : value.replaceAll("[\\p{Cntrl}]", " ").substring(0, Math.min(value.length(), 200));
    }

    @FunctionalInterface
    interface ClientFactory {
        AlipayClient create(AlipayConfig config) throws Exception;
    }

    /** 仅测试用：只携带固定的配置提示，不携带 YAML 原文或 SDK 响应正文。 */
    private static final class ProbeInputException extends RuntimeException {
        private ProbeInputException(String message) { super(message); }
    }
}
