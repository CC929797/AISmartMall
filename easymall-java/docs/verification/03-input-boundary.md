# 步骤 03：排序白名单、请求 DTO 与业务输入边界

- 日期：2026-10-28（Asia/Shanghai）。
- 修改前代码基线：`8a40eec`，即步骤 02 的用户身份与令牌续期提交。
- 开始时后端仅路线图、环境与启动说明两个 Markdown 未跟踪，原内容保留；前端原有 `.idea/vcs.xml`、WebSocket worker 日志修改及两个 `dist/` 保留。
- 新增说明与代码注释使用中文。不自动暂存或提交 Git，不执行批量删除、清库、重置索引或删除容器、数据卷、数据库 fixture。
- 状态：实现、普通回归及真实隔离集成验收已完成；本轮测试服务已停止。实际通过结果见下表，不以设计用例数量代替运行证据。
- 原始日志位于工作区根 `runtime/phase1/step03/`；汇总数据见 [机器可读结果](03-results.json)。

## 1. 修改前后行为

| 输入面 | 修改前 | 本次行为 |
|---|---|---|
| Mapper 排序 | 16 个 `selectList` 通过 `${query.orderBy}` 插入完整字符串 | 每个 Mapper 独立配置列白名单，先重建固定列与 `asc/desc`，再插入 `safeOrderBy`；未知列、表达式、额外 SQL、非法方向均拒绝 |
| 请求绑定内部查询结构 | 管理端部分接口直接绑定完整 Query，可接收页面不需要的实体筛选及内部分页结构 | 列表专用请求 DTO 映射到新建的内部 Query；全局表单绑定拒绝 `orderBy`、`simplePage` 及嵌套形式 |
| 管理列表筛选 | 用户、订单、评论、知识库直接绑定 Query；部分页面提交的筛选字段没有实际 Query 属性 | 六类列表使用明确 DTO；订单商品名、评论昵称和商品名通过参数化 `EXISTS` 条件实际筛选，列表与计数共用条件 |
| ES 搜索 | 无效枚举或分页异常可能进入兜底，表现为“搜索无结果” | 在兜底捕获之前验证输入，无效排序、分页或价格范围返回 600，不调用 ES |
| 下单明细 | `orderList` 只有级联标记，空集合、明细数量及购买数量约束不足 | 明细 1—50 条、每项数量 1—99、明细不能为空；Service 再次校验；重复商品和 SKU 明确拒绝 |
| 购物车新增 | 可绑定完整购物车实体；数量累加缺少统一上限 | 专用 DTO 只接收商品、SKU、数量；身份及持久化字段由服务端决定；数量及累加结果不得超过 99 |
| AI 消息 | 主要依赖前端 1000 字输入限制 | Controller、异步入口与 Service 使用相同非空、长度、身份和消息 ID 规则；不支持的附件输入明确拒绝 |
| 评价与追评 | 注解约束不足以保护 Service 直接调用；图片数量没有统一限制 | Service 与 Controller 共用规则：内容非空且不超过 300 字，星级 1—5，最多 5 个非空图片路径，总路径串不超过 300 字 |
| 校验异常 | 部分约束异常可能被错误转换或落入通用错误分支 | 保留 ResponseVO 协议，校验/绑定失败统一业务码 600；登录失效仍为 901 |

这里的输入防护与步骤 02 的身份、归属检查同时生效；输入合法不代表有权操作其他用户的订单、地址、消息或评论。

## 2. 全局绑定与分页边界

`RequestInputAdvice` 对商城 Controller 注册 `@InitBinder`，合并原有禁用字段，并在 Bean 绑定前检查实际请求参数。

- 表单和查询参数中的 `orderBy`、`simplePage` 及其嵌套、数组、字段标记变体拒绝为 600，不允许客户端自行设置 SQL 排序和分页偏移。
- `pageNo` 有明确数值时限定为 1—100000；`pageSize` 限定为 1—100。省略或现有表单提交的空值仍使用各接口默认值，不统一改变现有每页 15/20 条的契约。
- 重复分页参数、不可解析整数、越界数值和对应数组回退名称也按同一规则处理。
- 绑定器限制集合自动扩容，避免表单的超大集合下标在业务校验前申请大量内存。
- `BaseParam` 设置页码、页大小时也执行规则；`SimplePage` 的内部计算同样检查范围，不能只依赖 Web 注解。

JSON 请求使用专用 DTO 的字段范围与嵌套 Bean Validation。**不能把本次行为概括为“所有额外 JSON 字段一律 600”**：未知 JSON 字段沿用当前反序列化策略，但不会因为 DTO 没有这些属性而绑定到内部 Query、身份或金额字段；已声明字段的非法值才按约束拒绝。

Spring 将 `@InitBinder` 用于定制请求数据绑定，Bean Validation 的级联校验需明确使用 `@Valid`。本次同时验证表单绑定、JSON 明细以及 Service 直接调用，防止只有注解而实际没有生效。[Spring 数据绑定文档](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-initbinder.html)，[Spring 校验文档](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-validation.html)

## 3. 16 个 Mapper 排序入口与来源

`SqlSortWhitelist.forMapper` 只接受对应表的固定列和 `asc/desc`。允许现有无别名前缀及本表别名写法，必要复合排序最多 4 个片段；输出由白名单常量重新构造。Mapper 的 `${safeOrderBy}` 仍是 SQL 文本插入，安全依据是它只来自此常量映射，不能认为单独使用 `<bind>` 就自动消除了注入风险。

| Mapper | 已有排序的业务来源 | 保留的现有固定排序 |
|---|---|---|
| AgentMessageMapper | 用户端历史消息、Chat 历史上下文 | `message_id desc` |
| OrderInfoMapper | 用户订单、Chat 查询本人订单、管理端订单列表 | `o.order_time desc` |
| OrderCommentMapper | 商品公开评论、本人评论、后台评论列表 | `comment_time desc`、`o.comment_time desc` |
| OrderItemMapper | 当前未发现显式 `setOrderBy` 调用；保留通用 Mapper 防线 | 仅接受本表固定主键/订单列与方向 |
| OrderLogisticsInfoMapper | 当前未发现显式 `setOrderBy` 调用；保留通用 Mapper 防线 | 仅接受本表固定订单/物流状态列与方向 |
| OrderLogisticsInfoRecordMapper | 物流记录 Service | `record_time desc` |
| ProductCartMapper | 用户购物车列表 | `p.last_update_time desc` |
| ProductInfoMapper | 用户商品列表、Chat 商品数据、后台商品列表 | `create_time desc`、`p.create_time desc` |
| ProductPropertyValueMapper | 商品详情 Service、RagDataTask | `property_sort asc` |
| ProductSkuMapper | 商品详情 Service；本次后台库存列表明确排序映射 | `sort asc`；后台可映射固定 `p.stock` 或 `p.sort` |
| RagQuestionMapper | 后台知识库列表 | `r.question_id desc` |
| StatisticsInfoMapper | 统计 Service | `statistics_date asc,data_type asc` |
| SysCategoryMapper | 分类 Service、后台分类及属性列表 | `sort asc`、`s.sort asc`、`s.sort asc,sp.property_sort asc` |
| SysProductPropertyMapper | 当前未发现显式 `setOrderBy` 调用；保留通用 Mapper 防线 | 仅接受固定属性 ID/排序列与方向 |
| UserAddressMapper | 用户地址列表 | `default_type desc` |
| UserInfoMapper | 后台用户列表 | `u.join_time desc` |

复合分类排序的验证同时启用属性查询开关，确保 `sp` 的关联确实存在。所有现有服务端固定排序都有 BoundSql 回归；注入文本在 SQL 构造时即被拦截，不需要向数据库执行恶意语句。

分类批量排序中的 `${item.sort}` 同时改成 `#{item.sort}`，数值进入预编译参数。MyBatis 明确区分参数替换与字符串替换；列名和方向不能当作普通值参数，因此本次排序使用固定映射而非简单替换成 `#{orderBy}`。[MyBatis 参数文档](https://mybatis.org/mybatis-3/sqlmap-xml.html#Parameters)，[MyBatis 动态 SQL 文档](https://mybatis.org/mybatis-3/dynamic-sql.html)

## 4. 六类后台列表请求 DTO

六类请求共同只接受 `pageNo`、`pageSize`、`sortField`、`sortDirection`，不继承内部 Query。`sortDirection` 仅为 `asc/desc`，单独提供方向而没有字段时拒绝。全部排序参数省略时保留旧默认；明确选择字段但省略方向时使用 `desc`。

| 接口与 DTO | 页面筛选字段 | `sortField` 到固定列的映射 | 默认排序 |
|---|---|---|---|
| `/user/loadUser` / AdminUserListRequest | `nickNameFuzzy`、`status` | `joinTime→u.join_time`、`lastLoginTime→u.last_login_time`、`nickName→u.nick_name`、`status→u.status` | `u.join_time desc` |
| `/order/loadOrder` / AdminOrderListRequest | `productNameFuzzy`、`orderStatus` | `orderTime→o.order_time`、`amount→o.amount`、`orderStatus→o.order_status` | `o.order_time desc` |
| `/order/loadComment` / AdminCommentListRequest | `nickNameFuzzy`、`productNameFuzzy` | `commentTime→o.comment_time`、`star→o.star` | `o.comment_time desc` |
| `/rag/loadRagQuestion` / AdminRagListRequest | `questionFuzzy` | `questionId→r.question_id`、`createTime→r.create_time` | `r.question_id desc` |
| `/productInfo/loadProduct` / AdminProductListRequest | `productNameFuzzy`、`categoryIdOrPCategoryId`、`commendType`、`status` | `createTime→p.create_time`、`minPrice→p.min_price`、`totalSale→p.total_sale` | `p.create_time desc` |
| `/home/loadLessStockProduct` / AdminStockListRequest | 无客户端库存阈值 | `stock→p.stock`、`sort→p.sort` | 保留原无显式排序 |

昵称模糊条件上限 100 字；商品名、知识库问题模糊条件上限 200 字；分类 ID 上限 15 字。用户状态限定 0/1，商品状态 -1/0/1、推荐类型 0/1，订单状态限定现有 -1—7 范围。

订单关联明细/用户、评论关联用户/商品等开关由 DTO 转换方法明确设置，不能被客户端 `queryItems=false` 等输入关闭。库存预警阈值仍由服务端设为 10。原接口已经接收前端分页字段；本次让商品列表和库存预警列表的 `pageSize` 实际生效。

后台订单和评论页面原先提交的商品名/昵称条件缺少相应 Query 属性。本次为内部 Query 增加明确属性，并在列表和计数 SQL 中共用参数化 `EXISTS`，不把名称文本拼入 SQL，也不依赖客户端控制关联开关。

## 5. 商品搜索契约与 ES 特有边界

用户端 `/product/search` 保留旧标量方法签名和表单参数，避免破坏步骤 02 按真实 Method 建立的精确公开清单。Controller 将参数组成 `ProductSearchRequest` 校验，直接调用 `EsSearchComponent.searchProduct` 时也复用相同 DTO 校验。

- 继续使用 `keyWords`、`priceFrom`、`priceTo`、`sortType`、`sortField`、`pageNo`；不会只改一端强迫现有前端迁移为 `sortDirection`。
- `sortField` 仅接受 `composite/sale/price`，分别映射 ES `_score/totalSale/minPrice`；`sortType` 仅接受 `asc/desc`。省略或空串保持默认综合倒序。
- 关键词非空且最多 100 字；价格为 BigDecimal，允许省略，但已有值必须非负，且下限不得高于上限。
- 每页仍为 15 条。按当前 ES 默认一万条结果窗口，`pageNo` 最大为 666（666×15=9990），667 页即拒绝；这个特定边界比通用数据库列表的 100000 页更严格。
- 输入校验位于原搜索异常兜底之前；非法排序/分页/价格必须返回 600，不能假装搜索结果为空。真实 ES 查询失败的既有兜底行为未在本步全面重构。

## 6. 下单、购物车、AI 与评价

### 下单与购物车

- `PostOrderDTO.orderList` 必填，包含 1—50 个非空明细；`@Valid` 与元素 `@NotNull` 共同保护嵌套对象。
- 每项商品、SKU 非空并限制长度，`buyCount` 为 1—99，备注不超过 300 字；地址与支付方式、订单来源按现有业务枚举检查。
- 重复的“商品 ID＋原 SKU 属性 ID 串”**明确拒绝，不合并数量**。Service 在任何 DAO 调用之前完成 DTO、身份、数量和重复检查，不依赖调用方是否经过 MVC。
- 金额仍按数据库中的 SKU 价格乘以合法数量计算，客户端不能指定成交金额。合法订单的库存扣减逻辑与事务保留；重复 SKU 被拒绝时不写订单、不扣库存、不调用支付。
- 加购物车使用 CartAddRequest，只接收商品、SKU、数量。用户 ID 来自可信身份，购物车 ID、哈希、时间等持久化字段由服务端生成。
- 已有数量与本次增量使用 long 求和，超过 99 在写入前拒绝。调整展示时间放在数量校验后；已有记录以 `cartId＋userId＋旧数量` 条件更新，并发变化则业务拒绝并事务回滚。
- 既有购物车 `(product_id, property_value_id_hash, user_id)` 唯一索引保留，不在本步做表结构迁移，也不声称已完成所有交易并发或幂等问题。

### AI 与评价

- AI 消息非空，最多 1000 字，与现有前端一致。Controller、异步 Chat 入口及 AgentMessageService 共享同一规则；过长消息不会启动模型任务或写入消息。
- 当前 AI 文本入口不新增附件协议；附件/文件参数与 multipart 文件由绑定防线明确拒绝，不假装已经支持附件处理。
- 历史查询分页遵守通用边界；消息取消的 ID 必须为正整数，并继续绑定可信用户身份。
- 评价和追评非空、最多 300 字；初评星级为 1—5。图片保留前端逗号分隔路径协议，最多 5 个非空路径，总字符串最多 300 字；空图片列表允许 null 或空字符串。
- 图片数量和字符串边界不代表已经检查真实文件类型、内容或路径授权；上传/资源安全仍按步骤 05 专项实施。
- 用户端购买数量控件补充最大值 99，减少正常页面提交越界数量；后端仍独立校验，不能依赖控件约束。

## 7. 先复现、再修复的验证证据

日志 `03-before-input.log`、`03-before-sort-ai.log` 用于记录修复前的失败。诊断收集阶段使用 `maven.test.failure.ignore=true` 只是为了继续收集其他模块；该阶段的 BUILD SUCCESS 不能作为通过证据。最终普通验证与 package 均不忽略测试失败、不跳过测试。

已记录的排序范围修改前结果：

| 测试 | 修改前结果 | 检查意义 |
|---|---|---|
| MapperSortBoundaryTest 原 49 项 | 16 失败、33 通过 | 16 个动态排序入口都会接受注入排序文本；默认查询和旧固定排序原本可用 |
| ProductSearchInputTest 10 项 | 10 失败 | 非法排序、分页和价格范围没有按约定抛出业务拒绝 |
| AdminListRequestBoundaryTest 1 项 | 1 失败 | 页面不需要的密码/API Key/内部筛选属性可直接进入 Query |
| PostOrderValidationTest 11 项 / OrderInputBoundaryTest 原 22 项 | 分别 10 / 19 失败 | 嵌套明细、数量及重复 SKU 缺少入口与 Service 共同约束 |
| CartInputBoundaryTest 原 13 项 | 13 失败 | 非法数量、累加越界等输入不能在写入前稳定拒绝 |
| AiMessageInputBoundaryTest 13 项 / CommentInputBoundaryTest 27 项 | 分别 11 / 25 失败 | Service 直接调用缺少消息与评价边界 |
| ChatInputBoundaryTest 3 项 / AiCommentControllerBoundaryTest 原 8 项 | 分别 3 / 5 失败 | 异步入口与 Controller 校验需共同生效 |
| PaginationInputBoundaryTest 10 项 / RequestInputBoundaryTest 原 22 项 | 分别 9 失败 / 19 失败且 1 错误 | 分页、内部字段绑定与校验异常处理缺少保护；错误包含原有异常强转问题 |

首次实现编译发现旧 Query 没有三个前端筛选属性，已补实际 Query/参数化 SQL，而非删除筛选或伪造 getter。Mapper 测试另验证名称原文不出现在 SQL、筛选进入预编译参数，避免只解决编译而仍无实际筛选。

## 8. 最终验证结果

本次使用 JDK 21.0.11、Maven 3.9.14，复用阶段 0 工作区依赖缓存。后端目录运行：

```powershell
./scripts/verify-baseline.ps1 -JavaHome 'C:\Users\CC_Am\.jdks\ms-21.0.11' -MavenCommand 'D:\Driver\apache-maven-3.9.14\bin\mvn.cmd' -Package
```

| 验证 | 当前实际状态 |
|---|---|
| 最终普通测试与全模块 package | 严格 package 成功，403 项测试：common 250、admin 28、web 125，失败/错误/跳过均为 0；common/admin/web/mcp 全部成功，25.850 秒；日志 `03-package-after.log` |
| 管理端前端构建 | 通过，31.24 秒 |
| 用户端前端构建 | 购买控件增加 max=99 后的最终构建通过，10.81 秒 |
| 真实隔离集成 | BaselineSmokeIT 共 9 项全部通过，失败/错误/跳过均为 0；suite 28.251 秒，verify 总计 56.586 秒；日志 `runtime/phase1/step03/03-integration.log` |
| 真实管理端筛选 HTTP | 订单商品名、评论商品名/昵称正常筛选有记录；不可能匹配的名称与带引号/SQL 样式的普通筛选词均返回 200/0 行，未被作为 SQL 执行；证据 `03-admin-filter-http.json` |
| Git diff 检查 | 后端与前端工作区最终 `git diff --check` 均通过 |
| 环境结束状态 | 本轮管理端、合成模型及 `easymall-baseline` 三个隔离容器已停止，七个测试端口均无监听；未删除容器、数据卷或 fixture，未重置数据库；结果见机器可读记录的 `endState` |

真实集成应继续使用阶段 0 的独立 MySQL、Redis、ES、合成用户与本地模型替身，支付用 Mock，MCP 工具为空 Provider；不得使用日常开发数据或真实支付验证拒绝场景。原 fixture 与新产生记录保留，不执行 SQL 重置或删除。

本轮 Docker Desktop 起初未运行，为隔离测试后台启动引擎。收尾只停止本轮启动的合成模型、管理端与隔离容器，保留数据、容器和卷，没有主动关闭 Docker 引擎或开发容器。用户追问后再次只读检查时，Docker 的 Linux Engine 管道已不可用，未为查看状态再次启动引擎；这次不可用不改变此前已完成的测试和隔离容器停止记录。

## 9. 范围与后续边界

- 本步不改变密码协议、不新增权限管理平台、不替换 Redis Token、不引入新的数据库结构。
- 排序防线不代表 Mapper 所有动态 SQL 或整个项目已经完成安全审计；后续新增可排序字段需显式补固定映射和测试，不能恢复原始 SQL 文本绑定。
- SKU 属性 ID 顺序、交易幂等、支付与退款状态、可靠任务、MCP 可信工具身份等仍按后续步骤推进。
- 既有大小写搜索、取消接口写 CLOSED(5)、消费者退出重试等问题不在本步顺带修复；不能将测试通过解释为这些问题已解决。
- 浏览器页面点击未人工验收；前端构建、接口契约和独立协议测试不代替完整浏览器交互或并发性能评测。

## 10. 额度中断后的完整性复核（2026-10-28）

用户担心额度不足导致上次任务未完成，本次重新核对 `8a40eec` 到工作区的代码变化、最终日志、XML 报告和机器记录，并重新执行普通测试、后端打包与两前端构建。

结论：上次步骤 03 的实现和验证已经完整结束，未发现因中断留下的代码或验证缺项。本次没有调整业务代码，只补充复核记录；工作区的原有修改继续保留。

| 本次复核项 | 实际结果 |
|---|---|
| 当前代码的普通测试 | 重新执行 403 项：common 250、admin 28、web 125；失败、错误、跳过均为 0，且只统计本次新生成的 XML 报告 |
| 后端 package | common/admin/web/mcp 全部成功，退出码 0；44.196 秒，未忽略失败或跳过测试 |
| 用户端前端 | 重新构建成功，退出码 0；28.94 秒 |
| 管理端前端 | 重新构建成功，退出码 0；31.22 秒 |
| 前端产物核对 | 两个新 `index.html` 的 SHA256 均与上次记录一致，输出至独立目录，没有清空或覆盖原 `dist/` |
| 上次隔离集成 | 最终日志含 BUILD SUCCESS，XML 与机器记录的 9 个用例逐项一致；9 项完成、失败/错误/跳过均为 0，无超时；本次未重新启动 Docker 或重跑隔离集成 |
| 代码完整性 | 16 个 Mapper 排序白名单、六类后台列表 DTO、分页防绑定、订单/购物车/AI/评价业务校验及前后端参数兼容均已复核，未发现阻塞项 |
| 环境 | 本次未启动 Docker、管理端或模型替身；检查的七个隔离测试端口均无监听，没有执行删除或数据库重置 |

本次原始日志与汇总位于工作区根 `runtime/phase1/step03/recheck-20261028/`，其中 `results.json` 保存时间、数量、退出码及产物哈希。构建时间受本机负载影响，不作为性能优化前后对比。浏览器点击与压力测试仍未单独验证。
