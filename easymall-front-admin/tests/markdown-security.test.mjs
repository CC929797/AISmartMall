import test from 'node:test'
import assert from 'node:assert/strict'
import { JSDOM } from 'jsdom'
import MarkdownIt from 'markdown-it'

const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost:5173/' })
globalThis.window = dom.window
const { sanitizeMarkdown, secureMarkdown, isSafeMarkdownUrl } = await import('../src/utils/markdownSecurity.js')
for (const payload of [
  '<script>window.stolen=1</script>', '<img src=x onerror="window.stolen=1">',
  '<svg><a xlink:href="javascript:alert(1)">危险</a></svg>',
  '<iframe srcdoc="<script>alert(1)</script>"></iframe>',
  '<a href="javascript:alert(1)">点击</a>',
  '<a href="java&#x73;cript:alert(1)">点击</a>',
  '<a href="data:text/html,<script>alert(1)</script>">点击</a>',
  '<img src="data:image/svg+xml,<svg onload=alert(1)>">',
  '<img src="javascript:alert(1)">', '<div style="background:url(javascript:alert(1))">文字</div>',
  '<form id="attributes"><input name="__proto__"></form>',
  '<math><mtext><table><mglyph><style><!--</style><img title="--><img src=x onerror=alert(1)>">',
]) {
  test('清理恶意输出：' + payload.slice(0, 55), () => {
    const wrapper = dom.window.document.createElement('div')
    wrapper.innerHTML = sanitizeMarkdown(payload)
    assert.equal(wrapper.querySelector('script,svg,math,iframe,object,embed,form,input,style'), null)
    for (const node of wrapper.querySelectorAll('*')) {
      for (const attr of node.attributes) assert.ok(!/^on|^style$/i.test(attr.name))
      if (node.hasAttribute('href')) {
        assert.ok(isSafeMarkdownUrl(node.getAttribute('href')))
      }
      if (node.hasAttribute('src')) {
        assert.ok(isSafeMarkdownUrl(node.getAttribute('src'), true))
      }
    }
  })
}
test('正常 Markdown、代码、表格、图片和链接保持可用', () => {
  const md = new MarkdownIt()
  secureMarkdown(md)
  const result = sanitizeMarkdown(md.render('# 标题\n\n**粗体** [商城](https://example.com) ![商品](/api/file/getResource?sourceName=202611/test.png)\n\n|名称|价格|\n|---|---|\n|玩具|10|\n\n    const x = 1\n'))
  assert.match(result, /<h1>标题<\/h1>/)
  assert.match(result, /<strong>粗体<\/strong>/)
  assert.match(result, /<table>/)
  assert.match(result, /<code/)
  assert.match(result, /noopener noreferrer/)
  assert.match(result, /<img/)
})
test('原始 HTML 在 Markdown 解析阶段成为普通文本', () => {
  const md = new MarkdownIt()
  secureMarkdown(md)
  const result = sanitizeMarkdown(md.render('<img src=x onerror=alert(1)>\n<script>alert(1)</script>'))
  assert.doesNotMatch(result, /<img|<script/i)
  assert.match(result, /&lt;/)
})
for (const protocol of ['javascript:alert(1)', 'vbscript:msgbox(1)', 'data:image/png;base64,AA', 'file:///C:/test', 'blob:https://example.com/x', '//evil.example/x']) {
  test('禁止链接与图片地址：' + protocol, () => {
    assert.equal(isSafeMarkdownUrl(protocol), false)
    assert.equal(isSafeMarkdownUrl(protocol, true), false)
  })
}
test('链接与图片协议分别控制', () => {
  assert.equal(isSafeMarkdownUrl('mailto:test@example.com'), true)
  assert.equal(isSafeMarkdownUrl('mailto:test@example.com', true), false)
  assert.equal(isSafeMarkdownUrl('#section'), true)
  assert.equal(isSafeMarkdownUrl('#section', true), false)
})
