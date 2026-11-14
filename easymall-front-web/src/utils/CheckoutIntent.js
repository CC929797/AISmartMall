/**
 * 正式业务：兼容新旧后端的下单成功响应。
 * 新版显式状态优先；原版Mall没有requestStatus，只有完整支付信息时才视为已创建。
 * 这里只确认本地下单成功，不表示支付宝已经收单或用户已经付款。
 */
export function isCheckoutCreated(data) {
  if (!data || typeof data !== 'object' || Array.isArray(data)) return false
  if (Object.hasOwn(data, 'requestStatus')) return data.requestStatus === 'CREATED'
  return typeof data.payInfo === 'string' && data.payInfo.trim().length > 0
    && typeof data.payOrderId === 'string' && data.payOrderId.trim().length > 0
    && ['number', 'string'].includes(typeof data.amount)
    && Number.isFinite(Number(data.amount)) && Number(data.amount) > 0
}

/** 正式结算辅助：同一用户、同一购买内容复用requestId；刷新后也能恢复未确认的提交。 */
export function createCheckoutIntent(storage = globalThis.sessionStorage, cryptoApi = globalThis.crypto) {
  const memory = new Map()
  const keyFor = userId => `easymall.checkout.${userId}`
  const read = userId => {
    try { return JSON.parse(storage?.getItem(keyFor(userId)) || 'null') || memory.get(userId) }
    catch { return memory.get(userId) }
  }
  const write = (userId, value) => {
    memory.set(userId, value)
    try { storage?.setItem(keyFor(userId), JSON.stringify(value)) } catch { /* 当前页面仍通过内存复用。 */ }
  }
  return {
    /** 只有用户明确重新打开结算且上次已确认创建，才结束上一次购买意图。 */
    begin(userId) {
      if (!read(userId)?.completed) return
      memory.delete(userId)
      try { storage?.removeItem(keyFor(userId)) } catch { /* 无可用存储时使用当前实例内存。 */ }
    },
    async get(userId, content) {
      if (!userId) throw new Error('请先登录后再提交订单')
      if (!cryptoApi?.subtle || !cryptoApi?.randomUUID) throw new Error('当前浏览器无法安全提交，请使用HTTPS或localhost访问。')
      const merged = new Map()
      for (const item of content.orderList) {
        const key = JSON.stringify([item.productId, item.propertyValueIds, item.remark || ''])
        merged.set(key, (merged.get(key) || 0) + item.buyCount)
      }
      const canonical = JSON.stringify([content.addressId, content.payMethod, content.orderFrom,
        [...merged].sort(([a], [b]) => a.localeCompare(b))])
      // 只保存摘要与随机编号，不把地址、备注等请求原文写入sessionStorage。
      const fingerprint = [...new Uint8Array(await cryptoApi.subtle.digest('SHA-256', new TextEncoder().encode(canonical)))]
        .map(value => value.toString(16).padStart(2, '0')).join('')
      const previous = read(userId)
      if (previous?.fingerprint === fingerprint) return previous.requestId
      const requestId = cryptoApi.randomUUID()
      write(userId, { fingerprint, requestId, completed: false })
      return requestId
    },
    complete(userId, requestId) {
      const current = read(userId)
      if (current?.requestId === requestId) write(userId, { ...current, completed: true })
    },
  }
}
