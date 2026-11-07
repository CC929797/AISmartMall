import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'

const source = fs.readFileSync(new URL('../easymall-front-web/src/utils/worker/websocket.worker.js', import.meta.url), 'utf8')

// 执行真实 Worker，替换浏览器传输和时钟，不连接后端、不等待真实退避时间。
function harness({ constructorFails = false } = {}) {
  const sockets = [], messages = [], timers = new Map(), intervals = new Map()
  let id = 0, attempts = 0
  class Socket {
    static CONNECTING = 0
    static OPEN = 1
    constructor(url) {
      attempts++
      if (constructorFails) throw new Error('synthetic failure')
      this.url = url
      this.readyState = Socket.CONNECTING
      sockets.push(this)
    }
    close() { this.readyState = 3; this.closed = true }
    send(value) { this.sent = value }
    open() { this.readyState = Socket.OPEN; this.onopen?.() }
  }
  const context = { WebSocket: Socket, self: { postMessage: data => messages.push(data) }, console: { log() {}, error() {}, warn() {} },
    setTimeout(fn, delay) { const key = ++id; timers.set(key, { fn, delay }); return key },
    clearTimeout(key) { timers.delete(key) },
    setInterval(fn, delay) { const key = ++id; intervals.set(key, { fn, delay }); return key },
    clearInterval(key) { intervals.delete(key) } }
  vm.runInNewContext(source, context)
  return { sockets, messages, timers, intervals, get attempts() { return attempts },
    send(type, wsUrl = 'ws://synthetic.invalid/ws/synthetic-token') { context.self.onmessage({ data: { type, data: { wsUrl } } }) },
    tick() { const [key, value] = timers.entries().next().value; timers.delete(key); value.fn(); return value.delay } }
}

test('构造失败释放连接状态并安排重试', () => {
  const h = harness({ constructorFails: true })
  h.send('init')
  assert.equal(h.timers.size, 1)
  assert.equal(h.messages.at(-1).type, 'reconnecting')
  assert.equal(h.tick(), 2000)
  assert.equal(h.attempts, 2)
})

test('同一连接 error/close 只消耗一次重试，旧事件不能关闭新连接', () => {
  const h = harness()
  h.send('init')
  const old = h.sockets[0]
  old.open()
  const oldClose = old.onclose
  old.onerror({})
  oldClose({ code: 1006 })
  assert.equal(h.messages.filter(x => x.type === 'reconnecting').length, 1)
  h.tick()
  h.sockets[1].open()
  oldClose({ code: 1006 })
  assert.equal(h.intervals.size, 1)
  assert.equal(h.timers.size, 0)
})

test('首次连接加五次重试后停止，延时有上限', () => {
  const h = harness({ constructorFails: true })
  h.send('init')
  const delays = []
  while (h.timers.size) delays.push(h.tick())
  assert.equal(h.attempts, 6)
  assert.equal(h.messages.at(-1).type, 'failed')
  assert.equal(delays.length, 5)
  assert.ok(delays.every(x => x <= 10000))
})

test('连接超时会关闭旧连接并重试', () => {
  const h = harness()
  h.send('init')
  h.tick()
  assert.equal(h.sockets[0].closed, true)
  assert.equal(h.messages.at(-1).type, 'reconnecting')
  assert.equal(h.timers.size, 1)
})

test('主动关闭取消心跳和重试，迟到事件不会重新连接', () => {
  const h = harness()
  h.send('init')
  h.sockets[0].open()
  const oldClose = h.sockets[0].onclose
  h.sockets[0].onerror({})
  h.send('close')
  oldClose({ code: 1006 })
  assert.equal(h.timers.size, 0)
  assert.equal(h.intervals.size, 0)
  assert.equal(h.messages.at(-1).type, 'closed')
})

test('关闭后重新初始化能连接，重复初始化不重复创建连接', () => {
  const h = harness()
  h.send('init')
  h.send('init')
  assert.equal(h.sockets.length, 1)
  h.send('close')
  h.send('init')
  assert.equal(h.sockets.length, 2)
})

test('更换地址关闭旧连接，并忽略旧连接迟到的 open/message', () => {
  const h = harness()
  h.send('init')
  const oldOpen = h.sockets[0].onopen, oldMessage = h.sockets[0].onmessage
  h.send('init', 'ws://synthetic.invalid/ws/new-synthetic-token')
  oldOpen()
  oldMessage({ data: JSON.stringify({ messageId: '1', outPutType: 0, assistantMessage: 'old' }) })
  assert.equal(h.sockets[0].closed, true)
  assert.equal(h.messages.filter(x => x.type === 'message').length, 0)
})

test('坏 JSON、空值、数组和非法协议被隔离，后续合法消息仍可接收', () => {
  const h = harness()
  h.send('init')
  h.sockets[0].open()
  for (const data of ['invalid-json', 'null', '[]', '{}', '{"messageId":"1","outPutType":99}']) {
    assert.doesNotThrow(() => h.sockets[0].onmessage({ data }))
  }
  for (const outPutType of [0, 1, 2]) {
    h.sockets[0].onmessage({ data: JSON.stringify({ messageId: '1', outPutType, assistantMessage: outPutType === 1 ? null : 'synthetic' }) })
  }
  assert.equal(h.messages.filter(x => x.type === 'message').length, 3)
})

test('心跳发送失败也进入统一重连，成功重连后重置次数', () => {
  const h = harness()
  h.send('init')
  h.sockets[0].open()
  h.sockets[0].send = () => { throw new Error('synthetic send failure') }
  assert.doesNotThrow(() => h.intervals.values().next().value.fn())
  assert.equal(h.messages.at(-1).retryCount, 1)
  h.tick()
  h.sockets[1].open()
  h.sockets[1].onclose({ code: 1006 })
  assert.equal(h.messages.at(-1).retryCount, 1)
})
