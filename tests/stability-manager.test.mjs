import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'

function harness({ fails = false, token = 'synthetic-token' } = {}) {
  const workers = [], messages = [], storage = new Map(token ? [['token', token]] : [])
  class Worker {
    constructor() { if (fails) throw new Error('synthetic'); workers.push(this) }
    postMessage(value) { this.command = value }
    terminate() { this.terminated = true }
  }
  const context = { ref: value => ({ value }), useMessageStore: () => ({ onMessage: value => messages.push(value) }),
    Worker, URL, localStorage: { getItem: key => storage.get(key) ?? null }, env: { VITE_WS: 'ws://synthetic.invalid/ws/' } }
  const source = fs.readFileSync(new URL('../easymall-front-web/src/utils/worker/websocket.manager.js', import.meta.url), 'utf8')
    .replace(/^import .*$/gm, '').replace('export const connectionState', 'const connectionState')
    .replaceAll('import.meta.env', 'env').replaceAll('import.meta.url', "'file:///synthetic'")
    .replace('export { initWs, closeWebSocket };', 'globalThis.api = { initWs, closeWebSocket, connectionState };')
  vm.runInNewContext(source, context)
  return { ...context.api, workers, messages, storage }
}

test('重复初始化复用 Worker，更换凭据销毁旧 Worker 并忽略旧消息', () => {
  const h = harness()
  h.initWs(); h.initWs(); assert.equal(h.workers.length, 1)
  const oldMessage = h.workers[0].onmessage
  h.storage.set('token', 'synthetic-new-token'); h.initWs()
  oldMessage({ data: { type: 'message', data: 'old' } })
  assert.equal(h.workers[0].terminated, true); assert.equal(h.workers.length, 2); assert.equal(h.messages.length, 0)
})

test('退出终止 Worker，重新登录可新建连接', () => {
  const h = harness(); h.initWs(); h.closeWebSocket()
  assert.equal(h.workers[0].terminated, true); assert.equal(h.connectionState.value.status, 'closed')
  h.initWs(); assert.equal(h.workers.length, 2)
})

test('没有凭据不创建 Worker，构造失败提供失败状态', () => {
  const empty = harness({ token: null }); assert.equal(empty.initWs(), false); assert.equal(empty.workers.length, 0)
  const failure = harness({ fails: true }); assert.equal(failure.initWs(), false); assert.equal(failure.connectionState.value.status, 'failed')
})

test('Worker 错误可手动重试，重试耗尽同样可手动恢复', () => {
  const h = harness(); h.initWs(); h.workers[0].onerror({ preventDefault() {} })
  assert.equal(h.connectionState.value.status, 'failed'); assert.equal(h.workers[0].terminated, true)
  h.initWs({ force: true }); assert.equal(h.workers.length, 2)
  h.workers[1].onmessage({ data: { type: 'failed' } })
  h.initWs({ force: true }); assert.equal(h.workers[1].terminated, true); assert.equal(h.workers.length, 3)
})

test('外部清除凭据后旧 Worker 消息被拒绝并终止连接', () => {
  const h = harness(); h.initWs(); h.storage.delete('token')
  h.workers[0].onmessage({ data: { type: 'message', data: 'stale' } })
  assert.equal(h.messages.length, 0); assert.equal(h.workers[0].terminated, true)
})
