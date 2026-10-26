# 步骤 02 补充修复：前端 WebSocket 尾斜杠兼容

- 日期：2026-10-26（Asia/Shanghai）。
- 现象：用户发送“你是谁”后，消息区持续等待，没有展示 AI 回复。
- 范围：仅修复 WebSocket 握手端点兼容性，保留已有 Token、用户状态及撤销检查。

## 原因与证据

用户提供的运行日志显示，模型已生成完整回复，消息经 Redis Topic 发布，数据库消息状态也已更新为完成。与此同时，WebSocket 共出现 6 次连接后迅速断开的记录，未形成可接收推送的有效会话。

前端 `.env.development`、`.env.production` 均配置 `VITE_WS="ws://localhost:6051/ws/?token="`，实际请求路径为 `/ws/`。步骤 02 新增的 HandlerTokenValidation 只允许 `/ws`，因此在身份认证前就返回 403，导致页面一直等不到回复。

这是本次改造遗漏的兼容性问题。此前有效握手单测和隔离集成客户端都使用 `/ws?token=...`，没有覆盖实际前端的 `/ws/?token=...`。不归因于模型无响应、提示词或 Redis 发布失败。

## 修改

- HandlerTokenValidation 精确接受 `/ws` 和 `/ws/`；不使用任意前缀放行。
- 空 Token、重复 Token、无效 Token、`/ws/other`、`/ws//` 等继续拒绝。
- 有效握手回归同时覆盖两种路径；隔离集成客户端改用现有前端实际的带尾斜杠地址。
- 增加 WebSocketEndpointCompatibilityTest：真实本机随机端口、实际 HTTP/WebSocket 编解码器、Mock 身份服务，验证两种路径均成功升级、实际私有回复送达、撤销后停止投递并断开，以及无效 Token/额外路径握手 403。
- 保留用户本轮自行补充的中文注释和连接日志，不回退其他修改。

## 验证

修改前 WebSocketAuthenticationTest 共 15 项，14 通过、1 失败；失败项正是有效 Token 使用 `/ws/` 的握手。修改后 15 项全部通过。

最终严格全模块 package 通过，共 147 项回归测试，失败/错误/跳过均为 0，耗时 34.342 秒；其中 4 项真实本机网络传输测试全部通过。common 58、admin 15、web 74；common/admin/web/mcp 四模块均打包成功。

最终全量回归和打包结果见 [02-websocket-path-results.json](02-websocket-path-results.json)。原始日志位于工作区根 `runtime/phase1/step02/ws-path-fix/`：`before.log`、`focused-after.log`、`package-after.log`。

本轮未调用真实模型或支付、未读写开发数据库、未启停用户正在运行的服务，也未为测试占用 6051。此前完整隔离的 7 项数据库/Redis/Admin 集成未在本轮重跑，新增真实网络回归独立验证实际端点协议与私有回复投递。

## 本地生效

用户当前已运行的 Web JVM 不会因为修改 Java 源码就自动加载新逻辑。重新启动 IDEA 中的 EasyMallWebApplication，再刷新 AI 页面以建立新连接。此前日志显示回复已经保存，历史消息接口可以读取该回复。
