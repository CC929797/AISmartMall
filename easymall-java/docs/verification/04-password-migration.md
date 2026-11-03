# 步骤04：密码协议、存储迁移与登录防护

- 日期：2026-11-03（Asia/Shanghai）。
- 基线提交：e51e397；本轮不提交 Git。
- 范围：Common/Web/Admin、两个前端、密码字段迁移及隔离环境验证。
- 状态：实现完成，最终自动化结果与收尾状态见同目录 `04-results.json`；用户页面验收按下文执行。

## 1. 改造前后的区别

| 环节 | 改造前 | 改造后 |
|---|---|---|
| 用户密码存储 | 无独立盐的 MD5 | 新注册、改密使用 Argon2id；旧记录正确登录后升级 |
| 登录输入 | 两个前端先 MD5，后端比较摘要 | 两个前端提交原密码，后端按已存格式验证；不接受客户端摘要替代原密码 |
| 公共密码组件 | 多处直接 MD5 | PasswordService 统一 encode、matches、needsUpgrade |
| 字段容量 | varchar(50)、utf8mb4_general_ci | varchar(255)、utf8mb4_bin；条件更新按完整编码区分大小写 |
| 并发登录与改密 | 读取后直接更新密码、签发会话 | 用户级 Redis 分布式锁、锁内重读、SQL 条件更新，防止覆盖新密码或用旧快照签发 Token |
| 改密后的会话 | 原 Token 保持可用 | 验证旧密码后撤销 Redis 会话并通知 WebSocket；前端清理本地凭据并重新登录 |
| 新密码规则 | 改密接口仅非空，容易绕过前端 | 保留原有8—18位、至少字母和数字的规则，在 Controller 和 Service 都执行 |
| 管理员 | 原密码配置计算 MD5 | 明确使用 admin.password-hash；不回退原密码或 MD5 配置 |
| 登录与验证码 | 只有图片验证码；先读取后删除 | 账号失败限制、来源请求限制、Redis GETDEL 一次性消费验证码 |
| 来源 IP | 盲目信任任意转发头 | 默认连接对端；显式受信代理才从右向左解析 X-Forwarded-For |
| 用户登录窗口 | onUpdated 重置表单并反复获取验证码 | 打开窗口或主动切换时初始化；输入和注册选择保持稳定 |

密码采用 Spring Security Crypto 6.5.2 的 Argon2PasswordEncoder 和 BC 1.86：Argon2id v19、内存19456 KiB、迭代2、并行度1、随机盐16字节、输出32字节。单实例最多同时进行2个高成本计算，忙时拒绝重试；没有引入 Security Web Filter 或改变 Redis Token 协议。参数依据 [OWASP 密码存储建议](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)，本机实际耗时记录到 `runtime/phase1/step04/04-password-cost.json`。

Alipay SDK 间接依赖的旧 bcprov-jdk15on 1.62 已排除，避免与新版 Provider 重复。SDK 使用合成 RSA 密钥的本地 RSA2 签名/验签回归通过；没有调用真实支付渠道，不能据此声明证书、支付或退款已经联调。

## 2. 数据库到底改了什么

本轮仅在 `127.0.0.1:13306/easymall_test` 执行结构迁移。执行前列为 varchar(50)、utf8mb4_general_ci；执行后为 varchar(255)、utf8mb4_bin、NOT NULL。执行 DDL 前后均有2个原合成用户，编码字节总数均为64，原数据保持完整。后续集成测试会正常升级合成账号，并保留新注册/改密测试用户作为证据。

**你的日常开发数据库没有执行这条迁移。** 启动新代码并登录前，在数据库客户端选中实际包含 user_info 的开发库，备份该表后执行：

执行前可用 `SHOW FULL COLUMNS FROM user_info LIKE 'password';` 核对当前定义。下面脚本适用于原来的50字符列；若你已经扩到255字符或更长，保留已有容量，不要直接缩短。

```sql
ALTER TABLE user_info
    MODIFY COLUMN password VARCHAR(255)
    CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '密码哈希';
```

完整脚本：[V20261103_01__expand_password_hash.sql](../../db/migration/V20261103_01__expand_password_hash.sql)。脚本不包含 USE，不会自动选择你的开发库。执行后确认：

```sql
SELECT TABLE_SCHEMA, COLUMN_TYPE, IS_NULLABLE, COLLATION_NAME
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'user_info' AND COLUMN_NAME = 'password';
```

预期为你的库名、varchar(255)、NO、utf8mb4_bin。应用没有 Flyway/Liquibase，也不会在启动时自动执行该脚本。新的空隔离库结构文件已同步扩容，已有库需要单独执行迁移。

MySQL DDL 会隐式提交；扩容不能当作普通业务事务回滚。产生长哈希后不能再直接缩回50字符，旧版应用也无法验证新编码。恢复时保留扩容列，并部署支持两种已存格式的版本；需要恢复旧数据时使用迁移前备份。

## 3. 管理员配置及上线顺序

本机这两份被忽略的配置已通过专用工具迁移，仅将 admin.password 替换为 admin.password-hash，管理员实际密码不变：

- `easymall-admin/src/main/resources/application-dev.yml`
- `config/local/admin.yml`（合成集成配置）

迁移工具会解析并对比其他所有配置属性，再原子替换单个文件。物流模式、日志级别、数据库等配置保留。没有把真实密码或哈希写入控制台、验证文档或 Git。

其他本机配置需要迁移时，在后端根目录执行：

```powershell
.\scripts\convert-admin-password.ps1 `
  -ConfigPath '.\easymall-admin\src\main\resources\application-dev.yml' `
  -JavaHome 'C:\Users\CC_Am\.jdks\ms-21.0.11'
```

该命令要求已经 package；只运行独立工具，不启动应用、不连接数据库。已迁移文件再次执行保持不变。原密码必须是单行明确配置；环境变量引用或多行内容会拒绝处理并保留原文件。部署环境使用 ADMIN_PASSWORD_HASH 对应 admin.password-hash，不能继续传 ADMIN_PASSWORD。

更新顺序：先扩容数据库列，再配套更新后端与两个前端，重启服务，浏览器强制刷新。新接口不长期同时接受原密码和客户端 MD5 两种协议。正式部署以 HTTPS 保护原密码和 Token 的传输，日志不记录原密码、编码或请求体。

## 4. 可直接操作的页面验收

下列测试使用专用测试账号；数据库查询只显示算法类型及长度，不输出编码本身。

### A. 旧账号升级

1. 选一个尚未升级的旧测试账号，查询下方算法状态，应为 MD5。
2. 用错误密码登录一次，查询仍为 MD5。
3. 用该账号原密码正确登录，查询应变为 Argon2id。
4. 退出，再用同一原密码登录，仍应成功，无需重新注册或更换密码。

```sql
SELECT user_id, email,
       CASE WHEN LEFT(password, 10) = '$argon2id$' THEN 'Argon2id'
            WHEN password REGEXP '^[0-9a-fA-F]{32}$' THEN 'MD5'
            ELSE '其他格式' END AS password_algorithm,
       CHAR_LENGTH(password) AS encoding_length
FROM user_info WHERE email = '你的测试邮箱';
```

如果账号已经是 Argon2id，表示已升级，直接验证登录即可。不要为测试把现有密码改回 MD5。旧客户端摘要不能登录的情况另有自动化回归覆盖。

### B. 注册新账号

1. 注册新的测试邮箱，密码例如 TestUser123，填写正确验证码。
2. 应提示注册成功，使用相同密码能登录。
3. 数据库算法状态从创建时起就是 Argon2id。
4. 输入过程中字段不应自动清空，切换注册后不应自动跳回登录。

### C. 修改密码与旧登录态

1. 登录测试账号，另开一个同浏览器标签页并保持登录；可在改密前记录旧 Token，仅留在本机请求工具中使用。
2. 旧密码填错时拒绝修改，当前登录态仍可用。
3. 新密码填123等不合格内容应被拒绝；直接向 POST /api/account/updatePassword 提交同样内容也会被后端拒绝。
4. 正确输入旧密码，新密码例如 TestUser456；成功后提示重新登录，当前页面清理旧 Token 和用户信息。
5. 另一个标签页再访问需要登录的功能，应要求登录。用旧 Token 访问私有接口返回业务码901，不能继续使用原 WebSocket。
6. 旧密码登录失败，新密码登录成功。

### D. 管理员与登录防护

1. 重启管理端和前端，用原管理员账号、原密码及正确验证码登录，应正常进入后台。
2. 选一个专用测试账号，在5分钟内累计5次登录失败（验证码错误也计入）；之后应提示失败次数过多，等待窗口到期后可重试。
3. 同一验证码提交后再次使用，必须失败；验证码需重新获取。
4. 同一来源一分钟内超过20次申请验证码，或超过30次尝试登录，会提示操作频繁。正常打开、输入不应重复申请验证码。

限速默认配置：authentication.max-failures=5、failure-window-seconds=300、login-requests-per-minute=30、captcha-requests-per-minute=20；注册每来源10次/10分钟，改密每用户10次/10分钟。计数由 Redis 原子操作维护，成功登录清除账号失败计数，不清除来源请求计数。Redis 不可用时拒绝操作。

authentication.trusted-proxies 默认空。生产反代必须正确追加/覆盖来源地址，只列出确实受控的代理 IP；后端直接暴露时不能任意信任客户端转发头。

## 5. 自动化验证与复现

- 普通回归：密码独立盐、旧 MD5 验证、客户端摘要拒绝、新密码规则、错误密码不迁移、条件更新失败、并发密码快照变化、撤销失败不改密、管理员协议、配置迁移和来源 IP 信任边界。
- 隔离集成：真实 MySQL/Redis/HTTP/WebSocket，覆盖旧账号升级、新注册、改密、旧 Token 和连接撤销；10个并发请求消费同一验证码仅1个成功；账号失败限制、TTL、成功清理及验证码获取限速。
- 前端：3项实际 Vue 单文件组件行为测试，网络、表单控件和路由使用替身；另保留42项 Markdown DOM 安全回归和两前端生产构建。它们不代替上述用户页面验收。
- 完整结果、版本、耗时、日志和收尾状态写入同目录04-results.json。原始输出位于工作区根runtime/phase1/step04/，不会提交Git。

后端普通回归：在后端根目录运行原 verify-baseline.ps1 -Package。完整隔离集成仍使用 baseline-integration Profile，需先启动现有隔离容器、本机模型替身和6061合成管理端，见scripts/README.md。前端两个项目均可运行 npm run test:account；该入口一次验证两个项目的登录/改密组件。

## 6. 范围与故障语义

改密先验证旧密码并准备新编码，再在用户锁内重新确认数据库状态，撤销会话，最后条件写入新密码。Redis撤销失败时不写密码；数据库写入失败时可能已经退出登录，但不会返回改密成功。再次登录应使用数据库实际生效的密码。

锁在多个 Web 实例之间通过 Redis 协调并使用看门狗续期；数据库条件更新仍是最后的覆盖保护。已经通过鉴权并执行中的业务请求不会被回滚。本轮未整体改造Worker重连、MCP工具权限、账号找回或管理员CRUD；步骤07的其余工作仍待完成。
