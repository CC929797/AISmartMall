# 步骤 01：用户 CRUD 收口与安全响应模型

- 日期：2026-10-25（Asia/Shanghai）。
- 状态：实现及接口级验收完成。
- 修改前代码基线：`ce38232`（阶段 0 提交）；开始时仅路线图、环境说明两个 Markdown 文件未跟踪，原内容保留。
- 本次未提交 Git；未修改数据库结构、密码协议或前端源码；没有删除文件或清理已有数据。
- 机器可读证据：[01-results.json](01-results.json)。原始日志在工作区根 `runtime/phase1/`，不提交。

## 1. 修复的问题

| 问题 | 修改前 | 本次行为 |
|---|---|---|
| 生成式用户 CRUD | 13 条 `/userInfo/*` 路由没有登录检查，可匿名查询、修改和删除任意用户 | 移除类注册和全部 HTTP 映射，保留 Java 源码；真实 GET/POST 返回现有 ResponseVO 的业务码 404 |
| 管理端列表 | 直接返回 `PaginationResultVO<UserInfo>` | 显式转换成 `PaginationResultVO<UserAdminVO>`，只返回管理页面需要的 9 个字段 |
| 实体 JSON 输出 | Jackson、Fastjson2 均可输出 password/apiKey | 两字段分别使用 Jackson WRITE_ONLY 与 Fastjson2 serialize=false；嵌套分页响应也不输出 |
| 实体诊断输出 | `UserInfo.toString()` 拼接密码和 API Key | 去除这两项，保留其他原有诊断字段 |
| JSON 解析错误日志 | 原始输入与解析异常可能带出敏感字段 | 只记录目标类型、输入长度、异常类名；不记录原文、异常消息或异常对象，保留业务错误 603 |

`WRITE_ONLY` 只控制 JSON 方向，不删除数据库字段，也不取消 Java getter/setter。MyBatis 内部读取、注册/登录当前密码校验和两种 JSON 反序列化均保留并验证。

## 2. 路由与前端兼容

两个前端均未发现 `/userInfo/*` 调用。以下旧路由全部停用：

```text
/userInfo/loadDataList
/userInfo/add
/userInfo/addBatch
/userInfo/addOrUpdateBatch
/userInfo/getUserInfoByUserId
/userInfo/updateUserInfoByUserId
/userInfo/deleteUserInfoByUserId
/userInfo/getUserInfoByEmail
/userInfo/updateUserInfoByEmail
/userInfo/deleteUserInfoByEmail
/userInfo/getUserInfoByNickName
/userInfo/updateUserInfoByNickName
/userInfo/deleteUserInfoByNickName
```

文件保留为非 Spring MVC 组件，既没有 Controller 注册，也没有类或方法映射。已有内部 Service/Mapper 保留，登录注册等内部调用不受影响。

实际个人资料接口仍为 `/account/getUserInfo`、`/account/updateUserInfo`：身份来自现有 Token；更新仍只接收 avatar、nickName、sex，额外提交 userId、email、status、password、apiKey 不参与绑定。现有 `UserInfoVO` 和 `TokenUserInfoDTO` 已是安全响应模型，本次保持其字段及登录协议不变。

管理端 `UserList.vue` 使用的白名单为：

```text
userId, avatar, nickName, sex, email,
joinTime, lastLoginTime, lastLoginIp, status
```

分页 totalCount、pageSize、pageNo、pageTotal、list 全部保留；两个时间字段仍为 `yyyy-MM-dd HH:mm:ss`、GMT+8。原排序、查询条件、管理员拦截器、启用/禁用逻辑没有改动。

全仓未发现用户实体 apiKey 被实际模型业务读取；当前模型凭据来自应用配置。因此本步仅关闭对外暴露，不删列、不迁移已有值，也不新增 API Key 展示接口。

## 3. 先复现、再修复的证据

| 修改前测试 | 实际结果 | 说明 |
|---|---|---|
| 实体序列化 / toString / 兼容性 | 7 项中 5 失败、2 通过 | 直接 JSON、嵌套响应、toString 暴露；内部反序列化原本可用 |
| JSON 错误日志 | 2 项均失败 | 内存捕获日志，不把合成敏感输入继续写到控制台 |
| 管理端模型 | 3 项中 1 失败 | 直接断言行类型是 UserAdminVO；避免仅靠实体注解掩盖仍返回 PO 的问题 |
| 旧路由及 Account 兼容 | 20 项中 13 失败、7 通过 | 旧 13 路由均返回 200；现有 Account 兼容性原本通过 |

修改前日志分别保存为 `01-sensitive-before.log`、`01-admin-logging-before.log`、`01-admin-before.log`、`01-web-before.log`。这些失败不是跳过测试来消除，修复后对应断言均通过。

## 4. 修改后验证

本次使用 JDK 21.0.11、Maven 3.9.14，复用阶段 0 的工作区 Maven 缓存。命令在后端根目录执行：

```powershell
./scripts/verify-baseline.ps1 -JavaHome 'C:\Users\CC_Am\.jdks\ms-21.0.11' -MavenCommand 'D:\Driver\apache-maven-3.9.14\bin\mvn.cmd' -Package

# 独立服务和本地模型替身启动后，使用同一个 JDK 21
mvn -B -ntp -Dmaven.repo.local=D:\java\java24\wokspach-teach\runtime\phase0\maven-repository -Dmaven.test.skip=false -DskipTests=false -Pbaseline-integration -pl easymall-web -am verify
```

| 检查 | 实测结果 |
|---|---|
| common 普通测试 | 14 项：阶段 0 配置/分页 5、敏感输出 7、日志 2 |
| admin 普通测试 | 5 项：VO、字段/日期/分页、空列表、真实拦截器拒绝与允许 |
| web 普通测试 | 25 项：商品基线 5、用户路由及 Account 20 |
| 全模块 package | SUCCESS；44 项普通测试均通过，无失败、错误或跳过；33.937 秒 |
| 独立 HTTP 集成 | 2 项均通过，无失败、错误或跳过；verify 43.043 秒 |
| 管理端真实 JAR / HTTP | 合成管理员登录 200；匿名列表 901；授权列表 200，两名合成用户均仅有 9 个安全字段 |
| 用户端构建 | exit 0，21.77 秒 |
| 管理端构建 | exit 0，27.13 秒 |

普通 Web 安全测试使用最小 MVC 上下文与真实 GlobalOperationAspect/CGLIB，服务和 Redis 是内存替身；缺失/无效 Token 必须返回 901。Admin 测试显式使用真实 AppInterceptor，不以绕过鉴权的 Mock 响应代替认证验证。

普通验证脚本从本步起要求 common、admin、web 三个模块都生成本轮 XML 且执行数大于 0；仍拒绝使用历史报告充数。

两前端使用 `npm run build -- --outDir <工作区根>/runtime/phase1/frontend-{web,admin} --emptyOutDir false`，原 dist 与 `.idea/vcs.xml` 修改保留。前端源码未改，按两个页面实际请求参数和字段完成接口契约回归；浏览器点击、头像图片显示未单独验收。

## 5. 真实 A/B 隔离与原业务回归

显式 IT 在创建 Bean 前沿用阶段 0 的强制地址检查，仅连接本机专用测试 MySQL、Redis、ES 和固定模型替身；支付由 Mockito 替代，工具 Provider 为空实现。

- 原商城测试继续覆盖分类、商品、验证码登录、ES 搜索、下单、库存、本人订单、合成 AI 问答与历史消息；成功后通过业务接口关闭订单并恢复库存。
- 新安全测试使用合成 A 与合成 B。B 仅首次插入专用 `easymall_test`，以后校验其合成邮箱后复用，不删除记录或重置数据库。
- 实际登录、两次自动登录、读取和编辑个人资料响应均检查敏感键和值不出现；自动登录按现有行为跟随轮换后的 Token。
- A 在读取时伪造 B 的 userId，仍只得到 A；修改时附加 B 的 ID、密码、API Key、邮箱和状态，最终仅 A 的头像、昵称、性别变化，A 的其余列与 B 的整行快照保持不变。
- 编辑后再次自动登录，核对 Redis 的昵称、头像与资料读取一致。
- 先断言运行时没有任何旧用户映射，再逐条发送 13 路由的匿名 GET 与 POST；共 26 次均为业务 404，B 的快照不变。这样即使路由未来意外恢复，也会先失败，避免测试执行历史删除方法。
- 管理端真实 HTTP 额外覆盖具有非空合成 API Key 的 B 用户，验证两个用户的列表行均严格限定 9 字段。

测试框架准备过程中修复了最小上下文对 Redis Mock 的自动注入、完整上下文中 MVC/Actuator 路由 Bean 选择歧义；Admin 手动探针的合成验证码也按应用 JSON 格式存储。均为测试准备问题，没有为此改动业务实现或跳过断言，失败尝试保留在 runtime。

## 6. 范围与后续

本步没有执行真实模型、真实支付或退款，没有修改密码 MD5 协议、表结构或原开发数据。没有把普通测试通过作为全系统鉴权、并发交易或真实 AI 效果的验收。

默认保护、CurrentUser、固定 dev 身份绕过与用户禁用后完整失效仍属于步骤 02；排序/请求 DTO 与边界属于步骤 03；密码协议迁移属于步骤 04。其他 SQL DEBUG 参数与模型全量日志治理继续按后续步骤收口。

验收结束仅停止本次记录并核对身份的 Admin、模型进程及 `easymall-baseline` 容器。合成数据、local 配置、日志和卷保留；原开发 Redis 6379、ES 9200 保持运行。没有批量删除文件、清库、清索引、暂存或提交 Git。

- [x] 旧 13 个用户 CRUD 路由不可匿名访问，实际为业务 404。
- [x] A 伪造 userId 不能读取或修改 B，实际数据库快照验证通过。
- [x] 登录、自动登录、资料、管理列表及两种实体序列化均不输出密码或 API Key。
- [x] 原登录、个人资料、管理列表的前端接口契约与构建保持兼容；浏览器交互单独标明未测。

下一步：步骤 02，默认鉴权与统一当前用户上下文。
