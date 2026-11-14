<template>
  <Drawer :show="dialogConfig.show" :title="dialogConfig.title" :buttons="dialogConfig.buttons" width="80%"
    :showCancel="false" @close="dialogConfig.show = false" background="#F7F7F7">
    <div class="buy-panel">
      <div class="product-panel" :inert="submitting">
        <Address v-model="selectedAddress" />
        <div class="order-panel">
          <div class="order-title">确认订单信息</div>
          <ProductSkuItem v-for="data in orderData" :data="data"></ProductSkuItem>
        </div>
      </div>
      <div class="pay-panel">
        <div class="pay-title">
          <div class="label">付款详情</div>
          <div class="product-count">共 <span class="value">{{ productCount }}</span> 件商品</div>
        </div>
        <div class="pay-item">
          <div class="label">商品总价</div>
          <Price :price="orderPrice" :size="14"></Price>
        </div>

        <div class="pay-method">
          <el-radio-group v-model="payMethod">
            <el-radio :label="'alipay_pc'">
              <div class="payment-info">
                <span class="name">支付宝</span>
              </div>
            </el-radio>
          </el-radio-group>
        </div>
        <div class="pay-btn">
          <div class="price-panel">
            <div>实付款</div>
            <Price :price="orderPrice" :size="20"></Price>
          </div>
          <el-button type="primary" size="large" :loading="submitting" :disabled="submitting" @click="postOrder()">提交订单</el-button>
        </div>
        <p v-if="submitHint" role="status">{{ submitHint }}</p>
        <el-button v-if="lastRequestId" :disabled="submitting" @click="checkRequest">查询提交结果</el-button>
      </div>
    </div>
  </Drawer>
  <OrderPay ref="orderPayRef"></OrderPay>
</template>

<script setup>
import OrderPay from '@/views/product/OrderPay.vue'
import ProductSkuItem from './ProductSkuItem.vue'
import Address from '@/views/address/Address.vue'
import { createCheckoutIntent, isCheckoutCreated } from '@/utils/CheckoutIntent.js'
import { useLoginStore } from '@/stores/loginStore.js'
import {
  ref,
  reactive,
  getCurrentInstance,
  nextTick,
  computed,
  onMounted,
} from 'vue'
const { proxy } = getCurrentInstance()
const loginStore = useLoginStore()
const intent = createCheckoutIntent()
const submitting = ref(false)
const submitHint = ref('')
const lastRequestId = ref('')
const emit = defineEmits(['submitted'])
const dialogConfig = ref({
  show: false,
  title: '下单',
})

//订单商品信息
const orderData = ref([])
//商品数量
const productCount = computed(() => {
  return orderData.value.reduce((total, item) => {
    return total + item.buyCount
  }, 0)
})
//订单价格
const orderPrice = computed(() => {
  return orderData.value.reduce((total, item) => {
    return total + item.price * item.buyCount
  }, 0)
})
const orderFrom = ref(0)
const showBuy = (_orderData, _orderFrom) => {
  if (submitting.value) return
  intent.begin(loginStore.userInfo.userId)
  submitHint.value = ''
  lastRequestId.value = ''
  dialogConfig.value.show = true
  orderData.value = _orderData.map(item => ({ ...item }))
  orderFrom.value = _orderFrom
}

defineExpose({
  showBuy,
})
//支付方式
const payMethod = ref('alipay_pc')
//收货地址
const selectedAddress = ref()

//提交订单
const orderPayRef = ref()
const postOrder = async () => {
  if (submitting.value) return
  if (!selectedAddress.value) {
    proxy.Message.warning('请选择收货地址')
    return
  }
  const orderList = orderData.value.map((item) => {
    return {
      productId: item.productId,
      propertyValueIds: item.propertyValueIds,
      buyCount: item.buyCount,
      remark: item.remark ? item.remark : '',
    }
  })
  submitting.value = true
  const userId = loginStore.userInfo.userId
  try {
    const content = {
      orderFrom: orderFrom.value, payMethod: payMethod.value,
      addressId: selectedAddress.value, orderList,
    }
    const requestId = await intent.get(userId, content)
    lastRequestId.value = requestId
    const result = await proxy.Request({
      url: proxy.Api.postOrder,
      dataType: 'json',
      params: { ...content, requestId },
    })
    if (loginStore.userInfo.userId !== userId) return
    if (!result) {
      submitHint.value = '提交结果未确认，请查询结果或使用原请求重试。'
      return
    }
    if (!isCheckoutCreated(result.data)) {
      submitHint.value = result.data.requestStatus === 'PROCESSING' ? '订单正在处理中，请稍后查询或重试。' : '上次提交未完成，可以重试。'
      return
    }
    intent.complete(userId, requestId)
    dialogConfig.value.show = false
    orderPayRef.value.show(result.data)
    emit('submitted')
  } catch (error) {
    submitHint.value = error.message || '暂时无法提交，请稍后重试。'
    proxy.Message.warning(submitHint.value)
  } finally { submitting.value = false }
}

// 只查询已提交的请求，不能把查询本身变成第二次购买。
const checkRequest = async () => {
  if (submitting.value || !lastRequestId.value) return
  submitting.value = true
  const userId = loginStore.userInfo.userId
  try {
    const result = await proxy.Request({ url: proxy.Api.getOrderRequest, params: { requestId: lastRequestId.value } })
    if (!result || loginStore.userInfo.userId !== userId) return
    if (result.data.requestStatus === 'CREATED') {
      intent.complete(userId, lastRequestId.value)
      dialogConfig.value.show = false
      orderPayRef.value.show({ ...result.data, paymentStatus: 'UNAVAILABLE' })
      emit('submitted')
    } else submitHint.value = result.data.requestStatus === 'PROCESSING' ? '订单仍在处理中，请稍后再查询。' : '上次提交未完成，可以使用原请求重试。'
  } finally { submitting.value = false }
}
</script>

<style lang="scss" scoped>
.buy-panel {
  display: flex;
  align-items: flex-start;

  .product-panel {
    flex: 1;
    width: 0;

    .order-panel {
      margin-top: 20px;
      background: #fff;
      padding: 15px;
      border-radius: 10px;

      .order-title {
        font-size: 16px;
        font-weight: bold;
      }
    }
  }

  .pay-panel {
    width: 300px;
    background: #fff;
    border-radius: 10px;
    margin-left: 20px;
    padding: 10px;
    position: sticky;
    top: 0px;

    .pay-title {
      display: flex;
      align-items: center;

      .label {
        font-weight: 600;
        font-size: 16px;
      }

      .product-count {
        margin-left: 10px;

        .value {
          padding: 2px 5px;
          font-weight: 600;
          color: var(--pink);
        }
      }
    }

    .pay-item {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-top: 10px;

      .label {
        font-size: 14px;
      }
    }

    .pay-method {
      background: #f7f7f7;
      padding: 10px;
      border-radius: 10px;
      margin-top: 20px;

      :deep(.el-radio-group) {
        display: flex;
        flex-direction: column;
        align-items: flex-start;
      }
    }

    .pay-btn {
      margin-top: 20px;
      display: flex;
      justify-content: space-between;
    }
  }
}
</style>
