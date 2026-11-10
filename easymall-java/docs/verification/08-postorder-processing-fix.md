# postOrder 一直返回 PROCESSING 的修复

日期：2026-11-09（Asia/Shanghai）。本次只修复下单请求读取与失败恢复，不处理支付宝沙箱收银台的 SYSTEM_ERROR。

## 原因与证据

开发库只读检查发现：目标请求已经预留，但没有对应订单，租约已过期，没有正在等待的行锁。

真正原因是原来的幂等服务没有隔离好 MyBatis 一级缓存。正式 `postOrder` 使用 `@Transactional(NOT_SUPPORTED)`，虽然没有实际业务事务，仍可能存在 Spring 事务同步上下文，MyBatis 会在此上下文中复用 SqlSession：

1. 第一次查询不存在的请求，当前 SqlSession 缓存空结果。
2. `REQUIRES_NEW` 挂起外层同步上下文，在另一个 SqlSession 中插入并提交预留记录。
3. 返回外层后，再查相同请求仍拿到之前的空结果。
4. `requireSame` 抛出“下单请求尚未确认”，业务建单事务还没有开始，因此预留记录仍为 PROCESSING。
5. 后续 HTTP 重试看到有效租约，就返回 PROCESSING，且没有订单、金额和支付页面。

新增回归测试在真实 MySQL 上重建相同的 NOT_SUPPORTED 边界，修复前稳定触发上述 BusinessException。此前测试直接调用服务，没有模拟这一事务同步上下文，漏掉了这个场景。

## 具体改动

- `OrderRequestService.find` 使用独立短事务读取，避免命中外层会话保存的空结果或旧状态。
- 预留插入及随后的读取放进同一独立事务，返回已确认的预留快照。
- 并发插入冲突、接管冲突和业务回滚后的读取统一使用新的查询边界。
- 当前处理者的业务事务因锁冲突或查询超时回滚后，条件释放自己的租约，返回 RETRY；接管未成功时不会释放其他处理者的租约。
- 保留现有 requestId、请求摘要、固定 payOrderId、条件库存 SQL 和业务事件的事务规则。
- 同步更新测试中的依赖字段名，与 review 后的 `orderRequestService`、`businessTaskService` 命名一致。

没有新增表或字段，没有新的迁移 SQL，也不需要删除或手动修改已有 order_request 记录。没有更新开发库的订单、库存或请求状态。

## 新增测试（仅测试，不参与正式业务）

位置：`easymall-common/src/test/java/com/easymall/task/OrderCheckoutMySqlIT.java`。

| 场景 | 验证结果 |
|---|---|
| 在正式 NOT_SUPPORTED 边界内首次提交 | CREATED、READY、生成测试支付页面；只创建一单、扣一次库存、写一次事件 |
| 同一事务同步上下文中先查不存在，再提交、查询及重试 | 读取最新 CREATED，重试沿用原支付号，不重复建单 |
| 修复前遗留的 PROCESSING 已到期 | 允许原请求接管，保留原支付号，创建一单并生成测试支付页面 |
| 另一连接持有合成 SKU 行锁，真实库存更新 SQL 超时 | 订单和库存事务回滚，释放当前租约并返回 RETRY；解锁后原请求重试成功 |

支付和 Redis 使用测试替身，未请求真实支付网关。写入仅发生在固定隔离库 `127.0.0.1:13306/easymall_test`，合成记录保留，未清表。验证结束后隔离 MySQL 已恢复停止状态。

## 验证结果

- 下单定向验证：70 项通过，其中 20 项真实 MySQL 集成测试，0 失败、0 错误、0 跳过。
- 后端普通测试：550 项通过（common 376、admin 35、web 135、mcp 4）。
- 四个后端模块打包通过。
- Web 前端构建通过；产物写入忽略的 runtime 诊断目录，没有清空或覆盖原 dist。
- `git diff --check` 通过。

证据日志在工作区忽略目录 `runtime/diagnostics/`：

- `postorder-boundary-before.log`：修复前的可复现失败。
- `postorder-checkout-final.log`：70 项下单验证通过。
- `postorder-package.log`：后端测试和打包。
- `postorder-front-build.log`：前端构建。

## 本地复查

1. 重新构建并重启 IDEA 中的 `EasyMallWebApplication`，使进程加载修复后的类。
2. 保留原购买内容，在原结算页点击“查询提交结果”。遗留记录的租约已过期时应返回 RETRY。
3. 点击“提交订单”，前端会对相同购买内容复用原 requestId；正常应返回 CREATED，并携带金额和订单号。
4. 支付页面生成成功时为 READY；页面被浏览器拦截时可点击“打开支付页”。渠道不可用时应为 CREATED + UNAVAILABLE，从原订单继续支付。
5. 核对只有一组订单、一次库存扣减和一个 ORDER_CREATED 事件。

上述本地复查需要运行中的 Web 后端加载新代码。本轮没有重启用户正在运行的 IDEA 进程，也没有替用户点击真实下单或支付。
