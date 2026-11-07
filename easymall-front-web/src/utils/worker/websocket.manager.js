import { ref } from 'vue'
import { useMessageStore } from '@/stores/messageStore'

let worker = null;
let activeToken = '';
export const connectionState = ref({ status: 'closed', retryCount: 0, delay: 0 });
const setState = (status, detail = {}) => {
    connectionState.value = { status, retryCount: 0, delay: 0, ...detail };
};

const closeWebSocket = () => {
    const oldWorker = worker;
    worker = null;
    activeToken = '';
    if (oldWorker) {
        oldWorker.onmessage = oldWorker.onerror = null;
        // terminate 确保退出后不再运行重试、心跳或迟到消息回调。
        try { oldWorker.postMessage({ type: 'close' }); } catch { /* Worker 可能已退出。 */ }
        oldWorker.terminate();
    }
    setState('closed');
};

const initWs = ({ force = false } = {}) => {
    const token = localStorage.getItem('token');
    if (!token) { closeWebSocket(); return false; }
    if (worker && token === activeToken && !force) return true;
    closeWebSocket();
    const baseUrl = import.meta.env.VITE_WS;
    if (typeof baseUrl !== 'string' || !/^wss?:\/\//.test(baseUrl)) {
        setState('failed');
        return false;
    }
    const messageStore = useMessageStore();
    try {
        const current = new Worker(new URL('./websocket.worker.js', import.meta.url), { type: 'module' });
        worker = current;
        activeToken = token;
        setState('connecting');
        const isCurrent = () => {
            if (worker !== current) return false;
            if (localStorage.getItem('token') !== token) { closeWebSocket(); return false; }
            return true;
        };
        current.onmessage = ({ data: event }) => {
            if (!isCurrent() || !event) return;
            if (event.type === 'message') messageStore.onMessage(event.data);
            else if (['connecting', 'connected', 'reconnecting', 'failed', 'closed'].includes(event.type)) {
                setState(event.type, { retryCount: event.retryCount || 0, delay: event.delay || 0 });
            }
        };
        current.onerror = (event) => {
            if (!isCurrent()) return;
            // 浏览器错误对象可能包含 URL，不把它输出到控制台。
            event.preventDefault?.();
            closeWebSocket();
            setState('failed');
        };
        current.postMessage({ type: 'init', data: { wsUrl: baseUrl + encodeURIComponent(token) } });
        return true;
    } catch {
        closeWebSocket();
        setState('failed');
        return false;
    }
};

export { initWs, closeWebSocket };
