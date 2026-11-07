import test from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { harness } from './helpers/request-harness.mjs'

const webRequire = createRequire(new URL('../easymall-front-web/package.json', import.meta.url))
const { JSDOM } = webRequire('jsdom')
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/', pretendToBeVisual: true })
for (const key of ['window', 'document', 'Element', 'HTMLElement', 'SVGElement', 'Node', 'MutationObserver']) globalThis[key] = dom.window[key]
for (const key of ['getComputedStyle', 'requestAnimationFrame', 'cancelAnimationFrame']) globalThis[key] = dom.window[key].bind(dom.window)
const settleTransition = () => new Promise(resolve => setTimeout(resolve, 450))
const masks = () => document.querySelectorAll('.el-loading-mask').length

for (const realm of ['web', 'admin']) {
  const require = createRequire(new URL(`../easymall-front-${realm}/package.json`, import.meta.url))
  const { ElLoading, useZIndex } = require('element-plus')
  const create = () => harness(realm, { document, loading: { ElLoading, useZIndex } })

  test(`${realm}: 真实加载组件在全部并发请求结束后移除遮罩并恢复滚动`, async () => {
    document.body.style.overflow = 'auto'
    const h = create(), a = h.request(), b = h.request()
    await h.wait(); assert.equal(masks(), 1); assert.equal(document.body.style.overflow, 'hidden')
    h.respond({ code: 200 }); await a; assert.equal(masks(), 1)
    h.respond({ code: 200 }, 1); await b
    assert.equal(masks(), 0); assert.equal(document.body.style.overflow, 'auto')
    await settleTransition(); assert.equal(masks(), 0)
  })

  test(`${realm}: 登录后连续触发多轮首页请求不会累积真实遮罩`, async () => {
    const h = create()
    for (let round = 0; round < 5; round++) {
      const first = h.pending.length, requests = Array.from({ length: 4 }, () => h.request())
      await h.wait()
      for (let i = 0; i < 4; i++) h.respond({ code: 200 }, first + i)
      await Promise.all(requests)
      assert.equal(masks(), 0)
    }
    await settleTransition(); assert.equal(masks(), 0)
  })

  test(`${realm}: 网络错误、401和业务失败也清理真实遮罩`, async () => {
    const h = create()
    for (const outcome of ['network', 401, 901, 600]) {
      const index = h.pending.length, request = h.request()
      await h.wait()
      if (outcome === 'network') h.fail(undefined, index)
      else if (outcome === 401) h.fail(401, index)
      else h.respond({ code: outcome }, index)
      assert.equal(await request, null); assert.equal(masks(), 0)
    }
    await settleTransition(); assert.equal(masks(), 0)
  })

  test(`${realm}: HTTP请求结束不会关闭其他任务的全屏加载实例`, async () => {
    const foreign = ElLoading.service({ fullscreen: true, lock: true, text: '其他合成任务' })
    const node = foreign.$el
    try {
      const h = create(), request = h.request()
      await h.wait(); h.respond({ code: 200 }); await request
      assert.equal(node.isConnected, true)
      assert.equal(foreign.visible.value, true)
      assert.equal(masks(), 1)
    } finally { foreign.close(); await settleTransition() }
    assert.equal(masks(), 0)
  })

  test(`${realm}: 热更新释放旧遮罩，旧请求结束不会关闭新一轮加载层`, async () => {
    const h = create(), oldRequest = h.request()
    await h.wait(); h.disposeModule(); assert.equal(masks(), 0)
    const newRequest = h.request()
    await h.wait(); h.respond({ code: 200 }); await oldRequest
    assert.equal(masks(), 1)
    h.respond({ code: 200 }, 1); await newRequest
    assert.equal(masks(), 0)
    await settleTransition(); assert.equal(masks(), 0)
  })
}
