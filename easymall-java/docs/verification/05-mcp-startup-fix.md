# 步骤 05 补充：MCP 启动兼容性修复

- 日期：2026-11-02（Asia/Shanghai）。
- 状态：修复、四模块测试与打包、MCP 真实启动和初始化握手均已通过。
- 本次没有提交 Git，没有修改用户的私人配置或 simulated 选择。

## 1. 原因

用户日志表面显示 PropertyPlaceholderAutoConfiguration 条件处理失败，根因是公共模块的 ImageMultipartConfig 被 MCP 扫描，反射读取其 Tomcat 方法签名时缺少 org.apache.catalina.connector.Connector。

MCP 使用 WebFlux/Netty，依赖中排除了 Servlet Web Starter。步骤 05 的公共图片组件没有限制加载环境，这是本次遗漏的兼容性问题。继续复核还发现 FileUtils 的 HttpServletResponse 签名和 MVC 的 RequestInputAdvice 需要一并与 MCP 隔离。

此前步骤 05 的 471 项普通测试及 11 项集成主要验证 Common/Admin/Web 功能和打包，没有覆盖此次 MCP 启动问题。原验证结果保留，本页补充新的回归和实际 MCP 启动证据。

## 2. 修改

1. ImageMultipartConfig 在配置类级别要求 Servlet 环境及 Tomcat 类存在，避免缺失类在方法反射阶段才被解析。
2. FileUtils 和 RequestInputAdvice 仅在其依赖的 Servlet/MVC 环境注册。Web/Admin 的上传、超限处理及绑定规则继续生效。
3. MCP 主配置的 web-application-type 移到正确的 spring.main 层级；MCP 继续使用 WebFlux/Netty。
4. 为 MCP 增加测试作用域依赖与 4 项自身类路径隔离测试，运行时没有增加 Tomcat 或 Servlet API。
5. Common 增加 3 项测试，覆盖非 Web、缺少 Tomcat 和 Servlet/Tomcat 正常环境，验证 10 MiB 有界收尾配置保留。
6. verify-baseline.ps1 纳入 MCP 模块的新报告，避免遗漏其测试数量和执行状态。
7. 模拟物流关闭测试改为显式设置 disabled，不再依赖 AppConfig 的字段初始值；保留用户已配置的 simulated 行为。

类级别条件隔离方式参考 [Spring Boot ConditionalOnClass](https://docs.spring.io/spring-boot/3.5/api/java/org/springframework/boot/autoconfigure/condition/ConditionalOnClass.html)。该文档明确说明，依赖缺失类的方法可能在方法级条件生效前已触发类加载，因此本次条件放在配置/组件类上。

## 3. 验证

原始日志目录：工作区根 runtime/phase1/step05/mcp-startup-fix/。

| 验证 | 实际结果 |
|---|---|
| 修复前 MCP 回归 | 4 项中 3 项失败，复现 Connector、HttpServletResponse 缺失及 Servlet 组件误加载；before.log |
| 最终普通回归 | 478 项，Common 313、Admin 28、Web 133、MCP 4；失败/错误/跳过均为 0 |
| 四模块 package | 全部成功，30.734 秒；package-final.log |
| 缺少 Tomcat 的 MCP 类路径 | 没有 Connector 或 Servlet API，4 项回归全部通过 |
| Servlet 下原功能 | 10 MiB 收尾配置、原有图片处理与绑定测试继续通过 |
| MCP 实际启动 | 最新 JAR 在隔离数据配置和本机 18084 端口启动，日志显示 Netty 启动及业务初始化完成 |
| MCP 初始化 | POST /mcp 返回 HTTP 200、serverName=easymall-mcp、protocolVersion=2025-03-26；initialize.json |
| 业务调用 | 仅执行协议初始化，未执行下单、退款或其他工具 |
| 收尾 | 本轮 MCP 进程和隔离容器已停止，未删除数据、卷或容器；原开发 Redis/ES 保持运行 |

本次没有重新运行先前 11 项 Web HTTP/WS 集成或前端构建；改动涉及服务端组件加载，已通过上述普通回归、Servlet 配置断言及 MCP 真实启动验证。

## 4. 用户复验

重新运行 IDEA 中的 EasyMallMcpApplication，正常端口仍是你配置的 8084。应看到 Netty 与 EasyMallMcpApplication 启动成功，不再出现 Connector 缺失错误。若 IDEA 提示 Maven 依赖发生变化，重新加载 Maven 项目以识别新增测试依赖。

真实数据库、Redis 等连接仍使用用户自己的开发配置；本轮真实启动验证仅连接阶段 0 的隔离端口。
