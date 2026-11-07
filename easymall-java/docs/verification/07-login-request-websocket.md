# 步骤07：登录初始化、请求错误与基础WebSocket重连

- 日期：2026-11-07（Asia/Shanghai）。
- 状态：实现与隔离自动化/浏览器验收完成；未提交Git。
- 首轮范围：用户端、管理端请求封装和前端测试；退出补充修复另涉及AdminController响应契约，未修改数据库或支付配置。
- 详细测试案例：[前端测试说明](D:/java/java24/LLstudy/mall/easymall-front/tests/README-step07.md)。
- 最新补充：管理端logout空响应导致退出需两次已修复，最新99项前端、17项后端定向测试及两种响应的浏览器退出复验通过，见[退出修复记录](07-admin-logout-fix.md)。
- 补充修复：后台登录后真实加载遮罩残留已复现并修复，最新93项测试、两端构建及管理端隔离浏览器复验通过，见[加载层修复记录](07-loading-overlay-fix.md)。下文83项为首次验收记录。

## 实际改动

| 环节 | 改造前 | 改造后 |
|---|---|---|
| 自动登录 | 请求失败提前返回，haveInit一直为false | finally完成初始化；公开页可显示，私有页有登录说明和入口 |
| 无有效会话 | autoLogin返回success/null后本地旧Token保留 | 清理凭据、身份、旧连接；临时网络失败不误撤销凭据 |
| 状态竞争 | 自动登录/旧901可能覆盖后来的登录或退出 | 比较发起请求时的Token；旧响应不撤销新登录、不复活旧会话 |
| 退出/改密 | 状态清理分散，旧连接依赖页面卸载；退出延迟100ms清理 | Store统一saveSession/clearSession，先保存Token再展示私有组件；退出先离开私有页再清身份 |
| 请求错误 | 401/403统称网络错误；网络失败忽略showError；加载层可能提前关闭 | 401/901清会话，403保留会话并提示权限不足；超时/413单独提示；finally与并发计数管理加载层 |
| 请求体 | 已支持JSON、表单和上传 | 保留JSON DTO、数组表单、文件与回调契约，补回归验证；没有再造JSON封装 |
| Worker失败 | 构造失败被isConnecting卡住；error/close重复计数 | 构造、错误、关闭、10秒超时、心跳失败统一清理；单连接只调度一次重试 |
| 重试 | 退出后Worker重新init不恢复；缺少终态 | 初次连接后最多5次连续失败重试，延迟2/3/4.5/6.75/10秒；失败终态支持手动重试 |
| 消息 | 直接JSON.parse，无协议检查 | 解析保护、对象/messageId/outPutType/文本字段校验；坏消息不影响后续消息 |
| 生命周期 | manager重复创建Worker，旧回调可能继续到达 | 同Token初始化复用；切换Token/退出销毁旧Worker；旧事件失效 |
| 页面反馈 | 用户看不到连接终态 | 重连/失败提示和重新连接按钮；未连接时保留输入并阻止新建AI任务 |
| 依赖 | mitt靠其他依赖间接带入 | 两端直接声明mitt 3.0.1；浏览器测试直接声明已安装的ws 8.22.1开发依赖 |

阶段一已经完成的Account.vue watch初始化、后端相同Token条件续期、JSON提交与凭据日志脱敏均保留。未整体迁移TypeScript或认证体系。

## 验证结果

83项全部通过：账号/退出交互4项、Layout7项、manager5项、Worker9项、两端请求16项、既有DOM安全42项。测试使用真实组件/源码、真实Axios与模拟传输、虚拟时钟；不调用真实模型或支付。

```powershell
cd D:\java\java24\LLstudy\mall\easymall-front
node --test tests/account-protocol.test.mjs tests/stability-*.test.mjs easymall-front-web/tests/*.test.mjs easymall-front-admin/tests/*.test.mjs
```

修改前先执行请求/Worker探针，复现了构造失败不安排重试、重复计数、缺少终态、坏JSON未捕获、会话清理等缺陷。早期跨VM数组检测暴露的`instanceof Array`差异最终用Array.isArray消除；不把所有探针失败都视作浏览器缺陷。

两端Vite生产构建通过，写入独立目录，使用`--emptyOutDir false`；没有删除或覆盖原dist。PowerShell中的npm参数转发最初丢失了选项，因此采用测试说明中的直接node/Vite命令完成验证。首次普通沙箱测试进程遇到spawn EPERM，获准后完成测试；不存在未解决的构建或测试失败。

浏览器使用computer-use技能，真实用户端配合本机16070端口的模拟API/WS，桌面尺寸1440×1000。验证公开页过期/网络失败、私有页登录提示、输入与注册切换、断线自动恢复、最多五次重试、手动恢复、坏消息后正常回复、离线发送保留输入、退出关闭连接、重新登录重建。浏览器发现退出误弹登录框后修正顺序并增加组件回归，复测通过。

本地证据（运行产物，不加入业务源码）：

- `runtime/phase2/step07/frontend-tests.tap`：最终83项测试。
- `runtime/phase2/step07/build-web.log`、两端`frontend-web/`与`frontend-admin/`构建产物。
- `runtime/phase2/step07/browser-retry-exhausted.jpg`：达到重试上限，仍保留输入。
- `runtime/phase2/step07/browser-reconnected.jpg`：坏消息后能显示正常模拟回复。
- `runtime/phase2/step07/browser-logout.jpg`：退出后活动连接0、尝试次数保持不变。
- `runtime/phase2/step07/browser-private-login.jpg`：过期会话的私有页面登录入口。

浏览器验收服务已停止，临时浏览器页已关闭，视口已恢复。缓存保存在被Git忽略的node_modules/.vite-step07；没有删除任何文件或目录。

## 范围与后续

本轮没有重新执行Java全量回归，也未联调真实登录数据库、真实AI、支付或退款。HTTP/WS替身浏览器验收不替代这些后端集成验证。

步骤16负责会话消息路由、消息归属和断线结果补拉。本步不承诺断线期间的回复自动补全，也不自动重新发送问题。现有固定1300px布局未进行移动端改造。

按路线图下一步先实施11A可靠任务基础设施，再进入08下单幂等。
