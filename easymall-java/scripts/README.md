# 阶段 0 验证脚本

以下命令在后端根目录执行。PowerShell 脚本要求 **PowerShell 7 或更新版本**，JDK 固定为 21。所有入口均不执行清理、清库、批量文件删除或工作区重置。

## 普通后端验证

```powershell
./scripts/verify-baseline.ps1 -JavaHome '<本机 JDK 21 目录>'
./scripts/verify-baseline.ps1 -JavaHome '<本机 JDK 21 目录>' -Package
```

不带 `-Package` 执行 test，带参数执行 package（包含测试）。脚本仅临时设置当前进程的 Java 环境，退出时恢复原值；默认 Maven 缓存位于工作区上级的 `runtime/phase0/maven-repository`。首次下载需要联网。

脚本要求 common、admin、web 都产生**本次调用更新的** Surefire XML，且三个模块实际执行数均大于 0（步骤 01 起增加 admin 检查）。历史报告、全部跳过或“没有测试”不能作为通过证据。普通测试不依赖运行中的数据库、Redis、ES、模型或支付。

步骤 05 的 MCP 启动修复起，mcp 模块也必须产生本次更新的测试报告并执行测试。新增回归使用 MCP 自身不含 Tomcat/Servlet API 的依赖环境，验证公共 Servlet 组件不会误加载；验证记录见 [MCP 启动修复](../docs/verification/05-mcp-startup-fix.md)。

## 独立联调准备

```powershell
./scripts/prepare-baseline-config.ps1
docker compose -p easymall-baseline --env-file ../runtime/phase0/baseline.env -f compose.baseline.yml up -d
docker compose -p easymall-baseline --env-file ../runtime/phase0/baseline.env -f compose.baseline.yml ps
./scripts/initialize-baseline-db.ps1 -PrivateEnvFile ../runtime/phase0/baseline.env
```

准备脚本生成随机的测试数据库密码、忽略提交的三份 local 配置，并使用本机模型替身地址；现有文件会保留。私密 env 放在被 Git 忽略的 runtime 中，不打印密码。Compose 只使用已存在的本地镜像，提供独立 MySQL 13306、Redis 16379、带 IK 的 ES 19200；不会更改开发实例或复用其卷。

数据库初始化**只运行一次**：它固定连接 `127.0.0.1:13306/easymall_test`，先检查库名和零表状态，再导入纯结构和合成 fixture，最后核对 16 表及 fixture 数量。非空库会拒绝执行；错误后不会自动清理或重复导入。再次运行普通测试或集成测试不需要重新初始化数据库。

## 显式集成烟测

先启动下方模型替身。步骤 02 起还需要运行本轮打包的管理端（6061），使用 prepare-baseline-config 生成的合成管理员与隔离数据配置；用于验证后台封禁能跨进程关闭 WebSocket。在设置了 JDK 21 的其他终端分别执行：

```powershell
# 完成普通 package 后，在单独终端启动，保持运行直到集成验收结束
java -jar ./easymall-admin/target/easymall-admin-1.0.0.jar --spring.config.location=file:./config/local/admin.yml --spring.profiles.active=baseline
```

确认 6061 与 WebSocket 6051 没有其他项目占用；不得对已运行的日常开发服务执行本集成测试。

```powershell
$baselineRepo = Join-Path (Split-Path (Get-Location).Path -Parent) 'runtime/phase0/maven-repository'
mvn "-Dmaven.repo.local=$baselineRepo" -Dmaven.test.skip=false -DskipTests=false -Pbaseline-integration -pl easymall-web -am verify
```

该 Profile 启用 `BaselineSmokeIT`，普通 `test/package` 不运行它。它在创建应用 Bean 前检查数据库、Redis、ES、模型地址以及可覆盖这些地址的配置，仅允许隔离实例。支付由 Mockito 替换，工具 Provider 是始终返回空数组的测试实现，MCP 客户端传输在测试中关闭。

测试使用实际隔离 MySQL/Redis/ES，完成匿名浏览、验证码登录、ES 搜索、提交订单、本人订单查询、AI 消息保存及成功后的业务关闭与库存恢复。保留合成订单、消息和日志作为证据，不删除记录。断言失败时不使用 SQL 重置；应先检查失败原因和遗留状态。当前取消接口写入状态 5 的既有行为由基线记录，未在阶段 0 修复。

步骤 02 另覆盖默认鉴权、真实 Redis Lua 防会话复活、Token 退出/过期、真实 WebSocket 撤销、Admin 跨进程封禁和 A/B 订单/地址/评论隔离。测试仅操作已守卫的合成用户与 fixture；新增完成态订单/评价/地址保留作为证据，封禁测试在 finally 中解封并撤销会话。完整对比与限制见 [步骤 02 验证记录](../docs/verification/02-authentication-boundary.md)。

步骤04起，已有测试库还需先执行密码字段扩容脚本，已有管理员local配置需运行convert-admin-password.ps1迁移为admin.password-hash；新的prepare配置已使用合成哈希。登录表单统一提交原密码。完整集成增加旧账号升级、新注册、改密撤销Token/真实WebSocket、验证码并发消费和登录限速验证。开发库迁移、工具参数及页面验收见[步骤04记录](../docs/verification/04-password-migration.md)。

结束后在模型和合成管理端终端按 Ctrl+C，使用以下命令停止本次测试容器并保留所有卷和数据：

```powershell
docker compose -p easymall-baseline --env-file ../runtime/phase0/baseline.env -f compose.baseline.yml stop
```

## 本地模型替身

`mock-model-server.mjs` 使用 Node.js 内置 HTTP 服务，固定监听 `127.0.0.1:18080`，没有第三方依赖，也不调用任何外部接口。

在后端根目录启动：

```powershell
node ./scripts/mock-model-server.mjs
```

`prepare-baseline-config.ps1` 已写入固定测试值。若手动从 `web.example.yml` 建立配置，则在运行 Web 的终端设置：

```powershell
$env:AI_BASE_URL = 'http://127.0.0.1:18080'
$env:AI_API_KEY = 'baseline-placeholder'
$env:AI_CHAT_MODEL = 'baseline-chat'
$env:AI_EMBEDDING_MODEL = 'baseline-embedding'
$env:AI_VECTOR_DIMENSIONS = '1024'
```

默认接口路径为 `/v1/chat/completions` 和 `/v1/embeddings`，与本地配置样例一致。不要把真实模型地址或 API Key 填进替身配置。

| 接口 | 固定行为 |
|---|---|
| `GET /health` | 返回合成标记、支持的模型名、维度和聚合请求计数；没有请求正文或凭据 |
| 非流式 `POST /v1/chat/completions` | 固定输出 `{"intent":"CHAT","data":null}`，供当前 `UserIntent` 解析和 CHAT/FAQ 分支使用 |
| 流式 `POST /v1/chat/completions` | 返回带“阶段0合成测试回复”标记的固定 SSE 正文、`finish_reason=stop` 和 `[DONE]`；不返回工具调用 |
| `POST /v1/embeddings` | 每条输入返回相同的非零 1024 维向量，每项为 `1/32`，L2 范数为 1；支持 float/base64 格式 |

请求仅接受模型 `baseline-chat` / `baseline-embedding`，单个 JSON 请求体上限为 1 MiB，embedding 每次最多 128 条。服务不输出请求、Authorization、聊天内容或异常详情。`usage=null` 表示没有真实模型 Token 测量，不能据此报告模型成本或效果。

可用于验证当前 Java 客户端调用、`UserIntent` JSON 解析、FAQ 检索调用、SSE 消费、消息保存与 WS 投递链路；数据库、Redis、ES、MCP 等业务依赖仍需分别准备并隔离。当前 CHAT 分支仍会向量检索并附加工具定义，但本替身永远不生成 `tool_calls`，不会请求执行工具。

此替身不判断用户问题、不生成真实语义向量、不根据知识库组织回答；它固定选择 CHAT，不覆盖商品搜索/订单意图分类、真实检索质量、推荐准确性、模型异常恢复、工具权限、支付退款正确性或真实 AI 效果。整条链路是否通过仍需保存实际运行证据。

在替身终端按 Ctrl+C 停止。此说明提供启动方式；脚本新增和语法检查不代表服务已经启动或业务流程已经验收。
