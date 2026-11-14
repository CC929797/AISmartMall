// 仅用于测试验证：购买请求编号的生命周期和存储边界，不调用后端或支付网关。
import test from 'node:test'
import assert from 'node:assert/strict'
import { webcrypto } from 'node:crypto'
import { createCheckoutIntent, isCheckoutCreated } from '../easymall-front-web/src/utils/CheckoutIntent.js'

function storage() {
  const entries = new Map()
  return { entries, getItem: key => entries.get(key), setItem: (key, value) => entries.set(key, value), removeItem: key => entries.delete(key) }
}
const content = () => ({ addressId: 'SYNTHETIC-ADDRESS', payMethod: 'alipay_pc', orderFrom: 0,
  orderList: [{ productId: 'P1', propertyValueIds: 'S1', buyCount: 1, remark: 'synthetic-private-remark' }] })

test('旧版完整支付响应识别为已创建，且不改写原始响应', () => {
  const legacy = Object.freeze({ payInfo: '<form>synthetic</form>', payOrderId: 'LEGACY-SYNTHETIC', amount: 29.90 })
  assert.equal(isCheckoutCreated(legacy), true)
  assert.equal(isCheckoutCreated({ ...legacy, amount: '29.90' }), true)
  assert.equal(Object.hasOwn(legacy, 'requestStatus'), false)
})

test('新版显式状态优先，不回退到旧版支付信息判断', () => {
  const legacy = { payInfo: '<form>synthetic</form>', payOrderId: 'LEGACY-SYNTHETIC', amount: 29.90 }
  for (const requestStatus of ['PROCESSING', 'RETRY', '', null, undefined]) {
    assert.equal(isCheckoutCreated({ ...legacy, requestStatus }), false)
  }
})

test('新版CREATED即使暂时没有支付页面也已创建，后续继续原订单支付', () => {
  assert.equal(isCheckoutCreated({ requestStatus: 'CREATED', payInfo: null, paymentStatus: 'UNAVAILABLE' }), true)
})

test('旧版缺少有效支付信息或金额时保持未确认', () => {
  const legacy = { payInfo: '<form>synthetic</form>', payOrderId: 'LEGACY-SYNTHETIC', amount: 29.90 }
  for (const invalid of [null, undefined, [], {},
    { ...legacy, payInfo: null }, { ...legacy, payInfo: ' ' }, { ...legacy, payOrderId: '' },
    { ...legacy, amount: null }, { ...legacy, amount: false }, { ...legacy, amount: 0 },
    { ...legacy, amount: -1 }, { ...legacy, amount: Infinity }, { ...legacy, amount: 'invalid' }]) {
    assert.equal(isCheckoutCreated(invalid), false)
  }
})

test('网络重试与刷新重建实例均复用同一requestId，存储中没有请求原文', async () => {
  const store = storage(), a = createCheckoutIntent(store, webcrypto)
  const id = await a.get('A', content())
  assert.equal(await a.get('A', content()), id)
  assert.equal(await createCheckoutIntent(store, webcrypto).get('A', content()), id)
  assert.doesNotMatch([...store.entries.values()].join(), /SYNTHETIC-ADDRESS|synthetic-private-remark/)
})
test('地址、数量、备注和支付内容变化后生成新编号', async () => {
  const intent = createCheckoutIntent(storage(), webcrypto)
  const first = await intent.get('A', content())
  for (const change of [c => { c.addressId = 'NEW' }, c => { c.orderList[0].buyCount = 2 }, c => { c.orderList[0].remark = 'new' }, c => { c.orderFrom = 1 }]) {
    const next = content(); change(next)
    assert.notEqual(await intent.get('A', next), first)
  }
})
test('相同SKU拆成多行或调整明细顺序不会产生新购买意图', async () => {
  const intent = createCheckoutIntent(storage(), webcrypto), c = content()
  c.orderList[0].buyCount = 3
  const id = await intent.get('A', c)
  c.orderList = [{ ...c.orderList[0], buyCount: 1 }, { ...c.orderList[0], buyCount: 2 }]
  assert.equal(await intent.get('A', c), id)
})
test('不同账号的购买请求互不复用', async () => {
  const intent = createCheckoutIntent(storage(), webcrypto)
  assert.notEqual(await intent.get('A', content()), await intent.get('B', content()))
})
test('未确认时重新打开保留编号，已确认创建后明确开始新结算才换号', async () => {
  const intent = createCheckoutIntent(storage(), webcrypto), id = await intent.get('A', content())
  intent.begin('A'); assert.equal(await intent.get('A', content()), id)
  intent.complete('A', id); assert.equal(await intent.get('A', content()), id)
  intent.begin('A'); assert.notEqual(await intent.get('A', content()), id)
})
test('存储不可用时当前页面仍复用编号，未登录时拒绝创建', async () => {
  const blocked = { getItem() { throw new Error('blocked') }, setItem() { throw new Error('blocked') } }
  const intent = createCheckoutIntent(blocked, webcrypto), id = await intent.get('A', content())
  assert.equal(await intent.get('A', content()), id)
  await assert.rejects(intent.get('', content()), /登录/)
})
