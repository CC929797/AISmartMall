import createDOMPurify from 'dompurify'

// HTML 清理与 Markdown 解析同时收紧，原始 HTML 不参与渲染。
const purifier = typeof window === 'undefined' ? null : createDOMPurify(window)
export function secureMarkdown(md) { md.set({ html: false }) }

export function isSafeMarkdownUrl(value, image = false) {
  if (typeof value !== 'string') {
    return false
  }
  const text = value.trim()
  if (!text || /[\u0000-\u0020\u007f\\]/u.test(text) || text.startsWith('//')) {
    return false
  }
  if (text.startsWith('#')) {
    return !image
  }
  try {
    const url = new URL(text, typeof window === 'undefined' ? 'http://localhost/' : window.location.href)
    return image ? ['http:', 'https:'].includes(url.protocol)
      : ['http:', 'https:', 'mailto:'].includes(url.protocol)
  } catch { return false }
}

export function sanitizeMarkdown(html) {
  if (!purifier) {
    return ''
  }
  const clean = purifier.sanitize(html, {
    ALLOWED_TAGS: ['p', 'br', 'hr', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'blockquote',
      'pre', 'code', 'em', 'strong', 's', 'del', 'u', 'ul', 'ol', 'li',
      'table', 'thead', 'tbody', 'tr', 'th', 'td', 'a', 'img', 'span', 'div', 'sup', 'sub'],
    ALLOWED_ATTR: ['class', 'href', 'title', 'target', 'rel', 'src', 'alt', 'width', 'height', 'align'],
    ALLOW_DATA_ATTR: false,
    ALLOW_ARIA_ATTR: false,
    FORBID_TAGS: ['script', 'style', 'svg', 'math', 'iframe', 'object', 'embed', 'form', 'input'],
  })
  // template 内容保持惰性，只有最终清理结果才交给组件插入页面。
  const template = window.document.createElement('template')
  template.innerHTML = clean
  for (const link of template.content.querySelectorAll('a')) {
    if (!isSafeMarkdownUrl(link.getAttribute('href'))) {
      link.removeAttribute('href')
      link.removeAttribute('target')
    } else {
      link.setAttribute('rel', 'noopener noreferrer')
      if (!link.getAttribute('href').startsWith('#')) {
        link.setAttribute('target', '_blank')
      }
    }
  }
  for (const image of template.content.querySelectorAll('img')) {
    if (!isSafeMarkdownUrl(image.getAttribute('src'), true)) {
      image.remove()
    }
  }
  return template.innerHTML
}
