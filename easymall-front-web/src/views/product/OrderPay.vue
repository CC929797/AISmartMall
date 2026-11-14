<template>
  <Dialog :show="dialogConfig.show" :title="dialogConfig.title" :buttons="dialogConfig.buttons" width="400px"
    :showCancel="false" @close="dialogConfig.show = false">
    <div class="pay-success" v-if="payResult.orderStatus === 1 || payResult.orderStatus === 2">
      <div class="iconfont icon-success">支付成功</div>
      <el-button type="success" class="check-btn" @click="showOrder">查看订单</el-button>
    </div>
    <template v-else>
      <p v-if="payInfo.paymentStatus === 'UNAVAILABLE'" role="status">订单已创建，支付页面暂不可用。请继续支付原订单，无需重新下单。</p>
      <p v-if="payInfo.paymentStatus === 'NOT_PAYABLE'" role="status">订单当前不可支付，请查看最新订单状态。</p>
      <el-button v-if="payInfo.payInfo" @click="openPayment(payInfo)">打开支付页</el-button>
      <el-button v-if="payInfo.paymentStatus === 'UNAVAILABLE'" :loading="retrying" :disabled="retrying" @click="retryPay">继续支付</el-button>
      <el-button @click="showOrder">查看订单</el-button>
      <div class="pay-error" v-if="payResult.orderStatus === 0">
        <div class="iconfont icon-error">订单未支付</div>
      </div>
      <div class="pay-check">
        <el-button @click="checkPay" type="primary">我已支付</el-button>
      </div>
    </template>
  </Dialog>
</template>

<script setup>
import { ref, reactive, getCurrentInstance, nextTick } from 'vue'
const { proxy } = getCurrentInstance()

const props = defineProps({
  showType: {
    type: Number,
    default: 0,
  },
})

const dialogConfig = ref({
  show: false,
  title: '支付确认',
})

const payInfo = ref({})
const retrying = ref(false)
const openPayment = (data, reservedWindow) => {
  if (!data.payInfo) return
  const newWindow = reservedWindow || window.open('', '_blank')
  if (!newWindow) { proxy.Message.warning('支付页未打开，请允许弹出窗口后点击“打开支付页”。'); return }
  try {
    newWindow.opener = null
    newWindow.document.write(data.payInfo)
    // 支付渠道返回的页面自行提交自己的表单，不能提交商城主窗口里的表单。
    newWindow.document.close()
  } catch { proxy.Message.warning('支付页未能打开，请从原订单继续支付。') }
}
const show = async (data) => {
  if (data.requestStatus && data.requestStatus !== 'CREATED') return
  payResult.value = {}
  payInfo.value = data
  dialogConfig.value.show = true
  if (data.payInfo) openPayment(data)
}

// 仅重取原订单的支付信息，服务端复用原payOrderId，不会重新创建订单。
const retryPay = async () => {
  if (retrying.value) return
  retrying.value = true
  const token = localStorage.getItem('token')
  const reservedWindow = window.open('', '_blank')
  if (reservedWindow) reservedWindow.opener = null
  try {
    let orderId = payInfo.value.orderId
    if (!orderId) {
      const existing = await proxy.Request({ url: proxy.Api.getOrderInfo, params: { payOrderId: payInfo.value.payOrderId } })
      if (!existing) { reservedWindow?.close(); return }
      orderId = existing.data.orderId
    }
    const result = await proxy.Request({ url: proxy.Api.getPayInfo, params: { orderId } })
    if (!result || localStorage.getItem('token') !== token) { reservedWindow?.close(); return }
    payInfo.value = result.data
    if (result.data.payInfo) openPayment(result.data, reservedWindow)
    else reservedWindow?.close()
  } catch { reservedWindow?.close(); proxy.Message.warning('暂时无法打开支付页，请稍后继续支付原订单。') }
  finally { retrying.value = false }
}

const payResult = ref({})
const checkPay = async () => {
  let result = await proxy.Request({
    url: proxy.Api.getOrderInfo,
    params: {
      payOrderId: payInfo.value.payOrderId,
    },
  })
  if (!result) {
    return
  }
  payResult.value = result.data
}

const emit = defineEmits(['reload'])
const showOrder = () => {
  if (props.showType == 0) {
    proxy.Utils.jump('/my/orders')
  } else {
    emit("reload")
    dialogConfig.value.show = false
  }
}

defineExpose({
  show,
})
</script>

<style lang="scss" scoped>
.pay-check {
  text-align: center;
}

.pay-success {
  text-align: center;

  .icon-success {
    display: flex;
    align-items: center;
    justify-content: center;
    margin-bottom: 20px;

    &::before {
      color: green;
      margin-right: 5px;
      font-size: 30px;
    }
  }

  .check-btn {
    margin: 10px 0px;
    padding: 15px 30px;
  }
}

.pay-error {
  text-align: center;

  .icon-error {
    display: flex;
    align-items: center;
    justify-content: center;
    margin-bottom: 20px;

    &::before {
      color: red;
      margin-right: 5px;
      font-size: 30px;
    }
  }
}
</style>
