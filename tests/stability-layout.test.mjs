import test from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { fileURLToPath, pathToFileURL } from 'node:url'
import path from 'node:path'

const project = fileURLToPath(new URL('../easymall-front-web/', import.meta.url))
const require = createRequire(path.join(project, 'package.json'))
const { JSDOM } = require('jsdom')
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/' })
for (const key of ['window', 'document', 'Element', 'HTMLElement', 'SVGElement', 'Node', 'localStorage', 'history', 'location']) globalThis[key] = dom.window[key]
const vue = await import(pathToFileURL(require.resolve('vue')).href)
const { createServer } = await import(pathToFileURL(require.resolve('vite')).href)
const { default: vuePlugin } = await import(pathToFileURL(require.resolve('@vitejs/plugin-vue')).href)

async function harness({ token = 'synthetic-old-token', privateRoute = false } = {}) {
  localStorage.clear()
  if (token) localStorage.setItem('token', token)
  const server = await createServer({ root: project, configFile: false, logLevel: 'error', appType: 'custom',
    optimizeDeps: { noDiscovery: true, include: [] },
    plugins: [{ name: 'isolate-layout-children', enforce: 'pre', load(id) {
      if (/views\/(footer\/Footer|header\/IndexHeader)\.vue$/.test(id.replaceAll('\\', '/'))) return '<template><div /></template>'
    } }, vuePlugin()], resolve: { alias: { '@': path.join(project, 'src') } },
    server: { middlewareMode: true, hmr: false, watch: null } })
  const { createPinia, setActivePinia } = await server.ssrLoadModule('pinia')
  const { createRouter, createMemoryHistory } = await server.ssrLoadModule('vue-router')
  const pinia = createPinia(); setActivePinia(pinia)
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', meta: { checkLogin: privateRoute }, component: { render: () => null } }] })
  await router.push('/'); await router.isReady()
  const { useLoginStore } = await server.ssrLoadModule('/src/stores/loginStore.js')
  const store = useLoginStore(pinia)
  let resolve, reject
  const response = new Promise((a, b) => { resolve = a; reject = b })
  const component = (await server.ssrLoadModule('/src/views/Layout.vue')).default
  // 只替换展示，保留真实 setup、自动登录和 watch。
  const app = vue.createApp({ ...component, render() { return vue.h('div') } })
  app.use(pinia); app.use(router); app.provide(vue.ssrContextKey, { modules: new Set() })
  const messages = [], requests = []
  Object.assign(app.config.globalProperties, { Api: { autoLogin: '/account/autoLogin' }, Message: { error: value => messages.push(value) },
    Request(config) { requests.push(config); return response } })
  const node = document.createElement('div'); document.body.append(node)
  const mounted = app.mount(node)
  return { state: mounted.$.setupState, store, requests, messages, resolve, reject,
    async settle() { await response.catch(() => {}); await vue.nextTick(); await vue.nextTick() },
    async close() { app.unmount(); node.remove(); await server.close() } }
}

test('无 Token 不发自动登录请求，公开页面完成初始化', async () => {
  const h = await harness({ token: null })
  try { assert.equal(h.state.haveInit, true); assert.equal(h.state.showRouterView, true); assert.equal(h.requests.length, 0) }
  finally { await h.close() }
})

test('自动登录网络失败完成初始化，保留 Token，公开页面可显示', async () => {
  const h = await harness()
  try { h.resolve(null); await h.settle(); assert.equal(h.state.haveInit, true); assert.equal(h.state.showRouterView, true); assert.equal(localStorage.getItem('token'), 'synthetic-old-token') }
  finally { await h.close() }
})

test('自动登录抛异常同样完成初始化，不撤销凭据', async () => {
  const h = await harness()
  try { h.reject(new Error('synthetic')); await h.settle(); assert.equal(h.state.haveInit, true); assert.equal(h.messages.length, 1); assert.equal(localStorage.getItem('token'), 'synthetic-old-token') }
  finally { await h.close() }
})

test('有效 Token 恢复身份，私有页面不会在探测前提前弹登录', async () => {
  const h = await harness({ privateRoute: true })
  try {
    assert.equal(h.store.showLogin, false)
    h.resolve({ code: 200, data: { token: 'synthetic-old-token', userId: 'SYNTHETIC' } }); await h.settle()
    assert.equal(h.state.haveInit, true); assert.equal(h.state.showRouterView, true); assert.equal(h.store.showLogin, false)
  } finally { await h.close() }
})

for (const privateRoute of [false, true]) {
  test(`过期 Token 清理凭据，${privateRoute ? '私有页面引导登录' : '公开页面保持可浏览'}`, async () => {
    const h = await harness({ privateRoute })
    try {
      h.resolve({ code: 200, data: null }); await h.settle()
      assert.equal(localStorage.getItem('token'), null); assert.deepEqual(h.store.userInfo, {})
      assert.equal(h.state.haveInit, true); assert.equal(h.state.showRouterView, !privateRoute); assert.equal(h.store.showLogin, privateRoute)
    } finally { await h.close() }
  })
}

test('自动登录迟到响应不能覆盖新登录或复活已退出会话', async () => {
  for (const action of ['login', 'logout']) {
    const h = await harness()
    try {
      if (action === 'login') h.store.saveSession({ token: 'synthetic-new-token', userId: 'NEW' }); else h.store.clearSession()
      h.resolve({ code: 200, data: { token: 'synthetic-old-token', userId: 'OLD' } }); await h.settle()
      assert.equal(localStorage.getItem('token'), action === 'login' ? 'synthetic-new-token' : null)
      assert.equal(h.store.userInfo.userId, action === 'login' ? 'NEW' : undefined)
    } finally { await h.close() }
  }
})
