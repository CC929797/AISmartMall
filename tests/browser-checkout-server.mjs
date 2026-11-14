// 仅用于步骤08浏览器验证：本机模拟接口与合成数据，不连接MySQL、Redis或任何支付网关。
import { createRequire } from 'node:module'
import { fileURLToPath, pathToFileURL } from 'node:url'
import path from 'node:path'
const project = fileURLToPath(new URL('../easymall-front-web/', import.meta.url))
const require = createRequire(path.join(project, 'package.json'))
const { createServer } = await import(pathToFileURL(require.resolve('vite')).href)
const { default: vue } = await import(pathToFileURL(require.resolve('@vitejs/plugin-vue')).href)
const state = { submissions: [], groups: new Map(), paymentRetries: 0 }
const data = { requestStatus: 'CREATED', paymentStatus: 'UNAVAILABLE', payOrderId: 'STEP08-SYNTHETIC-PAY', orderId: 'STEP08-SYNTHETIC-ORDER', amount: 12.34 }
const respond = (res, value) => { res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify({ code: 200, data: value })) }
const entry = `
import { createApp, h, ref } from 'vue'; import { createPinia } from 'pinia';
import { createRouter,createWebHistory } from 'vue-router'; import ElementPlus from 'element-plus'; import 'element-plus/dist/index.css';
import '/src/assets/base.scss'; import ProductBuy from '/src/views/product/ProductBuy.vue';
import Drawer from '/src/components/Drawer.vue'; import Dialog from '/src/components/Dialog.vue'; import Price from '/src/components/Price.vue';
import Request from '/src/utils/Request.js'; import Message from '/src/utils/Message.js'; import {Api} from '/src/utils/Api.js';
import Utils from '/src/utils/Utils.js';
import {useLoginStore} from '/src/stores/loginStore.js';
const pinia=createPinia(); const app=createApp({setup(){
 const buy=ref(),stats=ref('尚未提交'),done=ref(0);const login=useLoginStore();login.saveUserInfo({userId:'STEP08TEST'});localStorage.setItem('token','synthetic-step08');
 const open=()=>buy.value.showBuy([{productId:'P1',productName:'步骤08合成商品',propertyValueIds:'S1',buyCount:1,price:12.34,propertyData:[{propertyName:'颜色',propertyValue:'蓝色'}]}],0);
 const refresh=async()=>{stats.value=JSON.stringify(await(await fetch('/__step08/stats')).json())};
 return()=>h('main',{style:'padding:28px;font-family:sans-serif'},[
 h('h1','步骤08结算复验（模拟接口）'),h('p','首次提交模拟响应丢失；可重试或查询结果，再继续支付原订单。没有真实交易。'),
 h('button',{onClick:open},'打开结算'),h('button',{onClick:refresh,style:'margin-left:16px'},'刷新统计'),
 h('p','已确认创建次数：'+done.value),h('output',{style:'display:block;white-space:pre-wrap'},stats.value),
 h(ProductBuy,{ref:buy,onSubmitted:()=>{done.value++;refresh()}})]);
}}); app.use(pinia);app.use(ElementPlus);app.use(createRouter({history:createWebHistory(),routes:[{path:'/:pathMatch(.*)*',component:{render:()=>null}}]}));
app.component('Drawer',Drawer);app.component('Dialog',Dialog);app.component('Price',Price);
app.component('NoData',{render:()=>h('span','暂无数据')});app.component('Cover',{render:()=>h('span','合成商品图')});
Object.assign(app.config.globalProperties,{Request,Message,Api,Utils:{...Utils,jump:()=>Message.warning('测试页面：此处跳转到我的订单')}});app.mount('#app');`
const server = await createServer({ root: project, configFile: false, cacheDir: path.join(project, 'node_modules/.vite-step08'),
  resolve: { alias: { '@': path.join(project, 'src') } }, server: { host: '127.0.0.1', port: 16080, strictPort: true },
  plugins: [vue(), { name: 'checkout-browser-fixture',
    resolveId(id) { if (id === '/step08-entry.js') return '\0step08-entry' },
    load(id) { if (id === '\0step08-entry') return entry },
    transformIndexHtml() { return '<!doctype html><html lang="zh-CN"><head><meta charset="UTF-8"><title>步骤08结算隔离复验</title></head><body><div id="app"></div><script type="module" src="/step08-entry.js"></script></body></html>' },
    configureServer(vite) {
      vite.middlewares.use(async (req, res, next) => {
        if (req.url === '/__step08/stats') { res.setHeader('Content-Type', 'application/json'); return res.end(JSON.stringify({ requestIds: state.submissions, groups: state.groups.size, paymentRetries: state.paymentRetries })) }
        if (!req.url?.startsWith('/api/')) return next()
        let body = ''; for await (const chunk of req) body += chunk
        const route = req.url.slice(4)
        if (route === '/userAddress/loadDataList') return respond(res, [{ addressId: 'STEP08-ADDRESS', address: '步骤08合成收货地址', addressee: '合成收货人', phone: '13000000000', defaultType: 1 }])
        if (route === '/order/postOrder') {
          const order = JSON.parse(body); state.submissions.push(order.requestId)
          const first = state.groups.size === 0; state.groups.set(order.requestId, data)
          if (first) { setTimeout(() => res.destroy(), 800); return }
          return respond(res, data)
        }
        if (route === '/order/getOrderRequest') return respond(res, data)
        if (route === '/order/getOrderInfo') return respond(res, { orderId: data.orderId, orderStatus: 0 })
        if (route === '/order/getPayInfo') {
          state.paymentRetries++
          return respond(res, { ...data, paymentStatus: 'READY', payInfo: '<!doctype html><html><head><meta charset="UTF-8"><title>步骤08模拟支付页</title></head><body><h1>原订单支付页面已恢复</h1><p>合成业务号：STEP08-SYNTHETIC-PAY；没有真实支付。</p></body></html>' })
        }
        return respond(res, [])
      })
    },
  }],
})
await server.listen(); console.log('步骤08隔离页面：http://127.0.0.1:16080/')
process.on('SIGINT', async () => { await server.close(); process.exit(0) })
