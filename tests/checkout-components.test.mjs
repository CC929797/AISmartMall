// 仅用于测试验证：加载实际Vue setup，隔离子组件展示和HTTP；无真实订单/支付写入。
import test from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { fileURLToPath, pathToFileURL } from 'node:url'
import path from 'node:path'
const project = fileURLToPath(new URL('../easymall-front-web/', import.meta.url))
const require = createRequire(path.join(project, 'package.json'))
const { JSDOM } = require('jsdom')
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/' })
for (const key of ['window', 'document', 'Element', 'HTMLElement', 'SVGElement', 'Node', 'localStorage', 'sessionStorage']) globalThis[key] = dom.window[key]
const vue = await import(pathToFileURL(require.resolve('vue')).href)
const { createServer } = await import(pathToFileURL(require.resolve('vite')).href)
const { default: plugin } = await import(pathToFileURL(require.resolve('@vitejs/plugin-vue')).href)
const product = { productId: 'P1', propertyValueIds: 'S1', buyCount: 1, price: 12.34 }
const created = { code: 200, data: { requestStatus: 'CREATED', paymentStatus: 'UNAVAILABLE', payOrderId: 'SYNTHETIC-PAY', orderId: 'O1', amount: 12.34 } }
const legacy = { code: 200, data: { payInfo: '<form>synthetic-legacy-payment</form>', payOrderId: 'SYNTHETIC-LEGACY-PAY', amount: 29.90 } }

async function harness(name = 'ProductBuy') {
  sessionStorage.clear(); localStorage.setItem('token', 'synthetic-A')
  const server = await createServer({ root: project, configFile: false, logLevel: 'error', appType: 'custom',
    optimizeDeps: { noDiscovery: true, include: [] }, plugins: [{ name: 'isolate-checkout-children', enforce: 'pre',
      load(id) { if (/\/(Address|ProductSkuItem)\.vue$/.test(id.replaceAll('\\', '/'))) return '<template><div /></template>' } }, plugin()],
    resolve: { alias: { '@': path.join(project, 'src') } }, server: { middlewareMode: true, hmr: false, watch: null } })
  const { createPinia } = await server.ssrLoadModule('pinia'), pinia = createPinia()
  const { useLoginStore } = await server.ssrLoadModule('/src/stores/loginStore.js'), store = useLoginStore(pinia)
  store.saveUserInfo({ userId: 'A' })
  const component = (await server.ssrLoadModule(`/src/views/product/${name}.vue`)).default
  const requests = [], messages = [], shown = [], popups = []; let submitted = 0
  window.open = () => { const popup = { opener: {}, written: [], document: { write(value) { popup.written.push(value) }, close() {} }, close() { popup.closed = true } }; popups.push(popup); return popup }
  const app = vue.createApp({ ...component, render() { return vue.h('div') } }, { onSubmitted: () => submitted++ })
  app.use(pinia); app.provide(vue.ssrContextKey, { modules: new Set() })
  Object.assign(app.config.globalProperties, {
    Api: { postOrder: '/order/postOrder', getOrderRequest: '/order/getOrderRequest', getPayInfo: '/order/getPayInfo', getOrderInfo: '/order/getOrderInfo' },
    Request(config) { return new Promise((resolve, reject) => requests.push({ config, resolve, reject })) },
    Message: { warning: value => messages.push(value) }, Utils: { jump() {} },
  })
  const node = document.createElement('div'); document.body.append(node)
  const mounted = app.mount(node), state = mounted.$.setupState
  if (name === 'ProductBuy') { state.orderPayRef = { show: value => shown.push(value) }; state.showBuy([product], 0); state.selectedAddress = 'ADDRESS1' }
  return { state, store, requests, messages, shown, popups, get submitted() { return submitted },
    async until(count) { const deadline = Date.now() + 3000; while (requests.length < count) { if (Date.now() > deadline) throw new Error('Request did not arrive'); await new Promise(resolve => setTimeout(resolve, 5)) } },
    async close() { app.unmount(); node.remove(); await server.close() } }
}

test('提交中连续点击只发一次，失败重试复用原编号', async () => {
  const h = await harness()
  try {
    const a = h.state.postOrder(), b = h.state.postOrder(); await h.until(1)
    assert.equal(h.requests.length, 1); const id = h.requests[0].config.params.requestId
    h.requests[0].resolve(null); await a; await b
    const retry = h.state.postOrder(); await h.until(2)
    assert.equal(h.requests[1].config.params.requestId, id)
    h.requests[1].resolve({ code: 200, data: { requestStatus: 'PROCESSING' } }); await retry
    assert.equal(h.state.dialogConfig.show, true); assert.equal(h.shown.length, 0); assert.equal(h.state.submitting, false)
  } finally { await h.close() }
})
test('购买数量变化换号，CREATED即使支付链接不可用也保留原订单', async () => {
  const h = await harness()
  try {
    const a = h.state.postOrder(); await h.until(1); h.requests[0].resolve(null); await a
    h.state.orderData[0].buyCount = 2
    const b = h.state.postOrder(); await h.until(2)
    assert.notEqual(h.requests[1].config.params.requestId, h.requests[0].config.params.requestId)
    h.requests[1].resolve(created); await b
    assert.equal(h.shown[0].payOrderId, 'SYNTHETIC-PAY'); assert.equal(h.state.dialogConfig.show, false); assert.equal(h.submitted, 1)
  } finally { await h.close() }
})
test('查询提交结果只使用查询接口，不再次购买', async () => {
  const h = await harness()
  try {
    const a = h.state.postOrder(); await h.until(1); h.requests[0].resolve(null); await a
    const check = h.state.checkRequest(); await h.until(2)
    assert.equal(h.requests[1].config.url, '/order/getOrderRequest')
    assert.equal(h.requests[1].config.params.requestId, h.requests[0].config.params.requestId)
    h.requests[1].resolve(created); await check
    assert.equal(h.shown.length, 1); assert.equal(h.requests.filter(item => item.config.url === '/order/postOrder').length, 1)
  } finally { await h.close() }
})
test('账号切换后旧下单响应不打开旧账号支付信息', async () => {
  const h = await harness()
  try {
    const a = h.state.postOrder(); await h.until(1); h.store.saveUserInfo({ userId: 'B' }); h.requests[0].resolve(created); await a
    assert.equal(h.shown.length, 0); assert.equal(h.submitted, 0)
  } finally { await h.close() }
})

test('原版Mall成功响应没有requestStatus时仍进入支付流程', async () => {
  const h = await harness()
  try {
    const submit = h.state.postOrder(); await h.until(1)
    h.requests[0].resolve(legacy); await submit
    assert.equal(h.state.dialogConfig.show, false)
    assert.equal(h.shown.length, 1)
    assert.equal(h.shown[0].payOrderId, 'SYNTHETIC-LEGACY-PAY')
    assert.equal(h.submitted, 1)
  } finally { await h.close() }
})

test('新版明确的PROCESSING和RETRY不会被支付HTML误判为创建成功', async () => {
  for (const requestStatus of ['PROCESSING', 'RETRY']) {
    const h = await harness()
    try {
      const submit = h.state.postOrder(); await h.until(1)
      h.requests[0].resolve({ code: 200, data: { ...legacy.data, requestStatus } }); await submit
      assert.equal(h.state.dialogConfig.show, true)
      assert.equal(h.shown.length, 0)
      assert.equal(h.submitted, 0)
    } finally { await h.close() }
  }
})

test('支付组件可以直接打开原版Mall返回的支付表单', async () => {
  const h = await harness('OrderPay')
  try {
    await h.state.show(legacy.data)
    assert.equal(h.popups.length, 1)
    assert.deepEqual(h.popups[0].written, [legacy.data.payInfo])
  } finally { await h.close() }
})
test('无支付HTML不打开空窗口，继续支付只请求原订单且防重复点击', async () => {
  const h = await harness('OrderPay')
  try {
    await h.state.show(created.data); assert.equal(h.popups.length, 0)
    const a = h.state.retryPay(), b = h.state.retryPay(); await h.until(1)
    assert.equal(h.requests.length, 1); assert.equal(h.requests[0].config.url, '/order/getPayInfo'); assert.equal(h.requests[0].config.params.orderId, 'O1')
    h.requests[0].resolve({ code: 200, data: { ...created.data, paymentStatus: 'READY', payInfo: '<form>synthetic</form>' } }); await a; await b
    assert.equal(h.popups.length, 1); assert.equal(h.popups[0].opener, null); assert.deepEqual(h.popups[0].written, ['<form>synthetic</form>'])
  } finally { await h.close() }
})
test('弹窗拦截不会抛异常，也不会保留上个订单的支付成功状态', async () => {
  const h = await harness('OrderPay')
  try {
    h.state.payResult = { orderStatus: 1 }; window.open = () => null
    await h.state.show({ ...created.data, payInfo: 'synthetic-html', paymentStatus: 'READY' })
    assert.equal(h.state.payResult.orderStatus, undefined); assert.equal(h.state.dialogConfig.show, true); assert.match(h.messages[0], /弹出窗口/)
  } finally { await h.close() }
})
