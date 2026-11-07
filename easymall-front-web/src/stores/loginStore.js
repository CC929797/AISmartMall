import { defineStore } from 'pinia'
import { ref } from 'vue'
import { closeWebSocket } from '@/utils/worker/websocket.manager.js'

export const useLoginStore = defineStore("loginState", () => {
    const showLogin = ref(false);
    const userInfo = ref({});
    const saveUserInfo = (value) => {
        userInfo.value = value;
    }
    const saveSession = (value) => {
        if (!value?.userId || !value?.token) return false;
        if (localStorage.getItem('token') !== value.token) closeWebSocket();
        // 先保存凭据，再显示依赖登录的页面，避免组件用旧 Token 建立连接。
        localStorage.setItem('token', value.token);
        saveUserInfo(value);
        return true;
    }
    const clearSession = ({ expectedToken, showLogin: prompt } = {}) => {
        // 旧请求迟到的 901 不能注销刚完成的新登录。
        if (expectedToken !== undefined && (localStorage.getItem('token') || '') !== expectedToken) return false;
        localStorage.removeItem('token');
        closeWebSocket();
        saveUserInfo({});
        if (prompt !== undefined) showLogin.value = prompt;
        return true;
    }
    return { showLogin, userInfo, saveUserInfo, saveSession, clearSession }
})
