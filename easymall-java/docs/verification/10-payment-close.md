# 步骤 10 支付通知、关单竞态与可靠履约

## 改动与原因

- 取消和超时按支付单号锁定整组订单，先写入 `8`（取消确认中）或 `9`（超时关单确认中）。退款、发货仍沿用步骤 09 的主订单行锁。支付宝关单和查单在数据库事务之外执行。
- 确认可以关单后，在同一事务将订单改成 `4`（已取消）或 `5`（已关闭）、归还库存并写入 `ORDER_CANCELLED`。收到可信付款结果时，待确认订单可以转为已付款，保留已经占用的库存。
- 付款表单使用固定截止时间，在行锁内重新确认仍可付款并登记签发后才交给浏览器。明确未签发时可直接关单。支付宝沙箱中，用户打开付款入口后离开可能导致渠道一直查无交易。整组表单已过期至少 60 秒，且关单与查单均明确返回 `40004 / ACQ.TRADE_NOT_EXIST` 时，可复用同一关单事务完成本地收尾。支付截止时间缺失、签发情况不明、网络超时或某个渠道响应不明确时，继续保留库存。真实支付仍要求可信的渠道终态。
- 支付宝异步通知沿用 `POST /api/notify/alipayNotify`。以支付宝公钥证书验签，核对 `app_id`、`seller_id`、商户订单号、支付宝交易号、交易状态和支付组金额。主动查单校验返回的商户订单号，与异步通知共用 `payOrderSuccess()` 确认付款。 [支付宝电脑网站支付接入说明](https://s.apifox.cn/apidoc/docs-site/3618910/doc-3271411)
- 同一支付组的已付款状态和 `ORDER_PAID` 任务在同一事务写入。重复通知核对同一支付宝交易号后应答成功。Web 中的 11A Worker 只领取 `ORDER_PAID`。默认发货由管理端处理。设置 `logistics.mode=simulated` 时，处理器会使用已有发货入口进行模拟发货。已发货主单会在重试中跳过，未决退款会延后一轮且不消耗失败次数。
- 超时关单和补偿改由数据库扫描。主动查单按支付单号分批，单笔异常不会中断同批后续查单。

## 数据库迁移与启动

先确认目标库已经执行 11A、08、09 的迁移。停止同一数据库上的旧版实例，在 `SELECT DATABASE()` 确认选中的商城库执行一次 [V20261115_01__payment_close.sql](../../db/migration/V20261115_01__payment_close.sql)，然后重新启动新版应用。该脚本只改 `order_info`，增加三个带注释的字段、两个索引，并补充订单状态注释。历史行新增列默认 `NULL`。应用不会自动执行 DDL。

```sql
SELECT DATABASE();
SHOW FULL COLUMNS FROM order_info LIKE 'payment_%';
SHOW FULL COLUMNS FROM order_info LIKE 'order_status';
```

现有开发库结构以实际 `SHOW CREATE TABLE order_info` 为准。脚本按仓库基线保留 `order_status` 的 `TINYINT(1) DEFAULT NULL`。

真实沙箱回调需要配置 `ALIPAY_SELLER_ID`（沙箱收款商户 PID）和 `ALIPAY_NOTIFY_URL`（支付宝可达的 HTTPS 地址，路径为 `/api/notify/alipayNotify`）。本地仍可通过 `project.auto-checkpay` 用主动查单确认支付。若专项验证回调，可暂时关闭主动查单，用新订单查看最终状态是否由回调推进，避免混淆来源。支付私钥、测试买家密码和签名不写入文档。

模拟物流由 `logistics.mode` 控制。Web 和管理端的公共配置默认为 `disabled`，关闭时可用管理端发货，由用户确认收货。本地用 `dev` Profile 测试时，可在 `application-dev.yml` 设为 `simulated`。付款超时和关单补偿查 MySQL，发货后生成模拟轨迹和自动确认收货仍使用 Redis 延迟队列。

## 验收标准与复测步骤

沙箱手工测试每个场景使用一笔新订单，从浏览器 Network 的 `postOrder` 响应记录 `data.payOrderId`。本次联调用 `dev` 启动 Web，付款期限 5 分钟，开启主动查单和模拟物流，模拟发货后自动确认收货的延迟为 15 分钟。可用 [独立查单工具](alipay-query-probe.md) 查询沙箱交易状态。

| 场景 | 操作 | 通过条件 |
| --- | --- | --- |
| 支付成功与履约 | 下单，使用沙箱账号付款，刷新订单并查看物流。 | 商城保存支付宝交易号，同一支付单只有一条 `ORDER_PAID`。启用模拟物流时自动发货、生成轨迹，随后可确认收货。自动发货较快时，页面可能从 `0` 直接显示 `2`，数据库已写入过付款结果。关闭模拟物流时，由管理端处理发货。 |
| 支付宝已建单，手动取消 | 进入收银台，确认查单返回 `WAIT_BUYER_PAY`，在付款截止前从商城取消。 | 渠道确认未付款且已关单后，同一支付单下的主订单变为 `4`（交易取消），只生成一条 `ORDER_CANCELLED`，每个 SKU 的库存只归还一次。 |
| 已签发表单，支付宝未建单，手动取消 | 打开付款入口后停在沙箱登录前，查到「交易不存在」。在付款截止前点取消。 | 先进入 `8`，截止前及截止后 60 秒内保留库存。宽限期结束后，同一轮关单和查单均明确返回 `40004 / ACQ.TRADE_NOT_EXIST` 时，变为 `4` 并归还库存。 |
| 已签发表单，支付宝未建单，超时关单 | 打开付款入口后停在登录前，留在待付款状态。也可在到期前停止 Web，到期后启动，验证数据库扫描能接着处理。 | 到期后后台按支付组进入 `9`。宽限期结束后，同一轮关单和查单均明确查无交易时，变为 `5`（交易关闭），清空 `payment_close_check_at` 并归还库存一次。恢复旧 `8` 状态时最终仍变为 `4`。 |
| 表单从未签发或渠道结果不明确 | 使用隔离 MySQL 与支付替身分别模拟生成表单失败、单边查无交易、空响应或已付款。 | 明确未签发表单的待付款订单到期后可直接收尾。结果不明确时保留 `8` 或 `9` 和库存。拿到有效付款凭证时确认支付，保留已扣库存。缺少固定截止时间的历史单继续等待渠道终态。 |
| 真实支付宝异步通知 | 配置沙箱 `ALIPAY_SELLER_ID` 和支付宝可达的 HTTPS `ALIPAY_NOTIFY_URL`，暂时关闭 `project.auto-checkpay`，用新订单付款。 | 服务端实际收到 `/api/notify/alipayNotify`，验签和应用、商户、金额校验通过，返回纯文本 `success`。同一支付单只写入一条 `ORDER_PAID`。测完恢复主动查单。 |
| 旧付款入口有效期 | 保存尚未付款的原表单或收银台页，分别在渠道已确认关单后、以及未建单订单过期且商城已收尾后，用沙箱验证原入口。 | 原入口无法继续完成付款，原支付单号没有新增成功付款。此项单独检查支付宝的实际行为。 |

`付款截止时间 + 60 秒` 是查无交易收尾的最早时点。后台还要等到 `payment_close_check_at` 再发起查证，页面可能多等一个查证周期。例外收尾仅在沙箱启用。真实支付仍由渠道终态驱动。 [支付宝关单接口](https://aipay.alipay.com/docs/vibe-pay/ai-web-app-payment-qianyi/api-list/alipay-trade-close.html)

## 测试记录

### 自动化

- 2026-11-15，105 项定向测试（含 36 项隔离 MySQL 集成用例）、574 项后端常规测试及用户端生产构建通过。
- 2026-11-22，关单修复相关的 63 项单元测试、22 项下单 MySQL 集成测试和 10 项支付生命周期 MySQL 集成测试通过。集成测试使用 MySQL 8.0.43 和渠道替身，未访问支付宝。
- [支付生命周期集成测试](../../easymall-common/src/test/java/com/easymall/task/PaymentLifecycleMySqlIT.java) 覆盖十次并发确认只产生一条付款任务、付款先于关单获确认时保留库存、未签发表单到期可从数据库完成关单、沙箱查无交易时过宽限期收尾、任一渠道结果不明确时不释放库存、重复扫描只归还一次，以及任务写入失败时回滚付款状态。
- [支付宝通知单元测试](../../easymall-common/src/test/java/com/easymall/service/AlipayPaymentNotificationTest.java) 和 [渠道契约测试](../../easymall-common/src/test/java/com/easymall/service/AlipayGatewayContractTest.java) 覆盖验签、金额篡改、错误应用或商户、非成功支付状态、关单与查单结果识别。[发货任务测试](../../easymall-web/src/test/java/com/easymall/component/PaidOrderHandlerTest.java) 覆盖关闭模拟物流、发货失败后重试、跳过已发货订单和未决退款时延后。

### 2026-11-22 沙箱手工记录

时间均为北京时间。订单号仅记尾号，结果已用页面、支付宝查单或本地数据库复核。

| 场景 | 订单尾号 | 实际结果 |
| --- | --- | --- |
| 正常付款、模拟发货、收货完成 | `ROWZ0ISTD` | 16:25:59 写入付款任务。数据库保存支付宝交易号和运单号，`ORDER_PAID` 为 `SUCCESS`，订单最终为 `3`（已完成）。 |
| 付款期限内手动取消已建单的交易 | `H47CB5I9A` | 独立查单返回 `WAIT_BUYER_PAY`。在 17:10:27 付款截止前取消，17:08:51 落库为 `4`，存在一条 `ORDER_CANCELLED`。 |
| 修复前待确认的手动取消恢复 | `G7PFBKWNI` | 两个渠道接口持续返回「交易不存在」。新版 Web 启动后，从 `8` 推进到 `4`，17:34:34 写入 `ORDER_CANCELLED`。 |
| 修复前待确认的超时关单恢复 | `V84JBSEE3` | 新版 Web 启动后，从 `9` 推进到 `5`，17:34:34 写入 `ORDER_CANCELLED`。 |
| 修复后新单，未登录沙箱，手动取消 | `ZT69V15VD` | 17:39:58 付款截止，17:40:58 宽限期结束。下一轮于 17:41:24 确认关单和查单均返回「交易不存在」，从 `8` 推进到 `4`，查证时间清空，存在一条 `ORDER_CANCELLED`。 |

Web Worker 目前只领取 `ORDER_PAID`。上述 `ORDER_CANCELLED` 为 `READY`，表示关单事务已登记事件，等待后续消费方接入。库存归还一次已由隔离 MySQL 用例验证，本轮手工记录未单独采集下单前后的库存值。

仍待沙箱复测的两项是**关闭主动查单后独立验证支付宝异步通知**，以及**保留旧付款入口并验证关单后或表单过期后无法继续付款**。

## 数据库核对

取消场景分别在下单前、下单后、待确认时和关单完成后记录同一个 SKU 库存。例如起始 `10` 件，下单购买 `1` 件后为 `9`，待确认时仍为 `9`，关单完成后恢复到 `10`。正常付款及发货后仍为 `9`。在没有其他订单并发修改该 SKU 时比较这些值。

```sql
SET @pay_id = '填本次测试的 payOrderId';

SELECT order_id, order_status, pay_order_id, channel_order_id,
       payment_expires_at, payment_link_issued, payment_close_check_at
FROM order_info WHERE pay_order_id = @pay_id ORDER BY order_id;

SELECT task_type, status, attempts, next_run_at, last_error
FROM business_task WHERE biz_key = @pay_id ORDER BY created_at, task_id;

SELECT i.product_id, i.property_value_id_hash, SUM(i.buy_count) AS purchased_quantity,
       s.stock AS current_stock
FROM order_info o
JOIN order_item i ON i.order_id = o.order_id
LEFT JOIN product_sku s ON s.product_id = i.product_id
                       AND s.property_value_id_hash = i.property_value_id_hash
WHERE o.pay_order_id = @pay_id
GROUP BY i.product_id, i.property_value_id_hash, s.stock;
```
