// 仅用于步骤07浏览器验收：127.0.0.1 独立端口、合成账号、模拟 HTTP/WS，无真实模型或业务写入。
import { createRequire } from 'node:module'
import { fileURLToPath, pathToFileURL } from 'node:url'
import path from 'node:path'

const project = fileURLToPath(new URL('../easymall-front-web/', import.meta.url))
const require = createRequire(path.join(project, 'package.json'))
const { createServer } = await import(pathToFileURL(require.resolve('vite')).href)
const { default: vue } = await import(pathToFileURL(require.resolve('@vitejs/plugin-vue')).href)
const { WebSocketServer } = require('ws')
const port = 16070
const clients = new Set()
const stats = { attempts: 0, active: 0, sentRequests: 0, online: true }
const user = { token: 'synthetic-browser-token', userId: 'STEP07', nickName: '隔离测试用户', avatar: null }
const json = (res, data, status = 200) => { res.statusCode = status; res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(data)) }
const panel = `<aside style="position:fixed;bottom:0;left:0;right:0;z-index:9999;background:#fff;border-top:2px solid #555;padding:8px;font:13px sans-serif">
步骤07隔离验收（模拟接口）
<a href="/?__case=guest">游客首页</a> · <a href="/?__case=expired">过期凭据首页</a> · <a href="/?__case=network">网络失败首页</a> · <a href="/agent?__case=valid">已登录 AI</a>
<button data-step07="drop">断开连接</button><button data-step07="bad">发送坏消息</button><button data-step07="offline">服务离线</button><button data-step07="online">服务恢复</button><button data-step07="stats">刷新统计</button>
<output id="step07-stats"></output></aside>
<script>document.querySelectorAll('[data-step07]').forEach(button=>button.onclick=async()=>{const r=await fetch('/__step07/'+button.dataset.step07,{method:'POST'});document.querySelector('#step07-stats').textContent=JSON.stringify(await r.json())});</script>`

const server = await createServer({ root: project, configFile: false,
  resolve: { alias: { '@': path.join(project, 'src') } },
  cacheDir: path.join(project, 'node_modules/.vite-step07'),
  define: { 'import.meta.env.VITE_WS': JSON.stringify(`ws://127.0.0.1:${port}/ws/`) },
  server: { host: '127.0.0.1', port, strictPort: true },
  plugins: [vue(), { name: 'step07-isolated-api',
    transformIndexHtml(html) {
      const seed = `<script>const step07Case=new URLSearchParams(location.search).get('__case');if(step07Case){document.cookie='step07case='+step07Case+';path=/';if(step07Case==='guest')localStorage.removeItem('token');else localStorage.setItem('token',step07Case==='expired'?'synthetic-expired-token':'synthetic-browser-token');}</script>`
      return html.replace('<head>', '<head>' + seed).replace('</body>', panel + '</body>')
    },
    configureServer(vite) {
      vite.middlewares.use((req, res, next) => {
        const url = new URL(req.url, 'http://localhost')
        if (url.pathname.startsWith('/__step07/')) {
          const action = url.pathname.split('/').at(-1)
          if (action === 'offline') stats.online = false
          if (action === 'online') stats.online = true
          if (action === 'drop' || action === 'offline') for (const client of clients) client.terminate()
          if (action === 'bad') for (const client of clients) { client.send('invalid-json'); client.send('{"outPutType":99}') }
          return json(res, stats)
        }
        if (!url.pathname.startsWith('/api/')) return next()
        req.resume()
        const route = url.pathname.slice(4)
        if (route === '/account/autoLogin') {
          if (req.headers.cookie?.includes('step07case=network')) return req.socket.destroy()
          return json(res, { code: 200, data: req.headers.token === 'synthetic-expired-token' ? null : user })
        }
        if (route === '/account/login') return json(res, { code: 200, data: user })
        if (route === '/account/checkCode') return json(res, { code: 200, data: { checkCodeKey: 'synthetic-code', checkCode: 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="100" height="40"><text x="4" y="25">TEST</text></svg>') } })
        if (route === '/account/logout') return json(res, { code: 200, data: null })
        if (route === '/agent/sendMessage') {
          stats.sentRequests++
          setTimeout(() => {
            for (const client of clients) {
              client.send(JSON.stringify({ messageId: '1', outPutType: 0, assistantMessage: '隔离测试回复：连接和消息接收正常。' }))
              client.send(JSON.stringify({ messageId: '1', outPutType: 1 }))
            }
          }, 300)
          return json(res, { code: 200, data: { messageId: '1', userMessage: '连接测试', assistantMessage: '', status: 1 } })
        }
        if (route === '/product/loadCategory' || route === '/product/loadCommendProduct') return json(res, { code: 200, data: [] })
        return json(res, { code: 200, data: { list: [], pageNo: 1, pageTotal: 0, totalCount: 0 } })
      })
    },
  }],
})
const wss = new WebSocketServer({ noServer: true })
server.httpServer.on('upgrade', (req, socket, head) => {
  if (!req.url?.startsWith('/ws/')) return
  stats.attempts++
  if (!stats.online) return socket.destroy()
  wss.handleUpgrade(req, socket, head, client => {
    clients.add(client); stats.active = clients.size
    client.on('close', () => { clients.delete(client); stats.active = clients.size })
  })
})
await server.listen()
console.log(`步骤07隔离验收：http://127.0.0.1:${port}/?__case=guest`)
process.on('SIGINT', async () => { for (const client of clients) client.terminate(); wss.close(); await server.close(); process.exit(0) })
