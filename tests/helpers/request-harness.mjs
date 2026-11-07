import fs from 'node:fs'
import vm from 'node:vm'
import { createRequire } from 'node:module'

export function harness(realm, options = {}) {
  const require = createRequire(new URL(`../../easymall-front-${realm}/package.json`, import.meta.url))
  const axios = require('axios')
  const { JSDOM } = require('jsdom')
  const document = options.document || new JSDOM('<html><body></body></html>').window.document
  let dispose = () => {}
  const { nextTick } = require('vue')
  const values = new Map(), messages = [], routes = [], requests = [], pending = []
  const tokenKey = realm === 'web' ? 'token' : 'adminToken'
  values.set(tokenKey, 'synthetic-old-token')
  let closes = 0, opens = 0, instance
  const store = { userInfo: { userId: 'SYNTHETIC' }, showLogin: false,
    saveUserInfo(value) { this.userInfo = value },
    clearSession({ expectedToken, showLogin = false } = {}) {
      if (expectedToken !== undefined && values.get('token') !== expectedToken) return false
      values.delete('token'); this.userInfo = {}; this.showLogin = showLogin; return true
    } }
  const context = { axios: { create(config) { instance = axios.create({ ...config, adapter: config => {
    requests.push(config)
    return new Promise((resolve, reject) => pending.push({ resolve, reject, config }))
  } }); return instance } },
    document, hot: { dispose(callback) { dispose = callback } },
    useZIndex: options.loading?.useZIndex || (() => ({ nextZIndex: () => 2001 })),
    ElLoading: options.loading?.ElLoading || { service({ target }) {
      opens++
      const mask = document.createElement('div'); mask.className = 'el-loading-mask'; target.appendChild(mask)
      return { close() { closes++; mask.remove() } }
    } },
    Message: { error(message) { messages.push(message) } }, router: { push(route) { routes.push(route); return Promise.resolve() }, replace(route) { routes.push(route); return Promise.resolve() } },
    Api: { autoLogin: '/account/autoLogin' }, useLoginStore: () => store,
    localStorage: { getItem: key => values.get(key) ?? null, removeItem: key => values.delete(key) },
    FormData, File, nextTick, env: { PROD: false }, console }
  const code = fs.readFileSync(new URL(`../../easymall-front-${realm}/src/utils/Request.js`, import.meta.url), 'utf8')
    .replace(/^import .*$/gm, '').replaceAll('import.meta.env', 'env').replaceAll('import.meta.hot', 'hot').replace('export default request;', 'globalThis.request = request;')
    .replace('export default request\n', 'globalThis.request = request\n')
  vm.runInNewContext(code, context)
  return { document, disposeModule: () => dispose(), values, tokenKey, messages, routes, requests, pending, store,
    get opens() { return opens }, get closes() { return closes }, get instance() { return instance },
    request: config => context.request({ url: '/synthetic', ...config }),
    async wait() { await new Promise(resolve => setImmediate(resolve)) },
    respond(data, index = 0, status = 200) { const p = pending[index]; p.resolve({ data, status, config: p.config }) },
    fail(status, index = 0) { const p = pending[index]; p.reject(Object.assign(new Error('synthetic'), { config: p.config, ...(status ? { response: { status, data: {} } } : {}) })) } }
}

