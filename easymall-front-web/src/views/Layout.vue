<template>
  <IndexHeader></IndexHeader>
  <div class="content-body" :style="{ width: `${proxy.bodyWidth}px` }">
    <div v-if="haveInit && !showRouterView" class="login-required">
      <p>登录后即可查看此页面</p>
      <el-button type="primary" @click="loginStore.showLogin = true">登录 / 注册</el-button>
    </div>
    <router-view v-if="haveInit&&showRouterView"></router-view>
  </div>
  <Footer v-if="route.meta.showFooter == null || route.meta.showFooter"></Footer>
</template>

<script setup>
import Footer from '@/views/footer/Footer.vue'
import IndexHeader from '@/views/header/IndexHeader.vue'
import {
  ref,
  getCurrentInstance,
  onMounted,
  computed,
  watch,
} from 'vue'
import { useRouter, useRoute } from 'vue-router'
const { proxy } = getCurrentInstance()
const router = useRouter()
const route = useRoute()

import { useLoginStore } from '@/stores/loginStore.js'
const loginStore = useLoginStore()

const needLogin = computed(() => !!route.meta.checkLogin && !loginStore.userInfo?.userId)
const showRouterView = computed(() => !needLogin.value)

const haveInit = ref(false)
const autoLogin = async () => {
  const token = localStorage.getItem('token')
  try {
    if (!token) {
      loginStore.clearSession()
      return
    }
    const result = await proxy.Request({
      url: proxy.Api.autoLogin,
      showLoading: false,
      promptOnAuthFailure: false,
    })
    // 自动登录等待期间可能已手动登录或退出，不得覆盖新状态。
    if (localStorage.getItem('token') !== token || !result) return
    if (result.data == null) {
      loginStore.clearSession({ expectedToken: token })
      return
    }
    loginStore.saveSession(result.data)
  } catch {
    // 网络故障不等于凭据失效；保留 Token 供稍后刷新重试。
    proxy.Message.error('登录状态检查失败，请稍后重试')
  } finally {
    haveInit.value = true
  }
}

watch([haveInit, needLogin], ([initialized, required]) => {
  if (initialized && required) loginStore.showLogin = true
})

onMounted(() => {
  autoLogin()
})
</script>

<style lang="scss" scoped>
.content-body {
  margin: 0px auto;
  margin-top: 70px;
  min-height: calc(100vh - 290px);
}
.login-required {
  padding: 80px 0;
  text-align: center;
}
</style>
