# 步骤11A：通用可靠任务基础设施

日期：2026-11-07。执行顺序：07 → **11A** → 08 → 09 → 10 → 11B。

## 为什么先做11A

后续订单超时、支付补偿和商品同步需要在数据库提交时可靠地记录“还要做什么”。只在内存中启动异步线程，进程退出后任务会丢失；先写数据库再单独推Redis，会存在业务已提交但任务未投递的窗口；先删除队列任务再执行业务，失败后可能没有重试依据。

11A提供通用数据库任务记录和独立Worker。现有商品、支付、Redis消费者尚未切换，不能把本次结果解释为这些业务链路已消除丢任务问题。

## 已实现的行为

| 场景 | 处理方式 |
|---|---|
| 业务提交/回滚 | `enqueue` 必须加入现有可写业务事务；业务行和任务一起提交、一起回滚；没有事务或只读事务直接拒绝 |
| 重复投递 | `(task_type,biz_key,biz_version)` 唯一；JSON对象键排序后计算SHA-256，同事件同载荷返回原任务，不改变原计划时间；不同载荷报错 |
| 载荷边界 | JSON对象，输入不超过64 KiB，嵌套不超过32层；拒绝重复键和尾随JSON；保留整数和小数精度；诊断字符串不包含载荷/业务主键 |
| 并发领取 | MySQL `READ COMMITTED` 短事务，`FOR UPDATE SKIP LOCKED`；领取提交后才调用处理器，不占用业务行锁等待外部服务 |
| 过期恢复 | 每次领取生成新租约标识并增加attempts；续租、成功、失败均要求当前标识且租约未过期；旧执行者不能复活租约或覆盖新结果 |
| 失败重试 | 默认最多3次执行，指数退避并封顶；耗尽进入DEAD；最后一次执行崩溃也会在租约到期后进入DEAD |
| 人工重试 | 内部 `listDead/findById/retryDead`，只允许DEAD条件重试，重置本轮次数；保留原错误码直到成功；11B再提供带管理员权限的HTTP入口 |
| 资源控制 | 独立JVM、有限线程和批次；许可同时限制运行中及已领取数量；有界交接队列；独立轮询/续租线程；无共享AI线程池 |
| 停机 | 停止新领取，宽限期内等待处理器完成；超过宽限后撤销本地结果写入资格，保留记录由租约恢复 |

状态流转：`READY → RUNNING → SUCCESS`；失败为 `RUNNING → RETRY → RUNNING`，次数耗尽为 `DEAD`；租约过期的RUNNING可被再次领取。延迟和租约都由数据库UTC时间计算。

这是“至少一次执行”，不是外部副作用的恰好一次。处理器可能在外部调用已完成、任务成功标记尚未提交时崩溃；超时或中断也不能保证外部调用停止。11B必须为真实处理器增加业务幂等、同对象串行与外部条件版本写入/有效版本校验。本次没有声称避免重复发货、重复库存恢复或旧索引覆盖。

## 代码入口与使用约定

- `easymall-common/src/main/java/com/easymall/task/`：任务模型、事务服务、配置、独立Worker、处理器接口和启动入口。
- Mapper与XML在上述目录的 `persistence/` 及对应资源路径。
- `db/migration/V20261107_01__business_task.sql`：新增任务表。
- `config/local/worker.example.yml`、`scripts/run-task-worker.ps1`：独立Worker配置和启动命令。

后续业务投递示例（示意，不会自动执行）：

```java
@Transactional(rollbackFor = Exception.class)
public void updateBusiness(...) {
    businessMapper.update(...);
    tasks.enqueue(new TaskSubmission(
        "BUSINESS_EVENT", businessKey, version, jsonPayload, Duration.ZERO));
}
```

同一事件的载荷应稳定，保存必要ID/版本即可，避免凭据和无关个人信息。延迟接受0至365天的整秒值。业务事务管理器必须对应同一业务DataSource；不要在HTTP控制器中脱离事务调用投递。后续需在独立入口的配置中显式导入业务处理器；普通商城组件扫描不会自动把全部业务组件装进Worker。

商城API进程只注册任务服务和Mapper，不启动新Worker，也不会在启动时查询新表。独立入口只装配JDBC/MyBatis和任务设施，不扫描商城HTTP、Redis、ES、模型及旧任务消费者。复用Admin归档只是复用运行依赖，实际主类为 `BusinessTaskWorkerApplication`。11A唯一内置处理器 `SMOKE_NOOP` 无业务副作用，必须显式 `-Smoke` 开启且只允许隔离库；未注册处理器时Worker启动失败。

独立入口显式使用 `logback-business-task.xml`，日志默认写到 `target/business-task-worker/logs/business-task.log`（`TASK_LOG_DIR` 可覆盖目录），不继承Admin的日志路径。商城三端和Worker均将新任务Mapper日志固定为WARN，避免根日志级别调试时输出JSON参数。

## 数据库迁移边界

本轮只启动既有隔离MySQL容器，并在 `127.0.0.1:13306/easymall_test` 应用任务表脚本。实际 MySQL **8.4.9**、InnoDB；已核对主键、事件唯一约束和4个CHECK约束。到期、租约各有专用索引。

脚本要求 MySQL 8.0.16+，没有 `IF NOT EXISTS`，避免同名旧结构被默默接受。DDL隐式提交，不应视为可通过业务事务回滚。仅新增表，不修改原业务表和旧Redis任务；开发库尚未执行。目标环境应先检查同名表、版本、磁盘空间和权限，执行脚本后核对字段/索引/约束，再启用业务投递。回退关闭新投递/Worker并保留任务数据，不删表重建。

集成测试另建 `business_task_test_marker` 合成标记表，用于证明业务行与任务一起提交/回滚。所有已提交合成记录和日志保留；未清库、未删行、未批量删除文件。没有启用真实支付、模型、ES、向量库或真实业务消费者。

## 测试案例与复现

前提：JDK21、PowerShell7、已有隔离库与私密 `runtime/phase0/baseline.env`。以下在后端根目录执行；无需启动Redis、ES、模型和管理端。

```powershell
docker compose -p easymall-baseline --env-file ../runtime/phase0/baseline.env -f compose.baseline.yml start mysql
./scripts/verify-task-infrastructure.ps1 -JavaHome '<JDK21目录>'
# 已缓存依赖时可加 -Offline；Maven不在PATH时可加 -MavenCommand '<mvn.cmd绝对路径>'
```

该入口要求本轮3份报告全部更新且零跳过。普通 `test/package` 只运行下表前2组，不连接数据库；MySQL组必须显式启用。测试固定端口和库名，使用合成任务，不删除测试数据。

| 测试类/组 | 数量 | 关键断言 |
|---|---:|---|
| BusinessTaskUnitTest | 9 | 事务前置、JSON合法性/大小/深度/精度、事件边界、退避上限、配置溢出防护、处理器注册、租约丢失、日志载荷保护 |
| TaskWorkerBootstrapTest | 4 | 无Redis/ES/旧任务装配；API配置即使enabled也不启动Worker；Smoke拒绝开发库；缺失处理器快速失败 |
| 提交/回滚 | 2 | 另一连接提交前看不到业务标记与任务；回滚两者均不存在；无事务/只读事务拒绝 |
| 去重/计划时间/领取 | 3 | 8个并发生产者只产生1行；不同载荷拒绝、新版本允许、重复不提前计划时间；两领取者无重叠且延迟/不支持类型不执行 |
| 租约/重试/DEAD | 3 | 自然租约到期后新owner接管，旧owner三个写入均拒绝；退避1秒/1.5秒封顶、3次DEAD、条件人工重试；末次崩溃转DEAD |
| Worker运行/停机 | 4 | 超过租约长度的处理器持续续租且无活动事务；8任务最多并发2、失败两次后成功；优雅停机完成在途任务；强制停机后迟到结果拒绝并恢复 |
| 真实子JVM | 2 | 处理器领取后`Runtime.halt(23)`硬退出，另一个Worker恢复到SUCCESS/attempt2；专用无Web入口实际消费Smoke且空闲仍保持运行 |

MySQL组共14项，总计27项定向测试。退避测试仅为自己的合成任务提前到期时间以避免无谓等待；租约恢复、续租和硬崩溃测试使用真实时间，不通过改租约时间伪造。

失败排查：查看当次 `target/surefire-reports` 和 `easymall-common/target/step11a/*.log`；后者为子JVM日志，参数文件不含密码，数据库密码仅注入子进程环境。不因失败清理/重置任务，应使用taskId核对状态、attempts和固定错误码。

结束后只停止本次启动的隔离MySQL并保留卷：

```powershell
docker compose -p easymall-baseline --env-file ../runtime/phase0/baseline.env -f compose.baseline.yml stop mysql
```

## 本轮结果

27项定向测试通过（零失败、零错误、零跳过），包含真实MySQL与两个子进程场景。四模块完整package通过，普通测试Common 368、Admin 35、Web 133、MCP 4，共540项，零失败/错误/跳过。27项定向中13项已计入540项，额外真实MySQL集成为14项。

归档入口使用最新Admin包和实际 `run-task-worker.ps1` 启动，成功连接隔离MySQL并生成专用日志；Ctrl+C观察到 `remaining=0` 及连接池关闭，进程已退出。终端因主动Ctrl+C返回1属于此次停止动作，不是启动失败。测试MySQL已恢复为开始前的停止状态，Redis/ES始终未启动。结果登记于同目录 [11a-results.json](11a-results.json)，原始日志保留在工作区 `runtime/phase2-step11a-*` 和各模块target目录。

本次满足11A通用设施验收，可以继续步骤08。11B待办保持开放：商品/FAQ/订单/支付实际投递和处理器、管理员权限入口、同对象与外部版本保护、旧Redis任务无双写迁移、外部服务故障后的真实最终同步。未将步骤11整包标记为完成。

实现参考：[MySQL锁定读取](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)（SKIP LOCKED队列领取）、[Spring事务模板](https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html)。依赖版本沿用本项目，没有升级Spring或数据库驱动。
