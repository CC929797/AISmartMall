import axios from 'axios'
import { nextTick } from 'vue'
import { ElLoading, useZIndex } from 'element-plus'
import Message from './Message'
import { useLoginStore } from '@/stores/loginStore.js'

const instance = axios.create({
    withCredentials: true,
    baseURL: (import.meta.env.PROD ? import.meta.env.VITE_DOMAIN : '') + '/api',
    timeout: 10 * 1000,
});

// 每轮请求拥有独立容器，避免共用全屏单例时遗留遮罩或关闭其他任务的加载层。
let loading = null;
const closeLoading = (current) => {
    if (!current || current.closed) return;
    current.closed = true;
    if (loading === current) loading = null;
    try {
        current.instance.close();
    } finally {
        current.container.remove();
        document.body.style.overflow = current.previousOverflow;
    }
};
const startLoading = () => {
    if (!loading) {
        const container = document.createElement('div');
        container.className = 'easymall-request-loading-root';
        container.setAttribute('role', 'status');
        container.setAttribute('aria-live', 'polite');
        Object.assign(container.style, { position: 'fixed', inset: '0', zIndex: String(useZIndex().nextZIndex()) });
        document.body.appendChild(container);
        const previousOverflow = document.body.style.overflow;
        document.body.style.overflow = 'hidden';
        try {
            loading = { container, previousOverflow, count: 0, closed: false,
                instance: ElLoading.service({ target: container, fullscreen: false,
                    text: '加载中......', background: 'rgba(0, 0, 0, 0.7)' }) };
        } catch (error) {
            container.remove();
            document.body.style.overflow = previousOverflow;
            throw error;
        }
    }
    const current = loading;
    current.count++;
    let finished = false;
    return () => {
        if (finished || current.closed) return;
        finished = true;
        current.count--;
        if (current.count !== 0) return;
        // 等本轮 Vue 更新结束；期间新请求仍可复用该容器。
        return nextTick(() => {
            if (current.count === 0) closeLoading(current);
        });
    };
};

// 开发热更新也要释放本模块创建的遮罩和滚动锁。
if (import.meta.hot) import.meta.hot.dispose(() => closeLoading(loading));

const expireSession = (token, prompt) => {
    useLoginStore().clearSession({ expectedToken: token, ...(prompt ? { showLogin: true } : {}) });
};

const request = async (config = {}) => {
    const { url, params = {}, dataType, showLoading = true, responseType = 'json', showError = true,
        promptOnAuthFailure = true, errorCallback } = config;
    let finishLoading = () => {};
    let token = '';
    try {
        token = localStorage.getItem('token') || '';
        if (url === '/file/uploadImage' && params?.file instanceof File &&
            (!/\.(jpe?g|png)$/i.test(params.file.name) || params.file.size > 5 * 1024 * 1024 || params.file.size === 0)) {
            if (showError) Message.error('请选择不超过5 MiB的JPG/JPEG或PNG图片');
            return null;
        }
        const json = dataType === 'json';
        let data = params;
        if (!json && !(params instanceof FormData)) {
            data = new FormData();
            for (const [key, value] of Object.entries(params || {})) {
                for (const item of (Array.isArray(value) ? value : [value])) {
                    data.append(key, item == null ? '' : item);
                }
            }
        }
        if (showLoading) finishLoading = startLoading();
        const response = await instance.post(url, data, {
            // FormData 的 boundary 由浏览器生成，JSON 显式声明类型。
            headers: { ...(json ? { 'Content-Type': 'application/json' } : {}),
                'X-Requested-With': 'XMLHttpRequest', 'token': token },
            responseType,
            onUploadProgress: config.uploadProgressCallback,
        });
        if (responseType === 'blob' || responseType === 'arraybuffer') return response.data;
        const result = response.data;
        if (result?.code == 200) return result;
        if (result?.code == 901) {
            expireSession(token, promptOnAuthFailure);
            return null;
        }
        errorCallback?.(result);
        if (showError) Message.error(result?.info || '请求失败，请稍后重试');
        return null;
    } catch (error) {
        // 使用当前请求的闭包配置，不依赖 error.config / error.response 必须存在。
        const status = error?.response?.status;
        if (status === 401) {
            expireSession(token, promptOnAuthFailure);
            return null;
        }
        let message = '网络异常，请检查连接后重试';
        if (status === 403) message = '没有权限执行此操作';
        else if (status === 413) message = '图片或请求超过大小限制';
        else if (error?.code === 'ECONNABORTED' || error?.code === 'ETIMEDOUT') message = '请求超时，请稍后重试';
        else if (status) message = error?.response?.data?.info || '服务暂时不可用，请稍后重试';
        if (showError && error?.code !== 'ERR_CANCELED') Message.error(message);
        return null;
    } finally {
        await finishLoading();
    }
};

export default request;
