# 步骤 05：图片上传、资源读取、日志、配置与 Markdown 安全

- 日期：2026-11-02（Asia/Shanghai）。
- 修改前后端基线：3c022c5，步骤 03 的输入边界提交；步骤 04 密码迁移仍未实施。
- 状态：代码实现及自动化验收通过，等待用户页面验收；本次没有提交 Git。
- 证据：本页配套的 [机器记录](05-results.json)；原始日志、测试产物和验收样例保存在工作区根 runtime/phase1/step05/。
- 原有路线图、环境说明、前端 .idea/vcs.xml、WebSocket worker、购买数量控件和两个 dist/ 均保留。未清库、重置 fixture、删除容器或数据卷，没有执行批量文件删除。

## 1. 修改前 → 修改后

| 范围 | 修改前 | 本次实现与实际效果 |
|---|---|---|
| 上传身份 | 步骤 02 已有默认登录拦截；存储工具本身不检查身份 | 保留两端默认鉴权，上传仅允许 POST；FileUtils 在存储入口再检查拦截器写入的可信 CurrentUser，不能以请求参数伪造计量身份 |
| 文件真实性 | 取客户端后缀，transferTo 原文件 | 新上传仅 JPG/JPEG、PNG；从实际内容识别格式，扩展名与真实格式匹配，完整解码后复制像素并重新编码，去除元数据和尾随内容 |
| 规模限制 | 已有 multipart 5MB 限制，公共存储工具缺少独立保护 | 额外检查声明大小和实际流长度、尺寸、总像素；重编码结果也受大小上限约束，每实例最多同时处理两张图 |
| 频率与配额 | 没有按身份计量 | Redis Lua 原子计量，用户与管理员身份分开；超过限额时拒绝，Redis 不可用时不降级成无限上传 |
| 存储命名 | 随机文件名加客户端后缀 | 年月目录加服务端 UUID，扩展名由成功解码的格式确定；CREATE_NEW 防覆盖，拒绝跟随链接 |
| 缩略图 | 拼接 ffmpeg 命令，依赖外部程序 | Java ImageIO 与 Graphics2D 生成，最长边不超过 200，不放大小图；保留原有 _thumbnail 后缀和前端返回路径协议 |
| 资源读取 | 字符串路径检查，按后缀生成 MIME；不存在也可能返回 200 空响应 | 规范化路径、实际路径和文件属性共同检查；拒绝绝对路径、越界、链接与 Windows 联接；检查图片签名并设置正确 MIME、nosniff 和受限 CSP；不存在返回 404 |
| 超大请求 | 容器可能提前断连，客户端只显示网络异常 | 10 MiB 有界吞咽剩余请求体；两端真实 HTTP 的超 5 MiB 测试均收到业务码 600 |
| Markdown | MdPreview/MdEditor 未传 sanitize，库的该默认回调原样返回 HTML | 两端预览和后台编辑预览关闭原始 HTML；DOMPurify 白名单清理，链接与图片协议分别检查，新窗口链接带 noopener noreferrer |
| 上传界面 | 选择器显示 GIF/BMP/WebP 等；失败可能把空路径写入控件，编辑器上传 Promise 可能不结束 | 选择器和大小提示与 JPG/JPEG/PNG 对齐；FormData boundary 由浏览器生成；失败不覆盖原图，不留下悬挂 Promise |
| 日志 | 验证码答案、模型请求/回复、分片元数据、支付完整响应可能进入日志 | 删除敏感正文输出；模型日志默认关闭，显式开启也只记耗时/分片数/结束类型；限制 SQL 参数及相关 SDK/wire 日志，异常仅记类型 |
| 配置与演示 | 跟踪配置使用 xxx 等占位值，模拟发货/轨迹默认运行，支付环境缺少代码约束 | 关键配置使用环境变量，补本地样例与忽略规则；支付网关与 sandbox/live 模式匹配；demo Profile 使用不扣款、不伪造到账的独立适配器，模拟物流仅显式开启 |

## 2. 上传边界与兼容性

默认边界：

| 项目 | 默认值 |
|---|---|
| 新上传格式 | JPG/JPEG、PNG；文件名后缀与实际格式必须匹配 |
| 输入与单个重编码文件 | 最大 5 MiB，即 5242880 字节 |
| 宽、高 | 分别最多 4096 |
| 总像素 | 最多 12000000，在完整解码之前读取尺寸检查 |
| 同时解码 | 每个应用实例最多两张 |
| 请求频率 | 每身份 60 秒计数窗口最多 20 次尝试 |
| 每日落盘额度 | 每身份 100 MiB，原图与缩略图实际字节合计 |
| 日界线 | UTC 自然日，即北京时间 08:00 切换日期；旧计量键最多保留 48 小时 |

ImagePolicy 的大小、尺寸、像素配置不能高于代码硬上限，可以配置收紧。用户与管理员即使账号字符串相同，也使用不同的哈希计量键。计量不保存 Token、密码或原始账号字符串。

先校验并重编码，再预占字节额度，最后落盘；落盘失败保留已计量额度。失败处理只会针对本次明确创建的单个文件做清理，不扫描或批量删除目录。

历史商品图和头像继续通过原 getResource 接口公开读取。旧 JPG/PNG/GIF/BMP/WebP 图片按允许的后缀和文件签名提供，新增上传暂不接收 GIF/BMP/WebP，因为当前运行时没有纳入可靠的 WebP 解码器。本次不是既有图片批量迁移，也没有重新编码或删除旧业务图片。

重编码会改变文件字节，JPEG 会重新压缩，原始 EXIF 等元数据不保留；页面验收时请使用自己的实际商品图和头像确认画质及方向。

PROJECT_FOLDER 是受信任的管理员配置，其 file/ 子树只能由应用和受信任运维账号写入；代码拒绝该子树中的链接/联接，最终打开也使用 NOFOLLOW_LINKS。Java 的普通路径检查不等于能抵御拥有本机目录改写权限的攻击者在检查和打开之间反复替换目录。

## 3. Markdown 渲染规则

- 两端 MarkdownView 和后台 EditorMarkdown 的预览均接入同一套规则。
- 原始 HTML 按普通文本处理；渲染后的 HTML 再经过 DOMPurify 的标签与属性白名单。
- 首版保留标题、文字、列表、表格、代码、图片和链接；不接受任意事件属性、内联样式、脚本、iframe、SVG/MathML 或表单嵌入，编辑器禁用 Mermaid。
- 普通链接允许 HTTP/HTTPS、mailto 和页内锚点；图片只允许 HTTP/HTTPS 或可解析到这些协议的相对地址。
- javascript、vbscript、data、file、blob、协议相对地址以及含控制字符/反斜杠的地址被拒绝。
- 外部图片仍可能访问其 HTTP/HTTPS 地址；这一步没有提供图片代理或私有文件授权功能。
- 生产依赖固定 DOMPurify 3.4.17；Markdown-it 使用编辑器兼容范围内的 14.3.3，未直接跨主版本升级编辑器。

## 4. 日志、凭据与演示模式

LoggerAdvisor 默认构造和 security.logging.model-summary=false 均不输出模型正文。开启摘要后只记耗时、分片数和结束类型；流仍原样传递，没有为日志额外聚合整个回复。正常调用、流、异常和重复订阅均有测试。

两端验证码答案不再输出；ChatComponent 不输出回复、提示词或模型元数据；支付查询不输出完整响应。SQL Mapper、AI/支付 SDK 和若干 HTTP wire 日志显式限制，应用层保留异常类型与受控摘要。

主资源配置与 web/admin/mcp.example.yml 的关键凭据均采用环境变量。私有 application-dev.yml、config/local/*.yml 的指定真实文件及私钥格式增加或保留忽略规则；原私有配置未覆盖。check-sensitive-files.ps1 检查跟踪及未忽略待提交文件中的已知凭据特征，只输出位置，不输出值。它不是完整密钥审计，不包含 Git 历史或供应商轮换。

支付模式：

- 默认 sandbox，仅接受受支持的支付宝 HTTPS 沙箱网关；live 必须显式配置且只能使用正式网关。地址不允许用户信息、额外查询串或非标准端口。
- demo Profile 替换 payChannel4Alipay 为 DemoPayChannel；页面明确显示未调用支付渠道，订单保持待支付，查询不伪造到账，回调与退款明确拒绝。
- 模拟自动发货与物流轨迹只在 logistics.mode=simulated 时运行，默认 disabled。没有接入真实物流供应商。
- 密码协议、支付通知验真与完整资金幂等、MCP 工具可信身份仍属于后续工作；本步未用真实支付或退款验收。

本地演示覆盖配置是 config/local/demo.example.yml，可在原有开发配置基础上显式加载：

    --spring.profiles.active=dev,demo --spring.config.additional-location=file:./config/local/demo.example.yml

启动目录为 easymall-java。所需数据库、Redis、ES 和模型配置仍使用你已有的私有开发配置；这份覆盖文件不会自动准备基础设施。

## 5. 自动化结果

补充：用户随后发现 MCP 的公共 Servlet 配置加载问题，现已修复，并新增 MCP 自身回归及真实启动握手验证；最新普通测试为 478 项，见 [MCP 启动兼容记录](05-mcp-startup-fix.md)。下表保留本步骤首次自动验收的原始数量。

| 验证 | 实际结果 |
|---|---|
| 修改前普通测试及 package | 403 项通过 |
| 最终普通测试 | 471 项通过，较之前增加 68 项；common 310、admin 28、web 133；失败/错误/跳过均为 0 |
| 最终四模块 package | common/admin/web/mcp 全部成功；31.259 秒 |
| 文件系统安全测试 | 40 项，含伪装内容、尺寸/像素、实际流大小、重编码、缩略图、旧 GIF、路径越界及实际 Windows 目录联接拒绝 |
| 配额、支付模式及日志 | Redis 失败拒绝、网关边界、demo Profile 选取、日志不泄露正文及默认禁用模拟物流均通过 |
| 两端 DOM 安全测试 | 每端 21 项，共 42 项；失败/跳过均为 0 |
| 两前端最终构建 | 用户端 10.80 秒，管理端 15.22 秒，均成功；输出至独立验证目录，原 dist/ 保留 |
| 真实隔离集成 | 11 项通过，较之前增加 2 项；失败/错误/跳过均为 0，suite 约 29.03 秒 |
| 上传真实 HTTP | Web/Admin 匿名拒绝、登录上传、公开原图/缩略图、伪装图片、SVG、越界路径和超大文件均通过 |
| Redis 真实并发与额度 | 同一合成身份 40 个并发计量请求仅 20 个通过；100 MiB 边界先拒绝越界增量，再允许剩余 1 字节 |
| 旧业务链路 | 原浏览、登录、搜索、下单、AI 回复、用户归属、退出/过期/封禁和 WebSocket 回归通过 |
| 配置与日志 | 7 份公开 YAML 无重复键解析通过；三个 Logback XML 有效；当前凭据特征扫描 0 发现；新日志指定敏感标记 0 命中 |
| 环境收尾 | 本轮管理端、模型替身及 easymall-baseline 已停止，七个测试端口无监听；原 mall Redis/ES 容器继续运行，保留所有数据与卷 |

关键日志均位于 runtime/phase1/step05/：

- 05-before-package.log：修改前 403 项。
- 05-package-final-verified.log：最终 471 项和全模块打包。
- 05-integration-final.log：最终 11 项真实集成。
- 05-web-security.log、05-admin-security.log：两端 DOM 样本测试。
- 05-frontend-web-final.log、05-frontend-admin-final.log：最终前端构建。
- 05-sensitive-scan.json、05-log-check.json、05-configuration-check.log：静态与新日志检查。
- end-state.json：测试环境结束状态。

中间诊断日志保留。真实 HTTP 测试发现并修复了超限后的连接重置；缺图契约改为 404 后同步更新旧断言。Windows 上测试 JAR 占用导致的一次打包失败通过停止本轮进程并使用独立 JAR 副本解决。最终结论以上述 final 日志和 XML 通过结果为准。

## 6. 用户页面验收清单（待你确认）

用最新代码启动你平时的用户端、管理端和后端，按原本账户登录。验收样例目录：

    D:\java\java24\wokspach-teach\runtime\phase1\step05\acceptance

| 样例 | 预期 |
|---|---|
| valid-image.png / valid-image.jpg | 上传成功；头像或商品图可显示，带缩略图的列表正常 |
| fake-image.png | 文件名像 PNG，内容是普通文本；后端拒绝，原头像/封面保留 |
| blocked-image.svg | 选择器限制或接口拒绝，不保存到业务图片目录 |
| oversized-image.png | 比 5 MiB 多 1 字节；前端提示大小限制，直接调用接口也拒绝 |

- [ ] 用户端上传正常 PNG 和 JPG 头像，刷新后能显示；重新登录后仍正常。
- [ ] 管理端保存正常商品图，列表缩略图、详情原图和图片查看器均能展示。
- [ ] 用伪装图片和超大样例验证失败提示，确认失败时不会清空原图；后台 Markdown 图片上传失败后能继续操作。
- [ ] 后台编辑器与 AI Markdown 中输入下面的原始 HTML 和危险链接，页面不弹窗、不出现可执行危险链接；正常 Markdown 仍能展示。
- [ ] 正常发一条 AI 消息能收到回复；检查本次新日志，不出现问题正文、模型回复、验证码答案或 Token。
- [ ] 如使用 demo Profile，支付窗口明确显示未扣款，订单保持待支付；仅在选择 simulated 时出现模拟物流轨迹。

可复制的 Markdown 验收内容：

    # 正常标题
    **正常粗体** [安全链接](https://example.com)
    <img src=x onerror="alert('step05-test')">
    <script>alert('step05-test')</script>
    [危险链接](javascript:alert('step05-test'))

匿名接口可在未附带 Token 的独立终端验证：

    curl.exe -F "file=@D:/java/java24/wokspach-teach/runtime/phase1/step05/acceptance/valid-image.png" http://127.0.0.1:6050/api/file/uploadImage

预期业务码 901。资源读取越界示例 sourceName=../outside.png 预期业务码 600；合法但不存在的图片预期 HTTP 404。

## 7. 证据边界

- 本次完成接口、文件系统、DOM 清理和构建验证；上面的真实浏览器页面交互仍等待用户验收。
- 没有真实模型质量、支付渠道、退款到账或性能压力测试结论。
- 日配额不是全生命周期存储配额；已有上传文件未做盘点、迁移或批量清理。
- 历史日志和 Git 历史没有清理；当前特征扫描没有发现不代表历史从未存在密钥。
- SKU 交易幂等、AI 取消终态、MCP 权限与任务退出重试等仍按对应步骤推进。

实现依据：[OWASP 上传指南](https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html)、[Java ImageReader](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/javax/imageio/ImageReader.html)、[Java 实际路径解析](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Path.html#toRealPath(java.nio.file.LinkOption...))、[DOMPurify](https://github.com/cure53/DOMPurify)、[MdEditor sanitize API](https://imzbf.github.io/md-editor-v3/en-US/api/)、[支付宝官方接入指南](https://aipay.alipay.com/merchant-guide)。
