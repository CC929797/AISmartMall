# 支付排查改动复盘与补测记录

本文记录步骤08首次反馈支付异常后的修复、2026-11-10暂停渠道验证的原因，以及2026-11-12的恢复补测。时间均为北京时间。

**2026-11-12，步骤08支付排查补充已完成。**用户在恢复后的支付宝沙箱完成一笔付款，开发库查询确认对应下单请求已创建、订单金额和归属一致，商城订单已发货。真实通知、关单竞态及退款专项验收见第7节。

## 1. 2026-11-10 暂缓原因

- 浏览器进入支付宝沙箱 `appAssign.htm` 后出现 `SYSTEM_ERROR`、HTTP 404 或页面标注 404 的默认错误页，未完成正常收银台付款。
- 商城后台查单及独立测试类均返回 `20000 / aop.ACQ.SYSTEM_ERROR`；使用实际项目 APPID 查询故意不存在的订单，也返回相同错误，没有得到 `trade_status`。
- 本地核对通过：私钥与应用证书匹配、APPID 与应用证书匹配、应用和支付宝公钥证书在有效期内、SDK 可加载证书。此结果不等同于确认支付宝后台登记配置或平台服务正常。
- 用户反馈老师独立部署的项目也出现沙箱跳转页错误，并于 2026-11-10 提供支付宝开放平台社区截图，显示多名用户反馈沙箱系统异常和不可用。用户据此决定暂按外部沙箱异常阻塞处理。
- 社区反馈和跨部署复现支持优先核查沙箱服务/共享应用，尚未取得官方根因说明；不把“多人共用账号触发限流”登记为已经证实的原因。

当时收银台与查单异常阻塞了真实渠道验证。上述记录对应2026-11-10的排查，恢复后的实测证据见第5节。

## 2. 正式业务改动：排查期间确实修改了什么

### 后端

| 改动 | 位置 | 原因与效果 | 对后续的影响 |
|---|---|---|---|
| 修复下单一直停在 PROCESSING | `easymall-common/src/main/java/com/easymall/order/OrderRequestService.java` | 步骤08新增幂等逻辑未隔离外层 MyBatis 会话缓存，插入提交后可能仍读到旧空值。现在查询使用独立短事务，预留插入和读取在同一独立事务内完成；业务回滚后条件释放本处理者租约 | 属于已复现并修复的本地代码缺陷。保留 requestId、摘要、固定 payOrderId 和库存事务规则，后续沿用修复后的读取边界；详见 [修复记录](08-postorder-processing-fix.md) |
| 补齐正式支付通知 Controller 和精确匿名入口 | `easymall-web/src/main/java/com/easymall/controller/notify/AlipayNotifyController.java`、`WebAuthenticationInterceptor.java` | 接收 `POST /api/notify/alipayNotify`，只放行该精确回调；验签或业务处理失败返回 `failure`，成功返回 `success`，重复参数拒绝 | 是提前补入的步骤10前置代码，不是测试类。后续在此基础上补齐渠道联调与竞态，不能再重复新增同名入口或扩大匿名范围 |
| 修正通知验签、身份和渠道金额传递 | `PayChannel4Alipay.java`、`PayOrderNotifyDTO.java` | 使用支付宝公钥证书及 `rsaCertCheckV1` 验签，核对 app_id；成功/交易完成状态传递渠道金额，主动查单也携带确认金额；系统错误保持未确认 | `PayOrderNotifyDTO.amount` 是 DTO 属性，**不是数据库字段**。后续测试替身或新适配器调用 `payOrderSuccess` 必须提供正确金额；仍需真实通知验证，不能宣称已完成全部商户身份/渠道契约核验 |
| 付款确认增加事务、金额核对和重复通知保护 | `OrderInfoServiceImpl.payOrderSuccess` | 按支付组核对金额，条件更新 WAIT_PAYMENT → PAID；无状态变化不重复触发发货，Redis 发货通知在事务提交后执行 | 金额缺失/不符会被拒绝，这是预期的业务约束。Redis 通知失败目前仅记录日志，**持久化补偿仍留在步骤10/11B**；提交后回调不等于可靠任务闭环 |
| 改为显式配置异步通知地址 | `AppConfig.java`、`PayChannel4Alipay.getPayUrl`、商城端 `application.yml` | 不再从占位 `project.domain` 拼接回调地址；仅配置了 `alipay.notify-url` 才发送通知地址。模板中的环境变量示例目前处于注释状态，当前开发 YAML 未填写该项 | 后续验证真实回调时必须显式提供可达的正确通知地址，可用 `ALIPAY_NOTIFY_URL` 对应配置；本次没有配置公网地址。未配置时本地付款确认依靠主动查单，查单失败不能当作已付款 |

上述改动中，PROCESSING 缓存缺陷是代码自身的问题，不能用沙箱异常替代其修复理由。通知验签与金额核对也是正式业务修复，并非为了让测试通过而添加的假支付流程。

### 前端

实际前端源码位于 `D:/java/java24/LLstudy/mall/easymall-front/easymall-front-web`。

| 改动/当前行为 | 位置 | 原因与效果 | 对后续的影响 |
|---|---|---|---|
| 兼容原版 Mall 的成功响应 | `src/utils/CheckoutIntent.js` 的 `isCheckoutCreated`、`src/views/product/ProductBuy.vue` | 原版响应只有 payInfo/payOrderId/amount，没有 requestStatus；前端原判断把缺失字段误判为未完成。现在仅缺失该字段且旧版支付信息完整时认定下单已创建，新版显式状态优先 | 这是已修复的前端兼容缺口。该兼容只覆盖初次下单成功响应，不代表原版后端支持全部新版查询/重试接口，也不会给原版后端新增幂等能力 |
| 保留步骤08的请求恢复与支付弹窗 | `ProductBuy.vue`、`OrderPay.vue`、`CheckoutIntent.js` | 一次购买复用 requestId，区分 PROCESSING/CREATED/RETRY；已建单但链接不可用时可继续原订单付款。弹窗仍使用渠道 HTML 表单、新窗口写入和关闭文档的方式 | 主体来自步骤08，不能把整个未提交差异都算成本轮支付排查新增。后续继续保持“订单创建成功 ≠ 支付成功”，不把前端按钮当作付款凭据 |
| 保留步骤08的购物车和接口配套 | `ShopCart.vue`、`src/utils/Api.js` | 创建成功后刷新购物车、选择去重；增加购买请求结果查询接口 | 仍要求新版前后端配套部署；本轮没有重写页面布局或更换支付提供方 |

`OrderPay.vue` 的“我已支付”读取商城订单状态；它不直接代替支付宝验签或将订单标成已付款。临时浏览器表单对照没有把模拟页面替换进正式前端。

## 3. 仅测试/诊断的新增内容

- `easymall-web/src/test/java/com/easymall/diagnostics/AlipaySandboxQueryProbe.java`：人工独立只读查单，复用本地证书配置；`--check` 仅加载配置/证书，不联网。带测试专用注释，不由普通 Maven 测试自动运行，不属于正式业务启动流程。
- `AlipaySandboxQueryProbeTest`：4 个使用合成配置和模拟客户端的单元测试，不访问支付宝。
- `AlipayGatewayContractTest`、`AlipayPaymentNotificationTest`、`AlipayNotifyControllerTest`：验证请求参数、证书验签和入口应答等本地行为；模拟客户端/生成的测试签名不代表真实供应商验收。
- `OrderCheckoutMySqlIT` 中的缓存边界、锁超时、重复付款确认和金额错误用例：使用隔离 MySQL 与支付替身，不发生真实付款。
- 前端 `tests/checkout-components.test.mjs`、`checkout-intent.test.mjs`：补充旧版响应、显式状态优先和实际支付组件接收表单的回归；浏览器夹具也使用合成接口。
- 工作区忽略目录 `runtime/diagnostics/` 中的原版对照、证书核对、日志和临时构建结果：诊断用途，不接入正式 Worker 或支付入口。

## 4. 数据库、依赖与部署边界

- **支付排查期间未新增数据库表或现有表字段，无新增迁移 SQL。** `business_task` 属于先前11A，`order_request` 属于先前步骤08，不能重新算成支付故障排查新增；开发库是否已执行这些前置迁移应以实际结构为准。
- 本轮未替换正式支付宝 SDK、Spring 框架或加密依赖；SDK 仍为 `4.40.576.ALL`，页面支付仍为 `pageExecute`，证书查单仍为 `certificateExecute`，RSA2 和支付产品码保持原调用约定。旧 BC 对照只发生在临时诊断进程。
- 没有修改原版 `D:/java/java24/LLstudy/mall/easymall-java` 参考源码；对照编译发生在工作区忽略目录。
- 没有把测试订单手动标成已付款。此前通过正常商城接口产生的两笔诊断订单已经按正常业务流程关闭，历史记录见 [交接记录](08-payment-restoration-handoff.md)。
- 这段工作仍包含未提交的正式源码、测试及文档。当前 Git 差异还包含步骤08原有实现，不等于“支付出错后改了全部这些文件”；后续提交时需按实际范围审阅。

## 5. 已有验证与尚未验证的部分

下面是各次记录中的结果，**不是本次复盘重跑，也不应直接把数量相加**：

| 验证批次 | 已有结果 | 原始记录/边界 |
|---|---|---|
| 支付通知前置修复后的后端验证 | 普通测试564项、四模块打包通过；下单定向72项通过，其中22项真实隔离MySQL测试 | `runtime/diagnostics/payment-restoration-package-final.log`、`payment-restoration-mysql.log`；真实渠道使用替身 |
| 原版响应前端兼容修复 | 用户端97项测试通过、隔离输出构建通过 | `runtime/diagnostics/legacy-checkout-after.log`、`legacy-checkout-build.log`；含7个新增回归，不代表真实支付宝可付款 |
| 独立查单工具 | 4项模拟测试、编译、实际配置的本地 `--check` 通过 | `runtime/diagnostics/alipay-query-probe-tests.log`、`alipay-query-probe-local-check.log` |
| 本地签名材料核对 | 私钥/应用证书、APPID/证书、证书有效期和SDK初始化通过 | `runtime/diagnostics/alipay-local-consistency-20261110.log`；不联网 |
| 真实沙箱单笔付款 | **2026-11-12 补测通过** | 用户完成沙箱付款，开发库 `easymall2` 中该订单已发货，支付组与下单请求核对一致 |

2026-11-12 12:56:32 创建的测试订单 `20261112125631WVRTOPFA5KPSHDOL` 对应支付单 `856106451810273506628390039162`。当天的开发库只读查询核对到一张主订单，`order_request.status=CREATED`，金额与 `order_info` 的 ¥35.90 一致，用户归属一致。`lease_owner`、`lease_until`、`last_error` 均为 `NULL`，主订单 `order_status=2`（已发货）。本次验收以用户的沙箱付款测试及上述数据库查询结果为依据。

`08-results.json` 保留2026-11-08初次步骤08验收快照。当前单笔沙箱付款补测状态以本记录和路线图为准。

## 6. 对后续步骤的影响

1. **步骤09可以继续实现退款 CAS、退款单状态、幂等与 UNKNOWN 恢复。** 本地用合成已付款数据和支付替身验证，真实退款用已付款的沙箱交易核对渠道结果。
2. **步骤10已提前实现部分通知入口、验签和金额检查，整步仍未验收。** 商户身份字段完整核验、支付/关单并发、结果不确定时的补偿、付款成功可靠事件、真实渠道应答仍需完成；后续在现有入口上继续，不重建第二套回调。
3. **步骤11B仍需接入真实可靠任务处理器及补偿。** 付款后Redis通知失败尚无持久化重投；现有ORDER_CREATED/ORDER_CANCELLED事件也不能描述成已经覆盖订单全生命周期的Worker工作流。
4. **开发环境开启主动查单。** `project.auto-checkpay=true` 时，运行中的商城会轮询待付款订单。离线测试使用模拟适配器。人工查单工具由测试人员单独运行。

## 7. 渠道补测清单

- [ ] 原配置单次查单获得正常业务响应；故意不存在订单能给出可解释的业务结果，已创建交易可查询状态。
- [x] 完成一笔新版商城的真实沙箱付款，核对原 `payOrderId`、支付组金额、`order_request` 状态及商城发货状态。
- [ ] 分别覆盖单品直购和购物车的沙箱付款路径，并记录各自的交易和金额结果。
- [ ] 真实异步通知到达精确入口，验签、app_id、金额和订单归属核验通过；订单仅在服务端可信确认后更新。
- [ ] 重复通知、通知与主动查单同时到达时只确认一次；核对发货通知/可靠任务效果。持久化发货补偿需在步骤10/11B实现后验收。
- [ ] 关闭窗口、浏览器拦截、返回页面、响应丢失和继续付款仍复用原订单；不重复建单、扣库存或更换支付组。
- [ ] 支付与取消/超时关单竞态、渠道超时/系统错误、晚到通知均有可解释状态和补偿路径；这是步骤10待完成内容，不能只补一张成功截图。
- [ ] 步骤09实现后验证真实沙箱退款与原退款业务号查询，确认UNKNOWN恢复和重复请求无重复资金效果。
- [x] 记录本次单笔用例的时间、测试单、金额与商城状态，并同步步骤08进度。
- [ ] 步骤09、10专项测试结束后补齐对应的渠道响应和验收记录。
