# 阶段 0：数据库结构基线

- 检查日期：2026-10-25（Asia/Shanghai）。
- 证据：工作区根目录 `runtime/phase0/database-schema.tsv`，为本机开发环境的结构快照。
- 范围：服务器版本、表、列、索引和约束元数据；本报告未读取或复制业务行，不记录源库名、连接地址或凭据。
- 本报告仅登记现状，没有执行迁移、修改约束或证明交易逻辑已经通过测试。

## 1. 结构概览

| 项目 | 快照结果 |
|---|---|
| MySQL 服务器版本 | 8.0.43 |
| 表 | 16 张，均为 BASE TABLE |
| 存储引擎 | 均为 InnoDB |
| 表排序规则 | 均为 utf8mb4_general_ci |
| 列 | 129 列 |
| 索引 | 33 个：16 个主键索引、3 个其他唯一索引、14 个普通索引；均为 BTREE |
| TABLE_CONSTRAINTS | 19 条：16 个 PRIMARY KEY、3 个 UNIQUE |
| 外键、CHECK | 本次元数据未返回 FOREIGN KEY 或 CHECK 约束 |

索引按“表名 + 索引名”计数，复合索引按一个索引计数，不能将其多个列条目重复计数。外键缺失意味着不能把表之间的逻辑关系当作数据库已经强制维护的关系。上述结论仅对应本次快照。

## 2. 表、主键和全部索引清单

下表中的 `PRIMARY` 均对应同名主键约束；“其他唯一键”同时对应同名 UNIQUE 约束。未列出的普通索引不存在于本次快照。

| 表 | PRIMARY（按索引列顺序） | 其他唯一键 | 普通索引 |
|---|---|---|---|
| agent_message | message_id | 无 | idx_user_id(user_id) |
| order_comment | order_id | 无 | idx_product_id(product_id) |
| order_info | order_id | 无 | idx_create_time(order_time)；idx_pay_order_id(pay_order_id) |
| order_item | order_item_id | 无 | 无 |
| order_logistics_info | order_id | 无 | idx_logistics_company(logistics_company)；idx_logistics_status(logistics_status)；idx_order_id(order_id)；idx_receiver_phone(receiver_phone)；idx_tracking_no(logistics_no)；idx_user_id(user_id 前 8 字符)；tracking_no(logistics_no) |
| order_logistics_info_record | record_id | 无 | idx_logistics_id(order_id) |
| product_cart | cart_id | idx_key(product_id, property_value_id_hash, user_id) | 无 |
| product_info | product_id | 无 | 无 |
| product_property_value | product_id, property_value_id | 无 | 无 |
| product_sku | product_id, property_value_id_hash | 无 | idx_product_id(product_id) |
| rag_question | question_id | 无 | 无 |
| statistics_info | statistics_date, data_type | 无 | 无 |
| sys_category | category_id | 无 | 无 |
| sys_product_property | property_id | 无 | 无 |
| user_address | address_id | 无 | idx_user_id(user_id) |
| user_info | user_id | idx_key_email(email)；idx_nick_name(nick_name) | 无 |

其中 `order_logistics_info.idx_user_id` 的 `SUB_PART=8`，是前缀索引；其他索引列的快照未报告前缀长度。

## 3. 密码、身份与唯一键事实

| 字段 | 类型 | 可空 | 结构含义 |
|---|---|---|---|
| user_info.password | varchar(50) | 否 | 仅确认容量为 50 字符；没有读取密码值，也不能仅凭字段判断实际编码算法 |
| user_info.api_key | varchar(50) | 是 | 存在可空凭据字段；本报告不读取其内容 |
| user_info.user_id | varchar(10) | 否 | 用户表主键 |
| user_info.email | varchar(150) | 否 | 受 idx_key_email 唯一约束保护 |
| user_info.nick_name | varchar(20) | 否 | 受 idx_nick_name 唯一约束保护 |
| product_cart.product_id | varchar(15) | 否 | 购物车复合唯一键第一列 |
| product_cart.property_value_id_hash | varchar(32) | 是 | 购物车复合唯一键第二列 |
| product_cart.user_id | varchar(15) | 是 | 购物车复合唯一键第三列 |
| product_sku.product_id | varchar(15) | 否 | SKU 复合主键第一列 |
| product_sku.property_value_id_hash | varchar(32) | 否 | SKU 复合主键第二列 |

需要保留的边界：

- `product_cart` 已有业务复合唯一键，但其中两列允许 NULL。MySQL 唯一键允许包含 NULL 的组合重复，不能只凭该索引宣称购物车在所有输入下都能防止重复；后续应核对业务是否始终填充这两列。
- 用户表 `user_id` 长度为 10；本次涉及的订单、消息、购物车、地址等关联列长度为 15。当前未发现关联外键，后续统一身份设计时需要核对真实 ID 生成规则和字段兼容性。
- 密码编码迁移前，应先验证新编码实际长度并安排字段扩容迁移，不能直接假定现有 50 字符容量足够。现有记录的编码算法和迁移方式仍需业务代码与独立测试验证。
- 没有读取业务行，因此没有核验历史 NULL、重复业务组合、无效关联或历史编码分布。

## 4. 金额、库存与数量字段

| 字段 | 类型 | 可空 | 默认值 |
|---|---|---|---|
| order_info.amount | decimal(10,2) | 是 | NULL |
| order_item.item_amount | decimal(10,2) | 是 | NULL |
| product_info.min_price | decimal(10,2) | 是 | NULL |
| product_info.max_price | decimal(10,2) | 是 | NULL |
| product_sku.price | decimal(10,2) | 是 | NULL |
| product_sku.stock | int | 是 | NULL |
| order_item.buy_count | int | 是 | NULL |
| product_cart.buy_count | int | 是 | NULL |
| product_info.total_sale | int | 是 | 0 |

上述数值类型均未标记 UNSIGNED；本次也没有返回保证金额、库存或购买数量取值范围的 CHECK 约束。`decimal(10,2)` 仅提供两位小数精度，不代表金额已保证非负。`item_amount` 的列注释为“价格”，单价与明细总额的最终语义仍需结合业务实现确认。

当前 `ProductSkuMapper.xml` 的库存增减 SQL 已包含 `stock + 变更量 >= 0` 条件；应保留这项已有保护。该 SQL 条件不等同于所有写入路径都受数据库约束保护，也尚未证明并发下单、重复 SKU、NULL 库存与受影响行数处理已经正确。

## 5. 订单状态与交易编号

| 字段 | 类型与默认值 | 快照中的状态定义 |
|---|---|---|
| order_info.order_status | tinyint(1)，可空，默认 NULL | -1 已删除；0 待付款；1 已付款待发货；2 已发货；3 已完成；4 已取消；5 已关闭；6 已退款；7 部分退款 |
| order_item.order_item_status | tinyint(1)，可空，默认 NULL | 1 正常；0 已退款 |
| order_info.comment_status | tinyint，可空，默认 0 | 0 未评价；1 已评价；2 已追评 |
| order_logistics_info.logistics_status | tinyint，可空，默认 0 | 0 待发货；1 运输中；2 已送达；3 订单取消 |
| product_info.status | tinyint(1)，可空，默认 0 | -1 已删除；0 下架；1 上架 |
| user_info.status | tinyint(1)，非空，默认 1 | 0 禁用；1 正常 |
| agent_message.status | tinyint(1)，可空，默认 1 | 0 用户取消；1 回答中；2 完成 |

订单主状态值与当前 `OrderStatusEnum`、订单明细状态值与 `OrderItemStatusEnum` 对应。列注释和 Java 枚举不是数据库取值约束，`tinyint(1)` 也不意味着只能保存 0/1；本次未发现用于限制状态集合或状态迁移的 CHECK 约束。

| 字段或组合 | 类型 | 索引和唯一性事实 |
|---|---|---|
| order_info.order_id | varchar(32)，非空 | 主键，唯一 |
| order_info.pay_order_id | varchar(32)，可空 | 只有普通 idx_pay_order_id，数据库不保证唯一 |
| order_info.channel_order_Id | varchar(50)，可空 | 本次未发现索引或唯一约束 |
| order_item.order_item_id | varchar(40)，非空 | 主键，唯一 |
| order_item.order_id | varchar(32)，非空 | 本次未发现单列或复合索引 |
| order_item.refund_order_id | varchar(32)，可空 | 本次未发现索引或唯一约束 |
| order_comment.order_id | varchar(32)，非空 | 整表主键；一个 order_id 至多对应一行评论记录 |

本次 16 张表中没有独立退款单表、可靠任务表或确认动作表；这些仍属于路线图后续设计。没有直接把 `pay_order_id` 标为缺少唯一键：如果多张业务订单合并支付，共享支付编号可能正是预期行为，必须先确认订单与支付单的关系，再设计幂等约束。

## 6. 后续核对事项

| 路线步骤 | 已掌握的结构事实 | 实施时还需验证 |
|---|---|---|
| 01、02：身份与敏感字段 | 用户表存在 password、api_key；关联 user_id 字段允许 NULL 且长度不完全一致 | 接口响应和日志脱敏、身份来源、资源归属；不通过读生产业务行来补充测试样本 |
| 03：输入边界 | buy_count、stock 和金额列可空，未发现范围 CHECK | Service/DTO 对 NULL、负数、数量上限和重复 SKU 的规则及测试 |
| 04：密码迁移 | password 为 varchar(50) NOT NULL | 新编码容量、迁移脚本、旧账号兼容；先在独立测试库执行 |
| 08：下单与库存 | SKU 已有复合主键；购物车已有含可空列的复合唯一键 | 并发库存、订单幂等请求号、受影响行数检查；是否需要补充可空规则或约束 |
| 09、10：退款与支付 | 支付号为普通索引；退款号没有唯一约束；没有独立退款单表 | 支付聚合粒度、稳定退款业务号、渠道结果与本地状态、并发状态迁移 |
| 11：可靠任务 | 未发现可靠任务表 | 新表、唯一任务键、租约与重试迁移；不把当前快照当作已经具备任务设施 |
| 12—16：AI 工程 | agent_message 有 user_id 普通索引及三种注释状态 | 会话归属、执行标识、失败/取消终态、消息恢复需要哪些新增字段和索引 |

查询索引方面，当前 `OrderInfoMapper.xml` 按 `user_id` 筛选订单并按 `order_id` 关联明细，但快照中 `order_info.user_id` 和 `order_item.order_id` 没有对应索引。后续使用独立测试数据及 EXPLAIN 核实查询计划，再决定是否新增索引。

`order_logistics_info` 的 `idx_order_id(order_id)` 与主键列相同，`idx_tracking_no` 和 `tracking_no` 的列完全相同，`product_sku.idx_product_id` 与复合主键首列相同。这些只是重复或前缀重叠的候选，尚未检查索引提示、真实查询计划与写入代价，本阶段不删除索引。

## 7. 未知项与测试环境边界

- 未读取业务行，不能判断历史数据是否满足后续非空、唯一、金额和状态规则，也未测量表行数或业务分布。
- 未导出触发器、事件、存储过程、授权、服务器 SQL mode 或隔离级别；不推定它们存在或不存在。
- 未运行数据库集成测试、真实并发测试或迁移回滚验证；本报告中的 Mapper 对照属于静态检查。
- 此快照来自开发环境，不是独立测试库证据。专用 MySQL、Redis、ES 以及仅结构复制的实际准备和验收结果，以 `00-baseline.md` 的执行记录为准。
- 后续测试数据只使用新建的虚构 fixture；复制结构不能夹带业务行、支付凭据或用户凭据。
