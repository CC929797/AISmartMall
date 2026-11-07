# 步骤07：登录、请求错误与 WebSocket 测试案例

日期：2026-11-07。测试采用合成账号、模拟 HTTP/WS 和虚拟时钟，不调用真实模型、支付或数据库。

## 自动化测试

在前端仓库根目录执行（两个前端的依赖均需已安装）：

```powershell
cd D:\java\java24\LLstudy\mall\easymall-front
node --test tests/account-protocol.test.mjs tests/stability-*.test.mjs easymall-front-web/tests/*.test.mjs easymall-front-admin/tests/*.test.mjs
```

最新结果：99 项通过，0 失败。其中账号/退出交互8项、页面初始化7项、连接管理5项、Worker9项、请求18项、真实加载组件10项、既有DOM安全42项。后台登录后遮罩残留和退出需两次均已补充修复和复验。

只运行步骤07新增稳定性用例：在任意一个前端项目目录执行 `npm run test:stability`（49项）。账号与退出交互执行 `npm run test:account`（8项）。

| 文件 | 主要覆盖 |
|---|---|
| `account-protocol.test.mjs` | 登录输入和注册切换、原密码提交、管理员凭据、改密后清理、从私有页退出的更新顺序 |
| `stability-layout.test.mjs` | 无凭据、有效/过期凭据、网络空结果和异常、公开/私有页、迟到响应覆盖保护 |
| `stability-request.test.mjs` | 两端901/401/403、超时/413、无config错误、关闭提示、JSON/表单/文件、并发加载层、旧请求保护 |
| `stability-manager.test.mjs` | 重复初始化、切换凭据、退出和重建、Worker构造/运行错误、手动重试、旧Worker消息隔离 |
| `stability-worker.test.mjs` | 构造失败、连接超时、error/close去重、五次退避上限、主动关闭、重新初始化、坏消息、心跳失败 |
| `stability-loading.test.mjs` | 真实Element Plus遮罩DOM移除、滚动恢复、五轮并发、错误分支、其他任务隔离、热更新与旧请求竞争 |

测试加载实际源码和 Vue 组件。网络层使用真实 Axios 加模拟 adapter；Worker/manager 使用模拟浏览器传输和时钟，不复制待测实现。

## 隔离浏览器验收

```powershell
cd D:\java\java24\LLstudy\mall\easymall-front
node tests/browser-stability-server.mjs
```

打开 `http://127.0.0.1:16070/?__case=guest`。使用桌面窗口，项目现有内容宽度为1300px；本次验收尺寸1440×1000。底部验收栏只来自测试服务器，不会进入正式构建。

脚本使用真实用户端组件，替换API与WS。合成身份只保存在该独立本机来源中。`ws` 是该脚本的直接开发依赖；无需启动 Java 服务。验收结束在运行脚本的终端按 Ctrl+C。

| 编号 | 操作 | 预期结果 | 本次验证 |
|---|---|---|---|
| 01 | 游客首页打开登录，连续输入邮箱和密码；切换注册并输入昵称 | 输入保持；注册模式不会跳回登录 | 组件测试＋浏览器通过 |
| 02 | 点击“过期凭据首页” | 首页出现“热门商品”和空数据状态；显示登录入口，失效Token被清理 | 组件/请求测试＋浏览器通过 |
| 03 | 打开 `http://127.0.0.1:16070/agent?__case=expired` | 引导登录；关闭弹窗后仍有“登录后即可查看此页面”和重新打开按钮 | 组件测试＋浏览器通过 |
| 04 | 点击“网络失败首页” | 首页仍能显示；不会因网络失败清理尚未判定失效的Token | 组件/请求测试＋浏览器通过 |
| 05 | 点击“已登录 AI” | 有效凭据恢复身份，AI页面不提前弹登录框；建立一个业务连接 | 组件/manager测试＋浏览器通过 |
| 06 | 已连接时点击“断开连接” | 显示重连状态，约2秒后恢复；统计连接尝试增加、活动连接恢复为1 | Worker测试＋浏览器通过 |
| 07 | 点击“服务离线”，输入问题并按Enter | 显示等待连接恢复提示；输入保留，发送请求数不增加 | 浏览器通过 |
| 08 | 保持离线约30秒 | 连续失败最多重试5次；出现失败提示和“重新连接”，不无限重试 | Worker测试＋浏览器通过 |
| 09 | 点击“服务恢复”，再点击页面中的“重新连接” | 恢复连接，失败提示消失 | manager测试＋浏览器通过 |
| 10 | 点击“发送坏消息”，随后输入问题并按Enter | 坏消息被丢弃，仍显示“隔离测试回复：连接和消息接收正常。” | Worker测试＋浏览器通过 |
| 11 | AI页头像菜单→退出登录→确定；稍后刷新统计 | 回游客首页，不误弹登录框；活动连接为0，连接尝试数不再增加 | 账号/Worker测试＋浏览器通过 |
| 12 | 再次点击“已登录 AI” | 使用合成登录重新建立连接，旧Worker不处理消息 | manager测试＋浏览器通过 |
| 13 | 自动化模拟401/901和403 | 前两者撤销当前会话并引导登录；403提示权限不足并保留会话 | 两端请求测试通过 |
| 14 | 自动化模拟无config网络错误、超时、413、静默请求 | 不二次抛异常，按类型提示，`showError:false`生效 | 两端请求测试通过 |
| 15 | 自动化模拟旧请求在新登录后返回901；自动登录在退出后才成功 | 不撤销新登录，不复活旧登录 | 请求/组件测试通过 |
| 16 | 自动化提交嵌套JSON、数组表单、文件和两个并发请求 | DTO结构/文件保持；全部请求结束才关闭加载层 | 两端请求测试通过 |
| 17 | 自动化使用真实加载组件并反复完成多轮请求；请求期间热更新 | 请求结束后遮罩DOM为0，恢复原滚动状态；旧请求不关闭新遮罩 | 10项真实组件测试通过 |
| 18 | 后台右上角退出→确定，随后点验收栏“刷新退出统计” | 第一次确认即到登录页；退出请求1次、凭据已清除；同时兼容旧空响应和新JSON | 组件/请求测试＋两种响应浏览器通过 |

后台加载遮罩可独立复验：执行 `node tests/browser-admin-loading-server.mjs`，打开 `http://127.0.0.1:16072/home`，多次点击底部“模拟登录跳转”。首页请求完成后应显示“遮罩数量：0，滚动锁：false”，页面可正常操作。该脚本使用真实管理端首页和模拟API，不需要真实管理员凭据。

同一脚本默认模拟旧管理端logout的HTTP200空响应；使用`node tests/browser-admin-loading-server.mjs --json-logout`模拟新的成功JSON。两种模式下都应满足案例18。额外组件用例覆盖重复确认只发一次、退出业务失败保留凭据并允许重试、旧退出响应不撤销后来的新登录。

登录/注册真实提交及真实后端身份、MySQL/Redis、真实模型未在这个模拟浏览器环境中联调。现有组件测试验证提交协议；实际账号流程可按01—05、11—12在正常开发环境复验。不要在开发环境注入测试服务器中的合成Token。

## 构建

为保留现有dist，直接运行Vite并关闭输出目录清空：

```powershell
cd D:\java\java24\LLstudy\mall\easymall-front\easymall-front-web
node node_modules/vite/bin/vite.js build --outDir D:/java/java24/wokspach-teach/runtime/phase2/step07/frontend-web --emptyOutDir false

cd D:\java\java24\LLstudy\mall\easymall-front\easymall-front-admin
node node_modules/vite/bin/vite.js build --outDir D:/java/java24/wokspach-teach/runtime/phase2/step07/frontend-admin --emptyOutDir false
```

## 边界

本步恢复的是基础连接。断线期间已产生的AI结果补拉、消息路由/归属和完整流式续传按路线图步骤16处理。断线发生在回复过程中时，不自动重发问题，避免产生重复任务。
