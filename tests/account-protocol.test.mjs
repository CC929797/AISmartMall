import test from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { fileURLToPath, pathToFileURL } from 'node:url'
import path from 'node:path'
import { harness as httpHarness } from './helpers/request-harness.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const webRequire = createRequire(path.join(root, 'easymall-front-web/package.json'))
const { JSDOM } = await import(pathToFileURL(webRequire.resolve('jsdom')).href)
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/' })
for (const name of ['window', 'document', 'Element', 'HTMLElement', 'SVGElement', 'Node', 'localStorage', 'history', 'location']) {
  globalThis[name] = dom.window[name]
}

// 加载实际 Vue 单文件组件，只替换网络、路由环境和表单控件，不复制提交逻辑。
async function harness(realm, source, options = {}) {
  const project = path.join(root, `easymall-front-${realm}`)
  const require = createRequire(path.join(project, 'package.json'))
  const vue = await import(pathToFileURL(require.resolve('vue')).href)
  const { createServer } = await import(pathToFileURL(require.resolve('vite')).href)
  const { default: vuePlugin } = await import(pathToFileURL(require.resolve('@vitejs/plugin-vue')).href)
  const server = await createServer({ root: project, configFile: false, plugins: [vuePlugin()],
    optimizeDeps: { noDiscovery: true, include: [] },
    resolve: { alias: { '@': path.join(project, 'src') } },
    server: { middlewareMode: true, hmr: false, watch: null }, appType: 'custom', logLevel: 'error' })
  try {
    const { createPinia, setActivePinia } = await server.ssrLoadModule('pinia')
    const { createRouter, createMemoryHistory } = await server.ssrLoadModule('vue-router')
    const requests = []
    const messages = []
    let confirmation
    const pinia = createPinia()
    setActivePinia(pinia)
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/', component: { render: () => null } }, { path: '/home', component: { render: () => null } },
      { path: '/agent', meta: { checkLogin: true }, component: { render: () => null } },
      { path: '/login', component: { render: () => null } },
    ] })
    await router.push('/')
    await router.isReady()
    const component = (await server.ssrLoadModule(source)).default
    const app = vue.createApp({ ...component, render() {
      const state = vue.getCurrentInstance().setupState
      return vue.h('input', { value: state.formData?.password || state.formData?.registerPassword || '' })
    } })
    app.use(pinia)
    app.use(router)
    app.provide(vue.ssrContextKey, { modules: new Set() })
    Object.assign(app.config.globalProperties, {
      Api: { checkCode: '/checkCode', login: '/login', register: '/register', updatePassword: '/updatePassword', logout: '/account/logout' },
      Confirm: ({ okfun }) => { confirmation = okfun() },
      Request: async (request) => {
        requests.push(request)
        if (options.request) return options.request(request)
        return { code: 200, data: request.url === '/checkCode'
          ? { checkCodeKey: 'synthetic-code', checkCode: '' }
          : realm === 'admin' ? 'synthetic-admin-token' : { userId: 'SYNTHETIC', token: 'synthetic-web-token' } }
      },
      Message: { success: (message) => messages.push(message) },
      Verify: { email: () => {}, password: () => {} },
    })
    const node = document.createElement('div')
    document.body.append(node)
    const vm = app.mount(node)
    const state = vm.$.setupState
    const store = realm === 'web'
      ? (await server.ssrLoadModule('/src/stores/loginStore.js')).useLoginStore(pinia) : null
    let validation
    state.formDataRef = { resetFields() {}, validate(callback) { validation = callback(true) } }
    return { state, store, requests, messages, router, async confirmLogout() { state.logout(); await confirmation; await vue.nextTick() }, async settle() { await vue.nextTick(); await vue.nextTick() },
      async submit() { state.doSubmit(); await validation; await vue.nextTick() },
      async close() { app.unmount(); node.remove(); await server.close() } }
  } catch (error) {
    await server.close()
    throw error
  }
}

test('用户登录提交原密码，输入及注册切换不会触发表单重置', async () => {
  const h = await harness('web', '/src/views/account/Account.vue')
  try {
    h.store.showLogin = true
    await h.settle()
    h.state.formData = { email: 'synthetic@example.invalid', password: 'Synthetic123', checkCode: '7' }
    await h.settle()
    assert.equal(h.state.formData.password, 'Synthetic123')
    await h.submit()
    assert.equal(h.requests.find((request) => request.url === '/login').params.password, 'Synthetic123')
    h.store.showLogin = true
    await h.settle()
    h.state.showPanel(0)
    await h.settle()
    h.state.formData.registerPassword = 'Synthetic456'
    await h.settle()
    assert.equal(h.state.opType, 0)
    assert.equal(h.state.formData.registerPassword, 'Synthetic456')
    h.state.formData.email = 'registration@example.invalid'
    h.state.formData.nickName = '注册测试'
    h.state.formData.checkCode = '7'
    await h.submit()
    assert.equal(h.requests.find((request) => request.url === '/register').params.registerPassword, 'Synthetic456')
  } finally {
    await h.close()
  }
})

test('管理员退出兼容新JSON和旧空响应，第一次确认就清理凭据并返回登录页', async () => {
  for (const body of ['', { code: 200, data: null }]) {
    const http = httpHarness('admin')
    const h = await harness('admin', '/src/views/Layout.vue', { request: config => http.request(config) })
    try {
      localStorage.setItem('adminToken', 'synthetic-old-token')
      await h.router.push('/home')
      const logout = h.confirmLogout()
      await http.wait(); http.respond(body)
      await logout
      assert.equal(h.router.currentRoute.value.path, '/login')
      assert.equal(localStorage.getItem('adminToken'), null)
      assert.equal(http.requests.length, 1)
      assert.equal(http.messages.length, 0)
    } finally { await h.close() }
  }
})

test('从私有页面退出先回到首页再清理身份，不弹出重新登录窗口', async () => {
  const h = await harness('web', '/src/views/header/IndexHeader.vue')
  try {
    h.store.saveSession({ token: 'synthetic-old-token', userId: 'SYNTHETIC' })
    await h.router.push('/agent')
    const stop = h.store.$onAction(({ name }) => {
      if (name === 'clearSession') assert.equal(h.router.currentRoute.value.meta.checkLogin, undefined)
    })
    await h.confirmLogout()
    stop()
    assert.equal(h.router.currentRoute.value.path, '/')
    assert.equal(h.store.showLogin, false)
    assert.equal(localStorage.getItem('token'), null)
  } finally { await h.close() }
})

test('管理员退出进行中重复确认只发送一次请求', async () => {
  const http = httpHarness('admin')
  const h = await harness('admin', '/src/views/Layout.vue', { request: config => http.request(config) })
  try {
    localStorage.setItem('adminToken', 'synthetic-old-token'); await h.router.push('/home')
    const first = h.confirmLogout(), second = h.confirmLogout()
    await http.wait(); assert.equal(http.requests.length, 1)
    http.respond({ code: 200, data: null }); await Promise.all([first, second])
    assert.equal(h.router.currentRoute.value.path, '/login')
    assert.equal(h.state.loggingOut, false)
  } finally { await h.close() }
})

test('管理员退出业务失败保留会话并允许重试', async () => {
  const http = httpHarness('admin')
  const h = await harness('admin', '/src/views/Layout.vue', { request: config => http.request(config) })
  try {
    localStorage.setItem('adminToken', 'synthetic-old-token'); await h.router.push('/home')
    const first = h.confirmLogout()
    await http.wait(); http.respond({ code: 600, info: 'synthetic failure' }); await first
    assert.equal(h.router.currentRoute.value.path, '/home')
    assert.equal(localStorage.getItem('adminToken'), 'synthetic-old-token')
    assert.equal(h.state.loggingOut, false)
    const retry = h.confirmLogout()
    await http.wait(); http.respond({ code: 200, data: null }, 1); await retry
    assert.equal(h.router.currentRoute.value.path, '/login')
  } finally { await h.close() }
})

test('管理员旧退出请求的迟到成功不撤销后来的新登录', async () => {
  const http = httpHarness('admin')
  const h = await harness('admin', '/src/views/Layout.vue', { request: config => http.request(config) })
  try {
    localStorage.setItem('adminToken', 'synthetic-old-token'); await h.router.push('/home')
    const logout = h.confirmLogout()
    await http.wait(); localStorage.setItem('adminToken', 'synthetic-new-token')
    http.respond({ code: 200, data: null }); await logout
    assert.equal(h.router.currentRoute.value.path, '/home')
    assert.equal(localStorage.getItem('adminToken'), 'synthetic-new-token')
    assert.equal(h.state.loggingOut, false)
  } finally { await h.close() }
})

test('管理员登录提交原密码，成功后保存管理员 Token', async () => {
  const h = await harness('admin', '/src/views/account/Account.vue')
  try {
    h.state.formData = { account: 'synthetic-admin', password: 'Synthetic123', checkCode: '7' }
    await h.submit()
    assert.equal(h.requests.find((request) => request.url === '/login').params.password, 'Synthetic123')
    assert.equal(localStorage.getItem('adminToken'), 'synthetic-admin-token')
  } finally {
    await h.close()
  }
})

test('改密成功清理本地旧 Token 和身份，并打开重新登录窗口', async () => {
  const h = await harness('web', '/src/views/account/UpdatePassword.vue')
  try {
    localStorage.setItem('token', 'synthetic-old-token')
    h.store.saveUserInfo({ userId: 'SYNTHETIC' })
    h.store.showLogin = false
    h.state.formData = { oldPassword: 'Synthetic123', password: 'Synthetic456' }
    await h.submit()
    assert.equal(localStorage.getItem('token'), null)
    assert.deepEqual(h.store.userInfo, {})
    assert.equal(h.store.showLogin, true)
    assert.ok(h.messages.includes('密码修改成功，请重新登录'))
    assert.equal(h.requests.find((request) => request.url === '/updatePassword').params.oldPassword, 'Synthetic123')
  } finally {
    await h.close()
  }
})
