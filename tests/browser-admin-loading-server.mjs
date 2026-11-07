// 管理端登录→首页的加载层隔离复现，不连接真实后台或数据库。
import { createRequire } from 'node:module'
import { fileURLToPath, pathToFileURL } from 'node:url'
import path from 'node:path'
const root = fileURLToPath(new URL('../easymall-front-admin/', import.meta.url))
const require = createRequire(path.join(root, 'package.json'))
const { createServer } = await import(pathToFileURL(require.resolve('vite')).href)
const { default: vue } = await import(pathToFileURL(require.resolve('@vitejs/plugin-vue')).href)
const jsonLogout = process.argv.includes('--json-logout')
let logoutRequests = 0
const server = await createServer({ root, configFile: false, plugins: [vue(), {
  name: 'admin-loading-reproduction',
  transformIndexHtml(html) {
    return html.replace('<head>', `<head><script>localStorage.setItem('adminToken','synthetic-admin-loading')</script>`)
      .replace('</body>', `<aside style="position:fixed;bottom:0;z-index:99999;background:white;border:1px solid #555;padding:10px">管理端隔离验收（模拟接口） <button id="loading-login">模拟登录跳转</button> <button id="loading-reload">重新载入首页</button> <button id="logout-stats">刷新退出统计</button><output id="logout-result"></output> <output id="loading-result"></output></aside>
<script type="module">import Request from '/src/utils/Request.js';import router from '/src/router/index.js';
document.querySelector('#loading-login').onclick=async()=>{await router.push('/login');const result=await Request({url:'/account/login'});localStorage.setItem('adminToken',result.data);await router.push('/home')};
document.querySelector('#loading-reload').onclick=async()=>{await router.push('/login');await router.push('/home')};
document.querySelector('#logout-stats').onclick=async()=>{const result=await(await fetch('/__logout-stats')).json();document.querySelector('#logout-result').textContent='退出请求：'+result.count+'，凭据：'+(localStorage.getItem('adminToken')?'存在':'已清除')};
setInterval(()=>{document.querySelector('#loading-result').textContent='遮罩数量：'+document.querySelectorAll('.el-loading-mask').length+'，滚动锁：'+(document.body.style.overflow==='hidden'||document.body.classList.contains('el-loading-parent--hidden'))},200);</script></body>`)
  },
  configureServer(vite) { vite.middlewares.use((req, res, next) => {
    if (req.url === '/__logout-stats') { res.setHeader('Content-Type', 'application/json'); return res.end(JSON.stringify({ count: logoutRequests, jsonLogout })) }
    if (!req.url?.startsWith('/api/')) return next()
    req.resume()
    if (req.url.includes('/account/logout')) {
      logoutRequests++
      if (!jsonLogout) { res.statusCode = 200; return res.end() }
      res.setHeader('Content-Type', 'application/json'); return res.end(JSON.stringify({ code: 200, status: 'success', data: null }))
    }
    let data = { list: [], pageNo: 1, pageTotal: 0, totalCount: 0 }, delay = 10
    if (req.url.includes('/account/checkCode')) data = { checkCodeKey: 'synthetic-code', checkCode: '' }
    if (req.url.includes('/account/login')) data = 'synthetic-admin-loading'
    if (req.url.includes('/home/getTodayData')) data = ['orderAmount', 'orderCount', 'userCount', 'refundAmount'].map(type => ({ type, todayValue: 0, yesterdayValue: 0, increase: 0 }))
    if (req.url.includes('/home/loadWeeklyStatisticsData')) { data = [1, 2, 3, 4].map(dataType => ({ dataType, dateList: ['2026-11-07'], dataList: [0] })); delay = 50 }
    setTimeout(() => { res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify({ code: 200, data })) }, delay)
  }) },
}], resolve: { alias: { '@': path.join(root, 'src') } }, cacheDir: path.join(root, 'node_modules/.vite-loading-regression'),
server: { host: '127.0.0.1', port: 16072, strictPort: true } })
await server.listen()
console.log('管理端加载层复现：http://127.0.0.1:16072/home')
process.on('SIGINT', async () => { await server.close(); process.exit(0) })
