import test from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { fileURLToPath, pathToFileURL } from 'node:url'
import path from 'node:path'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const webRequire = createRequire(path.join(root, 'easymall-front-web/package.json'))
const { JSDOM } = await import(pathToFileURL(webRequire.resolve('jsdom')).href)
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/' })
for (const name of ['window', 'document', 'Element', 'HTMLElement', 'SVGElement', 'Node', 'localStorage', 'history', 'location']) {
  globalThis[name] = dom.window[name]
}

// 加载实际 Vue 单文件组件，只替换网络、路由环境和表单控件，不复制提交逻辑。
async function harness(realm, source) {
  const project = path.join(root, `easymall-front-${realm}`)
  const require = createRequire(path.join(project, 'package.json'))
  const vue = await import(pathToFileURL(require.resolve('vue')).href)
  const { createServer } = await import(pathToFileURL(require.resolve('vite')).href)
  const { default: vuePlugin } = await import(pathToFileURL(require.resolve('@vitejs/plugin-vue')).href)
  const server = await createServer({ root: project, configFile: false, plugins: [vuePlugin()],
    resolve: { alias: { '@': path.join(project, 'src') } },
    server: { middlewareMode: true, hmr: false, watch: null }, appType: 'custom', logLevel: 'error' })
  try {
    const { createPinia, setActivePinia } = await server.ssrLoadModule('pinia')
    const { createRouter, createMemoryHistory } = await server.ssrLoadModule('vue-router')
    const requests = []
    const messages = []
    const pinia = createPinia()
    setActivePinia(pinia)
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/', component: { render: () => null } }, { path: '/home', component: { render: () => null } },
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
      Api: { checkCode: '/checkCode', login: '/login', register: '/register', updatePassword: '/updatePassword' },
      Request: async (request) => {
        requests.push(request)
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
    return { state, store, requests, messages, async settle() { await vue.nextTick(); await vue.nextTick() },
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
