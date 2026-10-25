# 步骤 00：环境与测试基线

- 验收日期：2026-10-25（Asia/Shanghai）。
- 状态：阶段 0 基线范围已完成；后续安全、交易、索引和 AI 效果改造仍按路线图推进。
- 范围：构建配置、测试入口、配置隔离、纯结构快照、合成 fixture、独立联调和执行记录；没有修改现有业务 Java / Mapper / 前端源码。
- 结构报告：[00-database-schema.md](00-database-schema.md)。机器可读结果：[00-results.json](00-results.json)。
- 操作说明：[环境与启动](../环境与启动.md)、[脚本说明](../../scripts/README.md)。

## 1. 修改前状态与保留的内容

| 仓库 | 开始时 Git 状态 | 本次处理 |
|---|---|---|
| 后端所在仓库 | 仅 `?? easymall-java/docs/`，路线图尚未跟踪；未发现 Service 未提交修改 | 保留原文与业务实现，只更新阶段 0 的事实和进度 |
| 前端仓库 | `.idea/vcs.xml` 已修改；两个 `dist/` 已存在且未跟踪 | 保留；本次构建写到另一目录，不清空 dist |

路线图上一轮登记的 Service 未提交补丁已不出现在当前 Git 状态里，不能继续把它当成本次要覆盖的改动。原开发 MySQL 仅执行结构元数据查询和 `mysqldump --no-data`，没有复制业务行或修改原库。现有开发 Redis 6379、ES 9200 在验证后仍运行。

没有执行文件/目录批量删除、清库、索引清空、工作区重置、Git 暂存或提交。

## 2. 已建立的入口与隔离

1. 父 POM 恢复默认执行测试；common、web 引入由 Spring Boot 管理的 test starter。
2. MCP 移除 Spring AI 1.1.0 覆盖，继承父 BOM 1.1.2；Web 移除重复 OpenAI starter。保留 Boot 3.5.4 和 Java 21。
3. Surefire 显式选择 `test` Profile 和 `config/test/application-test.yml`，避免默认加载 dev。普通测试不创建完整应用上下文；配置测试只解析 ConfigData，不实例化业务 Bean。
4. `verify-baseline.ps1` 选择 JDK 21、复用工作区 Maven 缓存；只统计本次调用更新的报告，要求 common、web 均实际执行测试。PowerShell 要求 7+。
5. `compose.baseline.yml` 建立独立项目 `easymall-baseline`：MySQL 13306 / `easymall_test`、Redis 16379、ES 19200，均绑定本机且使用新卷。现有 Redisson 不读取数据库编号，因此使用专用 Redis 实例隔离其全部 key、队列和锁。
6. 从原开发库只读导出 16 表的纯结构，去除原始自增计数，不复制触发器或业务数据；仅导入全新的测试库，再插入明显合成的用户、分类、商品、SKU 和地址。
7. 随机数据库密码保存在被忽略的 `runtime/phase0/baseline.env`，三份实际 local 配置也被忽略。样例仅含环境变量与占位符。
8. `baseline-integration` 是显式 Failsafe Profile。创建 Bean 前检查实际连接配置，拒绝 Hikari/Redis 拓扑/模型子地址等覆盖；模型只能调用本地替身，支付使用 Mockito，工具 Provider 返回空数组。
9. 新建 `db/migration/`、`evals/` 的使用约定；没有启用自动迁移或伪造 AI 评测样本、指标。

`test` 配置文件本身不能安全启动任意整应用测试：现有业务包含消费者、调度、ES 初始化及 Netty 启动副作用。显式 IT 使用实际独立实例，普通测试使用替身，两种入口分开。

## 3. 实际环境

| 项目 | 本次实测 |
|---|---|
| Java / Maven | Microsoft JDK 21.0.11 / Maven 3.6.3 |
| PowerShell | 7.6.5 |
| Node / npm / Vite | 22.15.0 / 10.9.2 / 实际解析 7.3.1 |
| 原库结构来源 | MySQL 8.0.43，仅结构读取 |
| 独立测试 MySQL | 8.4.9，16 张表，合成 fixture |
| 独立 Redis | 8.2.0 |
| 独立 ES / IK | 均为 9.2.1 |
| Docker 引擎 | 29.7.2；使用本机已有镜像，Compose 不隐式拉取 |

Java/Maven 环境只在本次进程设置，没有修改全局设置。首次 Maven 依赖缺失且受限环境无法解析仓库域名，获准联网下载到工作区缓存后构建成功。受限环境中的 Mockito JVM 附加曾等待，终止该次运行后在允许本机 JVM 附加的执行环境验证成功，没有通过跳过测试规避问题。

## 4. 验证命令与结果

以下后端命令在 `D:\java\java24\wokspach-teach\easymall-java` 执行；均未使用 clean。

```powershell
# 首次实际执行普通测试
$env:JAVA_HOME = 'C:\Users\CC_Am\.jdks\ms-21.0.11'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn -B -ntp -Dmaven.repo.local=D:\java\java24\wokspach-teach\runtime\phase0\maven-repository -Dmaven.test.skip=false -DskipTests=false test

# 最终打包入口，同时检查本轮报告，不读取历史报告充数
./scripts/verify-baseline.ps1 -JavaHome 'C:\Users\CC_Am\.jdks\ms-21.0.11' -Package

# 独立服务与本地模型替身准备好后，显式执行完整烟测
mvn -B -ntp -Dmaven.repo.local=D:\java\java24\wokspach-teach\runtime\phase0\maven-repository -Dmaven.test.skip=false -DskipTests=false -Pbaseline-integration -pl easymall-web -am verify
```

| 验证 | 结果与边界 |
|---|---|
| 普通后端测试 | 10 项，失败 0、错误 0、跳过 0：配置 1、分页 4、商品 HTTP/响应 5；Admin/MCP 目前没有自己的单测 |
| 全模块 package | SUCCESS；最终脚本输出 `Baseline passed: 10 tests executed.`，用时 7.647 秒（已缓存依赖） |
| 显式完整烟测 | 1 项完整流程，失败 0、错误 0、跳过 0；最终 verify 用时 33.062 秒 |
| MCP JAR | 实际启动；独立配置、响应式服务、8084 `/mcp` initialize HTTP 200；没有调用写工具 |
| Admin JAR | 实际启动；6061 `/api/account/checkCode` HTTP 200 |
| Web JAR | 实际启动；6050 `/api/product/loadCategory` HTTP 200；日志确认独立 Netty 6051 启动 |

两个前端分别在各自目录执行，使用新输出目录并明确关闭清理：

```powershell
# easymall-front-web
npm run build -- --outDir D:/java/java24/wokspach-teach/runtime/phase0/frontend-web --emptyOutDir false

# easymall-front-admin
npm run build -- --outDir D:/java/java24/wokspach-teach/runtime/phase0/frontend-admin --emptyOutDir false
```

两者 exit 0。用户端转换 3699 模块、用时 25.07 秒；管理端转换 4266 模块、用时 29.40 秒。产物 index 的 SHA-256 已记在 `00-results.json`。本次没有执行浏览器点击验收；前端构建成功不能替代页面交互验证。

## 5. 完整烟测实际覆盖

| 顺序 | 实际检查 |
|---|---|
| 匿名浏览 | 分类、分类商品列表、商品详情返回合成商品 |
| 登录 | 专用 Redis 写入本次验证码，通过真实 HTTP 验证当前 MD5 登录协议，验证码使用后失效 |
| 搜索 | 从测试 DB 同步合成商品到测试 ES，刷新并确认按 ID 存在，小写 `baseline` HTTP 搜索命中 |
| 下单 | HTTP 提交单 SKU；真实数据库写订单/明细/物流及扣库存；支付替身返回固定标记，无渠道请求 |
| 本人订单 | 同时核对数据库金额、明细数量、待付款状态与 HTTP 本人订单列表/详情 |
| AI 问答 | 实际 ChatComponent → 本地意图 JSON → FAQ 向量查询 → 合成 SSE → 消息完成落库 → HTTP 历史查询；没有执行工具 |
| 业务恢复 | 通过当前取消接口处理本次订单，核对实际状态 5、支付替身 closeOrder 调用和库存恢复；保留订单证据 |

最终本地模型替身计数：意图响应 1、流式响应 1、embedding 请求 2、错误 0。所有向量与答复均为固定合成数据，未调用真实模型，未测量真实 Token 或推理成本。WS 服务启动有日志，但没有验证浏览器接收、断线重连或消息竞态。

首次 IT 启动发现测试工具 Provider 在 `@BeforeEach` 之前被访问，已改为启动阶段始终返回空数组的测试配置。第二次发现既有大小写搜索差异；保留失败日志及独立 ES 对比证据，正常烟测使用可命中的小写关键词，没有修改搜索业务或隐藏已知缺陷。最终完整流程通过。

## 6. 缺陷与后续工作清单

| 现状 | 证据 / 后续范围 |
|---|---|
| 英文大小写搜索漏召回 | 同一合成商品，专用 ES `*Baseline*` 命中 0，`*baseline*` 命中 1；步骤 19 处理检索归一化与覆盖 |
| 用户取消实际写 CLOSED(5) | 当前 Service 忽略传入的 CANCELLED(4)，IT 复现；交易状态步骤中统一语义 |
| 应用退出后消费者继续重试 | 成功 IT 关闭上下文时出现 `RedissonShutdownException` 重试日志；可靠任务和生命周期步骤处理，不代表本次断言失败 |
| Redis / 商品索引名固定 | 当前以独立实例隔离；后续如共用实例，需真正配置化并补 Redisson 一致性，不能只改 database 编号 |
| 密码字段与约束 | password 为 varchar(50)，新编码迁移需扩容；无 FK/CHECK 的本次结构事实及索引清单见结构报告 |
| 部分原配置不完整 | MCP 原 `server.main` 拼写无效；本次外部配置正确放在 `spring.main`。默认 dev 配置没有作为本次联调入口 |
| 前端体积 | 当前存在约 1.65 MB / 2.08 MB 的 JS 产物，后续步骤 26 再做分析；本次没有放宽警告阈值 |

鉴权、敏感字段、输入校验、密码升级、真实支付/退款、并发/幂等、RAG 一致性和真实 AI 效果仍未由本步验收。后续按步骤增加测试与证据，不能据此宣称全系统安全或金融链路正确。

## 7. 证据与结束状态

- `00-results.json`：脱敏计数、版本、前端产物哈希和验证边界。
- `00-database-schema.md`、`db/baseline/00-schema.sql`：实际结构事实与纯结构快照。
- `target/surefire-reports/`、Web 的 `target/failsafe-reports/`：实际 XML 报告，被 Git 忽略。
- 工作区根 `runtime/phase0/`：构建日志、失败尝试、三个后端启动日志、结构元数据、模型健康计数及前端产物，均不提交。

验证结束时只停止了本次记录并核对身份的后台进程与 `easymall-baseline` 容器；配置、合成数据、容器和卷均保留，没有删除任何文件或容器。后续可重新 `up -d`、启动模型替身再运行显式 IT；不要重新向非空库导入 fixture。

- [x] 三个后端和两个前端启动方式有说明，后端 JAR 已实际启动。
- [x] 普通测试不依赖真实模型或支付；显式 IT 同样使用替身。
- [x] 后端测试实际执行且数量大于 0。
- [x] 两个前端构建有记录，原 dist 保留。
- [x] 已有改动登记且未覆盖；无批量删除；文档与实际结果同步。

下一步：按路线图步骤 01 收口用户 CRUD 与安全响应模型。
