# 阶段 0：结构与合成数据

`00-schema.sql` 是现有数据库的纯结构快照。`01-fixture.sql` 只包含合成用户、两级分类、一个商品、一个属性值、一个 SKU 和一个收货地址，不包含真实用户资料或业务数据。两份脚本仅用于显式选中的空 `easymall_test` 数据库，先导入结构，再导入 fixture；应用启动前完成导入，不向开发数据库导入。

fixture 导入前检查全部 16 张业务表为空，且 `DATABASE()='easymall_test'`。每条 INSERT 都受同一条件控制；错误目标或非空库不会写入 fixture。执行器还必须确认 `fixture_allowed=1`，并检查最终计数为用户 1、分类 2、商品 1、SKU 1、地址 1。不要使用 `mysql --force`、去掉条件或反复补导数据。脚本不提供清空/覆盖现有数据库的操作。

测试账号为 `baseline@example.invalid`，假密码为 `Baseline123`；当前登录协议要求客户端提交该假密码的 MD5，数据库用同一个 MD5。该账号只能用于隔离环境，不是生产凭据。注册与密码升级协议属于后续步骤，不能把此 fixture 当作安全密码存储设计。

固定资源：用户 `BASE000001`、分类 `B0001/B0002`、商品 `BASELINEPROD001`、属性值 `BASEVALUE001`、收货地址 `BASEADDR000001`。SKU 单价 3999.00、初始库存 10，商品上架且不属于推荐列表。封面路径只是合成路径，未附带图片文件；图片显示不在该 SQL 的验证范围。

## HTTP 烟测参数

所有接口路径均在 Web 后端 `/api` 之下。

1. 匿名读取 `/product/loadCategory`，预期包含 `B0001` 下的 `B0002`；`/product/loadProduct?pageNo=1&categoryId=B0002` 和 `/product/getProduct?productId=BASELINEPROD001` 应返回合成商品。
2. 登录前调用 `/account/checkCode`，保存本次 `checkCodeKey`；仅在专用测试 Redis 中获取对应验证码，或人工识别图片。用表单提交 `/account/login`，字段为 `email`、`password`（假密码 MD5）、`checkCodeKey`、`checkCode`。后续请求使用响应里的 `token`，以 `token` 请求头传递。验证码使用一次即失效。
3. ES 搜索 `/product/search?keyWords=Baseline&pageNo=1&priceFrom=3000&priceTo=5000` 需要先将合成商品同步到专用 ES 的商品索引。SQL 导入不会自动建立搜索文档。
4. 下单请求见下方 JSON；成功前必须替换支付组件，否则现有代码无条件调用 `payChannel4Alipay.getPayUrl`，未配置的私钥/证书会失败并回滚。`project.auto-checkpay=false` 只关闭支付轮询，不会绕过下单支付组件。
5. 替身支付下单成功后，使用其 `payOrderId` 调 `/order/getOrderInfo`，并调用 `/order/loadMyOrder?pageNo=1`，预期待付款订单金额 3999.00、明细数量 1、对应 SKU 库存从 10 变为 9。这些数值以首次执行为前提；重复执行不是幂等验收。

```json
{
  "payMethod": "alipay_pc",
  "addressId": "BASEADDR000001",
  "orderFrom": 0,
  "orderList": [
    {
      "productId": "BASELINEPROD001",
      "propertyValueIds": "BASEVALUE001",
      "buyCount": 1,
      "remark": "Synthetic phase 0 smoke test"
    }
  ]
}
```

支付替身对应接口为 `PayChannel.getPayUrl(PayChannelEnum, String payOrderId, String subject, BigDecimal amount)`，返回 `new PayInfoDTO(String payInfo, String payOrderId, BigDecimal amount)`；Bean 名必须为 `payChannel4Alipay`。不执行真实支付、支付回调、退款或自动资金确认。

发货人信息从 Redis 读取，现有下单代码允许其为空；本 fixture 不造不存在的运费字段或运费配置。模型问答和 FAQ/向量索引另行准备显式 Mock，不由此 SQL 构造效果证据。
