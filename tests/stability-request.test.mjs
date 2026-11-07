import test from 'node:test'
import assert from 'node:assert/strict'
import { harness } from './helpers/request-harness.mjs'

for (const realm of ['web', 'admin']) {
  test(`${realm}: 901/401 清理当前凭据并引导登录`, async () => {
    for (const status of [901, 401]) {
      const h = harness(realm), result = h.request()
      await h.wait()
      if (status === 901) h.respond({ code: 901 }); else h.fail(401)
      assert.equal(await result, null)
      assert.equal(h.values.has(h.tokenKey), false)
      assert.equal(realm === 'web' ? h.store.showLogin : h.routes.includes('/login'), true)
    }
  })

  test(`${realm}: 403 提示权限不足，保留会话`, async () => {
    const h = harness(realm), result = h.request()
    await h.wait(); h.fail(403); await result
    assert.equal(h.values.has(h.tokenKey), true)
    assert.match(h.messages[0], /权限/)
    assert.equal(h.routes.length, 0)
  })

  test(`${realm}: 缺少 config/response 的网络错误不会二次抛异常`, async () => {
    const h = harness(realm), result = h.request()
    await h.wait(); h.pending[0].reject(new Error('synthetic'))
    assert.equal(await result, null)
    assert.equal(h.values.has(h.tokenKey), true)
    assert.equal(h.closes, 1)
  })

  test(`${realm}: showError=false 对网络错误生效`, async () => {
    const h = harness(realm), result = h.request({ showError: false })
    await h.wait(); h.fail(); await result
    assert.equal(h.messages.length, 0)
  })

  test(`${realm}: 旧请求的失效响应不能撤销新登录`, async () => {
    const h = harness(realm), result = h.request()
    await h.wait(); h.values.set(h.tokenKey, 'synthetic-new-token')
    h.respond({ code: 901 }); await result
    assert.equal(h.values.get(h.tokenKey), 'synthetic-new-token')
    assert.equal(h.store.showLogin, false)
    assert.equal(h.routes.length, 0)
  })

  test(`${realm}: JSON 保留嵌套对象、表单保留数组和文件`, async () => {
    const h = harness(realm)
    const json = h.request({ dataType: 'json', params: { orderList: [{ buyCount: 2 }] } })
    await h.wait()
    assert.deepEqual(JSON.parse(h.requests[0].data), { orderList: [{ buyCount: 2 }] })
    assert.match(h.requests[0].headers.get('Content-Type'), /application\/json/)
    h.respond({ code: 200 }); await json
    const form = h.request({ params: { ids: ['a', 'b'], file: new File(['synthetic'], 'image.png', { type: 'image/png' }) } })
    await h.wait()
    assert.deepEqual(h.requests[1].data.getAll('ids'), ['a', 'b'])
    assert.equal(h.requests[1].data.get('file').name, 'image.png')
    h.respond({ code: 200 }, 1); await form
  })

  test(`${realm}: 并发请求全部结束后才关闭加载层`, async () => {
    const h = harness(realm), a = h.request(), b = h.request()
    await h.wait(); h.respond({ code: 200 }); await a
    assert.equal(h.closes, 0)
    h.respond({ code: 200 }, 1); await b
    assert.equal(h.closes, 1)
    assert.equal(h.opens, 1)
  })

  test(`${realm}: 超时/413/业务失败及回调保持兼容`, async () => {
    const h = harness(realm), calls = []
    const business = h.request({ errorCallback: data => calls.push(data.code) })
    await h.wait(); h.respond({ code: 600, info: 'synthetic business error' }); await business
    assert.deepEqual(calls, [600])
    const timeout = h.request()
    await h.wait(); h.pending[1].reject(Object.assign(new Error('synthetic'), { code: 'ECONNABORTED' })); await timeout
    assert.match(h.messages.at(-1), /超时/)
    const upload = h.request()
    await h.wait(); h.fail(413, 2); await upload
    assert.match(h.messages.at(-1), /大小/)
  })
}

test('admin: 退出接口HTTP200/204空响应转换为成功，不需要第二次请求', async () => {
  for (const [status, body] of [200, 204].flatMap(status => ['', null, undefined].map(body => [status, body]))) {
    const h = harness('admin'), result = h.request({ url: '/account/logout' })
    await h.wait(); h.respond(body, 0, status)
    assert.equal((await result)?.code, 200)
    assert.equal(h.requests.length, 1)
    assert.equal(h.messages.length, 0)
  }
})

test('admin: 普通接口空响应和退出业务失败不能伪装成功', async () => {
  for (const config of [{ url: '/synthetic', body: '' }, { url: '/account/logout', body: { code: 600, info: 'synthetic failure' } }]) {
    const h = harness('admin'), result = h.request({ url: config.url })
    await h.wait(); h.respond(config.body)
    assert.equal(await result, null)
    assert.equal(h.values.has('adminToken'), true)
    assert.equal(h.messages.length, 1)
  }
})
