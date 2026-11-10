# 支付宝沙箱独立查单（仅用于测试）

入口：`easymall-web/src/test/java/com/easymall/diagnostics/AlipaySandboxQueryProbe.java`。

此类供人工诊断使用，不属于商城正式业务流程。它不启动 Spring Boot，不连接数据库或 Redis，不启动后台轮询，不修改商城订单状态。普通 Maven 测试也不会自动运行它的 `main`。

## 在 IDEA 中运行

1. 使用项目的 JDK 21，打开 `AlipaySandboxQueryProbe`，点击 `main` 左侧运行按钮。
2. 不填参数时，默认读取商城端 `src/main/resources/application-dev.yml`，打印实际 APPID、网关和证书模式，查询一次故意不存在的 `DIAG_QUERY_ONLY_001`。
3. 查询商城订单时，在该类运行配置的 **Program arguments（程序参数）** 中填写 `postOrder` 返回的 `payOrderId`。不要填子订单 `orderId`，也不要填沙箱买家账号。
4. 只检查本地配置和 SDK 能否加载证书时，在程序参数中填写 `--check`。此模式不会联网；它不代表支付宝已认可参数、私钥与证书一定匹配或交易存在。
5. 指定另一份完整配置时，可填写 `--config=D:/你的目录/web.yml`，也可与订单号、`--check` 一起使用。该文件需要提供 `project.folder` 和完整 `alipay` 证书配置；此工具不自动加载 Spring 的配置导入或其他 profile。

证书相对路径以 `project.folder` 为基准。私钥直接从现有 YAML/同名系统属性或环境变量读取，不要在测试类里另存一份，也不要将私钥复制到聊天或截图。

## 查看结果

输出 `code`、`msg`、`sub_code`、`sub_msg`、`trade_status`。代码只执行一次 `certificateExecute`，不循环、不主动重试、不打印签名或完整响应正文。

- `10000`：查单接口成功，继续看 `trade_status`；成功查单不等于已经付款。
- `40004 / ACQ.TRADE_NOT_EXIST`：当前调用身份下未找到该交易；故意不存在的测试订单出现此结果属于预期对照。
- `20000 / aop.ACQ.SYSTEM_ERROR`：查询失败，尚未确认支付状态；保留错误码用于对照。
- `测试未执行`：参数或本地配置检查失败，按提示处理。
- `测试未完成，异常类型=...`：本地加载或 SDK 调用异常；为避免敏感信息泄漏，工具不打印异常正文。

配套 `AlipaySandboxQueryProbeTest` 是自动化单元测试，使用合成 YAML 和模拟客户端，完全不访问真实支付宝。
