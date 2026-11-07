const MAX_RETRIES = 5;
const RETRY_INTERVAL = 2000;
const MAX_RETRY_DELAY = 10000;
const CONNECT_TIMEOUT = 10000;
const HEARTBEAT_INTERVAL = 5000;
let ws = null;
let wsUrl = '';
let needReconnect = false;
let isConnecting = false;
let retryCount = 0;
let reconnectTimer = null;
let connectTimer = null;
let heartbeatTimer = null;

const notify = (type, detail = {}) => self.postMessage({ type, ...detail });
const clearTimers = () => {
    clearTimeout(connectTimer);
    clearTimeout(reconnectTimer);
    clearInterval(heartbeatTimer);
    connectTimer = reconnectTimer = heartbeatTimer = null;
};
const disposeSocket = () => {
    const socket = ws;
    ws = null;
    isConnecting = false;
    clearTimers();
    if (!socket) return;
    socket.onopen = socket.onclose = socket.onerror = socket.onmessage = null;
    if (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING) {
        try { socket.close(); } catch { /* 连接尚未建立时也必须完成本地清理。 */ }
    }
};

const scheduleReconnect = () => {
    if (!needReconnect || reconnectTimer !== null) return;
    if (retryCount >= MAX_RETRIES) {
        needReconnect = false;
        notify('failed');
        return;
    }
    retryCount++;
    const delay = Math.min(RETRY_INTERVAL * Math.pow(1.5, retryCount - 1), MAX_RETRY_DELAY);
    notify('reconnecting', { retryCount, delay });
    reconnectTimer = setTimeout(() => {
        reconnectTimer = null;
        connectWs();
    }, delay);
};
const failConnection = (socket) => {
    // error、close、超时可能连续到达，同一连接只处理一次。
    if (!needReconnect || (socket && ws !== socket)) return;
    disposeSocket();
    scheduleReconnect();
};

const validMessage = (data) => {
    if (!data || typeof data !== 'object' || Array.isArray(data)) return false;
    const id = data.messageId;
    if (!(Number.isSafeInteger(id) && id >= 0) && !(typeof id === 'string' && /^\d+$/.test(id))) return false;
    if (![0, 1, 2].includes(data.outPutType)) return false;
    if (data.outPutType === 0 && typeof data.assistantMessage !== 'string') return false;
    return data.assistantMessage == null || typeof data.assistantMessage === 'string';
};

const connectWs = () => {
    if (!needReconnect || isConnecting || ws) return;
    isConnecting = true;
    notify('connecting');
    try {
        const socket = new WebSocket(wsUrl);
        ws = socket;
        const isCurrent = () => needReconnect && ws === socket;
        connectTimer = setTimeout(() => failConnection(socket), CONNECT_TIMEOUT);
        socket.onopen = () => {
            if (!isCurrent()) return;
            clearTimeout(connectTimer);
            connectTimer = null;
            isConnecting = false;
            retryCount = 0;
            notify('connected');
            heartbeatTimer = setInterval(() => {
                if (!isCurrent()) return;
                try {
                    if (socket.readyState !== WebSocket.OPEN) return failConnection(socket);
                    socket.send('ping');
                } catch { failConnection(socket); }
            }, HEARTBEAT_INTERVAL);
        };
        socket.onmessage = (event) => {
            if (!isCurrent()) return;
            try {
                const data = JSON.parse(event.data);
                if (validMessage(data)) notify('message', { data });
            } catch { /* 坏消息只丢弃，不输出内容或凭据，不影响后续消息。 */ }
        };
        socket.onerror = () => failConnection(socket);
        // 是否重连由本地关闭意图决定，不能只凭服务端 close code 判断。
        socket.onclose = () => failConnection(socket);
    } catch {
        failConnection(null);
    }
};

self.onmessage = ({ data: command }) => {
    if (command?.type === 'close') {
        needReconnect = false;
        disposeSocket();
        notify('closed');
    } else if (command?.type === 'init' && typeof command.data?.wsUrl === 'string') {
        const url = command.data.wsUrl;
        if (url === wsUrl && needReconnect && (ws || reconnectTimer !== null)) return;
        needReconnect = false;
        disposeSocket();
        wsUrl = url;
        retryCount = 0;
        needReconnect = true;
        connectWs();
    }
};
