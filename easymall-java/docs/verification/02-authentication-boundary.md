# 步骤 02：默认鉴权与统一当前用户

- 日期：2026-10-26（Asia/Shanghai）。
- 修改前代码基线：`fff0175`（步骤 01 提交）。开始时仅路线图、环境说明两个 Markdown 未跟踪，原内容保留。
- 本次不自动暂存或提交 Git；不删除文件、容器、数据卷或数据库 fixture，不清空 Redis 或索引；撤销/过期合成 Token 属于必要的业务测试。
- 本步日志与构建产物放在被忽略的工作区 `runtime/phase1/step02/`；实际配置沿用被忽略的 `config/local/*.yml` 和 `runtime/phase0/baseline.env`。
- 状态：实现及 HTTP/WebSocket 接口级验收完成。机器可读证据为 [02-results.json](02-results.json)。
- 后续发现并修复前端实际 `/ws/` 路径兼容遗漏：此前测试客户端仅使用 `/ws`，未覆盖尾斜杠。原因、补充真实网络回归和本地生效方式见 [WebSocket 路径修复记录](02-websocket-path-fix.md)。本记录原 137 项与 7 项集成数字保留为当时验收证据，最新回归数量见补充记录。

## 1. 修改前与修改后

| 方面 | 修改前 | 修改后 |
|---|---|---|
| 用户端默认保护 | 只有带 `@GlobalInterceptor(checkLogin=true)` 的方法才触发鉴权，新增接口漏注解就裸露 | 每个业务 Handler 默认鉴权；公开接口同时匹配实际 Java Method 和精确路径；`checkLogin=false` 不能放行接口 |
| 图片上传 | `/file/uploadImage` 没有登录检查 | 匿名请求在进入文件存储前返回业务码 901 |
| 管理端公开名单 | URI 包含 `/account` 或 `/file/getResource` 就放行 | 仅实际验证码、登录、公开图片读取方法和对应精确路径放行；退出也需管理员身份 |
| 当前用户 | 切面与 ABaseController 分别查 Redis，多个地方各自解释身份 | 唯一 UserAuthenticationService 校验会话和数据库状态；受保护请求及有效自动登录探测在入口解析一次并存入不可变 CurrentUser；旧注解只检查同一身份 |
| 开发旁路 | 设置 `-Ddev` 会绕过登录并生成固定用户 | 两处固定身份逻辑移除；测试使用 Mock 和专用配置 |
| 用户状态 | HTTP/WS 主要判断 Token 是否存在 | 同时检查 Token 内容、userId→Token 反向映射和数据库 ENABLE 状态；禁用、删除及旧会话都拒绝 |
| 自动登录 | 校验通过后无条件重新生成 Token，旧请求可能在退出后重建会话 | Lua 仅刷新仍存在且仍是当前会话的 Token；保持同 Token；撤销优先，失败返回 901 |
| 资料更新的会话缓存 | setex 可以复活被撤销的旧 Token，或覆盖较新的反向映射 | 同一 Lua 条件刷新；已撤销或被替换的会话不能重建 |
| 旧 Token 退出 | 无条件删除用户的反向映射，可能影响较新的登录 | 比较删除，只删除仍指向旧 Token 的映射 |
| WebSocket | 只在握手时检查 Token，之后仅检查连接是否活跃 | 握手、私有推送、客户端消息及周期扫描都校验同一认证规则；退出、轮换、封禁跨进程通知关闭旧连接 |
| 连接管理 | 静态单用户单连接表，无完整移除路径 | 实例连接表支持同用户多连接；关闭旧连接不移除新连接；握手完成前不推私有帧；销毁时移除监听并关连接 |
| 地址归属 | 改默认前先清除本人默认，目标非本人时 0 行更新却成功 | Service 在任何写入前确认归属；目标写入必须影响 1 行；事务保留 |
| 地址/评论删除 | 非本人或不存在资源的 0 行结果仍显示成功；空资源 ID 存在扩大条件的风险 | 明确资源 ID 必填，查询保留可信 userId；0 行拒绝 600；缺失评论返回业务拒绝 |
| 订单、评论和物流已有防线 | 已有多处 userId 归属检查 | 保留原检查并回归，订单读取提前加入可信 userId 条件；不把原本存在的防线算作新增能力 |
| 凭据诊断输出 | Redis 写入错误可输出 key/value；WS 错误和浏览器连接日志可带 Token | 相关服务端日志仅记操作/异常类型；事件 toString 脱敏；前端不打印连接 URL 或可能带 URL 的构造异常 |

CurrentUser 只包含 userId、nickName、avatar、USER/ADMIN，不包含 Token、密码或 API Key。AgentController 显式把身份快照传给 ChatComponent，异步任务使用该快照，不读取请求或 ThreadLocal。模型工具的可信身份与危险操作限制仍属于步骤 05/06 的专项工作。

## 2. 精确公开清单与兼容性

用户端公开方法对应路径：

```text
/account/checkCode
/account/register
/account/login
/product/loadCategory
/product/loadCommendProduct
/product/loadProduct
/product/getProduct
/product/search
/order/comment/loadComment
/file/getResource
```

`/account/autoLogin` 为可选身份探测：无有效会话返回现有 `success/null`；有效会话经统一入口认证后原子刷新，返回原有 4 字段登录 DTO 和相同 Token；认证后遭撤销则返回 901。不会把整个 `/account/**` 或 `/product/**` 放行。

管理端仅放行 `/account/checkCode`、`/account/login`、`/file/getResource` 对应的实际方法。保留现有 RequestMapping 的 GET/POST 契约，不在本步同时迁移请求方式。用户端 `token` 与后台 `adminToken` 及各自 Redis 命名空间保持隔离。首版现有单管理员拥有后台操作权限，本步不新增完整权限管理平台。

返回结构沿用 ResponseVO 及业务码 901/600；未单独把后端改成 HTTP 401/403。原 13 条用户 CRUD 继续不注册、返回现有业务 404。WebSocket 失败握手仍 HTTP 403，前端 `ping` 仍不要求文本响应。

拦截器用 MappedInterceptor bean 注册，公开决策基于已解析的真实 HandlerMethod，避免字符串包含或前缀放行，并覆盖非默认 HandlerMapping。Spring 官方指出单独路径匹配的拦截器可能与 Controller 匹配不一致，因此本实现不使用路径排除列表作为公开权限依据；后续扩展独立 Servlet、Filter 链或新传输时仍需纳入统一安全入口。[Spring 拦截器文档](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-config/interceptors.html)

## 3. 修改前失败证据

| 测试 | 修改前实测 |
|---|---|
| RedisSessionRevocationTest 原 8 项 | 6 失败、2 通过：旧退出误删、新会话覆盖/复活、缺少跨进程撤销通知 |
| UserAddressOwnershipTest 原 4 项 | 4 失败：归属检查及副作用顺序缺失 |
| AppInterceptorSecurityTest 原 7 项 | 5 失败、2 通过：宽路径放行、注销未保护 |
| WebAuthenticationBoundaryTest 7 项 | 7 失败：无注解接口、前缀内新增接口、上传、dev 旁路、重复身份解析 |
| ChannelContextSecurityRegressionTest 1 项 | 1 失败：未验证身份也能绑定并收到私有推送 |
| UserResourceBoundaryTest 原 6 项 | 3 失败、3 通过：缺失评论、地址/评论删除零行假成功 |
| BusinessResourceOwnershipTest 7 项 | 7 通过：已有订单、评论、物流归属检查本来有效 |

证据为 `02-before.log`、`02-before-all.log`、`02-resource-before.log`。为了收集所有模块的修改前失败，后两次诊断明确使用 `maven.test.failure.ignore=true`；该运行的 BUILD SUCCESS 不表示测试通过，以上数字以 XML/失败输出为准。最终验收不使用该参数，也不跳过测试。

## 4. 验证命令与结果

JDK 21.0.11、Maven 3.9.14，使用阶段 0 工作区依赖缓存。后端目录运行：

```powershell
./scripts/verify-baseline.ps1 -JavaHome 'C:\Users\CC_Am\.jdks\ms-21.0.11' -MavenCommand 'D:\Driver\apache-maven-3.9.14\bin\mvn.cmd' -Package
```

完整隔离集成需要阶段 0 的 MySQL 13306、Redis 16379、ES 19200、本地模型替身 18080，以及本次打包的 Admin 6061（使用 `config/local/admin.yml` 合成配置）。启动前检查端口归属，不替换已有进程；模型和管理端后台启动。管理员仅使用 prepare-baseline-config 产生的合成账号。集成测试在创建上下文前检查基线配置、ws.port 6051、独立数据库和本地模型，支付和 MCP 工具使用替身。

```powershell
# 先设置同一 JDK21 的 JAVA_HOME/Path；配置和三个隔离容器仍沿用阶段0
mvn -B -ntp -Dmaven.repo.local=D:\java\java24\wokspach-teach\runtime\phase0\maven-repository -Dmaven.test.skip=false -DskipTests=false -Pbaseline-integration -pl easymall-web -am verify
```

两个前端运行 `npm run build -- --outDir <工作区根>/runtime/phase1/step02/frontend-{web,admin} --emptyOutDir false`。用户端仅去除 WebSocket 含 Token 日志，原 `.idea/vcs.xml`、原 dist 与其内容保留。

| 验证 | 最终实测结果 |
|---|---|
| common 普通测试 | 58 项，通过 |
| admin 普通测试 | 15 项，通过 |
| web 普通测试 | 64 项，通过 |
| 全模块 package | 137 项普通测试均通过，失败/错误/跳过均 0；common/admin/web/mcp 均 SUCCESS，51.363 秒 |
| 完整隔离集成 | BaselineSmokeIT 共 7 项均通过，失败/错误/跳过均 0；最终 suite 31.00 秒，verify 总计约 63 秒 |
| 用户端构建 | exit 0，17.80 秒 |
| 管理端构建 | exit 0，23.62 秒 |
| 本地模型替身 | synthetic=true，errors=0；没有真实模型效果或费用评测 |
| 最终差异检查 | 后端与前端 git diff --check 均通过 |

相较步骤 01，普通测试由 44 增至 137（新增 93），独立集成由 2 增至 7（新增 5）。最终普通测试日志为 `02-package-final.log`，最终集成为 `02-integration-final.log`；132 项的首轮 package 只是中间结果，不能替代最终数量。

7 项集成保留原商城/AI 回归与个人资料安全用例，新增：公开/私有 HTTP、真实 Redis JSON 序列化下的 Lua、真实 WebSocket 注销和自然到期、Admin 独立 JVM 封禁、A/B 三类业务资源双向归属。

- A/B 各自可读自己的订单、地址、评论；交叉读订单/评论、取消订单、评论/追评、改地址/设默认均返回 600；两名用户对应的订单、评论、地址与默认标志快照均不变。
- 合成私有消息经真实 Redis Topic 与 WebSocket 编解码器送达，然后通过真实 HTTP 退出，旧连接在 5 秒测试期限内关闭；旧 HTTP 返回 901，重新握手返回 403。
- 直接缩短合成会话 TTL 后，无心跳、无私有消息的连接在 20 秒测试期限内关闭，覆盖 15 秒周期扫描兜底。
- 实际合成 Admin 6061 封禁 B，通过 Redis 向另一 JVM 的 Web 节点通知；无客户端心跳的旧连接在 5 秒期限内关闭，旧 HTTP 901、重连 403；finally 通过管理接口解封并退出合成管理员。
- 合成完成态订单/评论/地址由测试守卫后插入或复用；不调用真实支付、不变更库存、不删除记录。原商城回归仍通过业务取消恢复其订单库存。

本轮所有自建服务已停止：合成 Admin、模型替身及 easymall-baseline 的三个容器；文件、容器、数据卷与 fixture 均保留。6050/6051/6061/18080/13306/16379/19200 无遗留监听，原开发 3306/6379/9200 仍在。结束状态记录在 `runtime/phase1/step02/end-state.json`。

## 5. 生效时机与边界

- 退出、替换登录和后台封禁先撤销 Redis 会话，再用独立 Topic 通知所有 Web 节点。Topic 通知失败不恢复会话；推送及心跳会重新认证；无流量过期连接由 15 秒周期扫描兜底，时间还受数据库/Redis 查询耗时影响。
- 每次受保护 HTTP 请求、WS 推送和客户端心跳都读取数据库状态，保证禁用判断及时。本步未进行吞吐量/并发压测，不声称性能提升。
- 已通过认证并开始执行的业务请求不被事务性回滚；本步不实现所有在途 AI 任务的取消。更强的登录与封禁并发协调、用户认证版本、多设备策略属于后续会话稳定性工作。尤其“密码登录先校验状态、封禁后才写 Token”的在途请求在禁用期间仍会被入口拒绝，但本步不承诺重新启用后所有该类请求也必须重新输入密码。
- 无数据库结构或密码协议迁移。密码哈希迁移、MCP 可信工具参数、模型工具权限、交易幂等仍按后续步骤实施。
- 默认普通测试不加载完整 Boot 基础设施，不连接真实模型或支付。真实集成只使用隔离服务和合成 A/B 用户，保留新增记录作为证据，测试禁用用户在 finally 中恢复。
- 浏览器页面点击与多窗口人工交互未单独验收；实际 HTTP 与 WebSocket 客户端、前端构建是本步主要验证方式。
- 阶段 0 已登记的大小写搜索、取消接口写 CLOSED(5)、RagDataTask 在上下文关闭后重试并输出 RedissonShutdownException 仍保留；本轮严格 Maven 验收退出码为 0，不把该既有生命周期缺陷算作已修复。
