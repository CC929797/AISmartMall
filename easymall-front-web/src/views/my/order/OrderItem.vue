<template>
  <div class="order-item">
    <div class="order-title">
      <div class="order-time">{{ proxy.Utils.format2Date(data.orderTime) }}</div>
      <div class="order-id">订单号: {{ data.orderId }}</div>
      <div class="order-status">{{ data.orderStatusName }}</div>
    </div>
    <div class="sub-item-list">
      <div class="order-sub-item" v-for="(sub, index) in data.orderItemList">
        <Cover :source="sub.cover" :width="80"
          @click="proxy.Utils.jump(`/detail/${sub.productId}`, showType != 'agent')">
        </Cover>
        <div class="product-name-panel">
          <div class="product-name" @click="proxy.Utils.jump(`/detail/${sub.productId}`, showType != 'agent')">{{
            sub.productName }}</div>
          <div class="property">{{ sub.propertyInfo }}</div>
          <div class="sub-order-info">
            <div class="btn-refund">
              <el-button v-if="[1, 2, 7].includes(Number(data.orderStatus)) && sub.orderItemStatus == 1
                && !isRefundPending(sub) && refundStatus(sub) !== 'SUCCESS' && showType !== 'agent'"
                :disabled="refundBusy[sub.orderItemId]" @click="refund(sub)" size="small">退款</el-button>
              <el-tag v-if="refundStatus(sub)" :type="refundStatus(sub) === 'SUCCESS' ? 'success' : 'warning'">
                {{ refundLabels[refundStatus(sub)] }}
              </el-tag>
              <el-button v-if="isRefundPending(sub) && showType !== 'agent'" size="small"
                :disabled="refundBusy[sub.orderItemId]" @click="checkRefund(sub.orderItemId)">查询退款结果</el-button>
            </div>
          </div>
        </div>
        <div class="amount-info">
          <Price :price="sub.itemAmount"></Price>
          <div class="buy-count">x{{ sub.buyCount }}</div>
        </div>
        <div class="total-amount">
          <template v-if="index == 0">
            <div class="total-amount-tips">实付款</div>
            <Price :price="data.amount"></Price>
          </template>
        </div>
        <div class="order-op-panel" v-if="showType !== 'agent'">
          <template v-if="index == 0">
            <el-button type="primary" v-if="data.orderStatus == 0" class="btn" @click="payOrder">付款</el-button>
            <el-button link v-if="data.orderStatus == 0" class="btn-link" @click="cancelOrder">取消订单</el-button>
            <!--确认收货-->
            <el-button type="primary" class="btn" v-if="data.orderStatus == 2 ||
              (data.orderStatus == 7 && [1, 2].includes(Number(data.logisticsStatus)))"
              :disabled="data.orderItemList?.some(isRefundPending)" @click="confirmOrder">确认收货</el-button>
            <!--评价-->
            <el-button type="primary" class="btn" v-if="data.orderStatus == 3 && data.commentStatus == 0"
              @click="comment">评价</el-button>
            <el-button class="btn-link" link v-if="data.orderStatus == 3 && data.commentStatus == 1"
              @click="commentRe">追评</el-button>
            <!--查看物流-->
            <el-button class="btn-link" link
              v-if="[2, 3].includes(Number(data.orderStatus)) ||
                (data.orderStatus == 7 && [1, 2].includes(Number(data.logisticsStatus)))"
              @click="showLogistics">查看物流</el-button>
            <!--取消，关闭，完成-->
            <el-button class="btn-link" link
              v-if="data.orderStatus == 4 || data.orderStatus == 5 || data.orderStatus == 3"
              @click="deleteOrder">删除订单</el-button>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, getCurrentInstance, nextTick, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
const { proxy } = getCurrentInstance()
const router = useRouter()
const route = useRoute()

const props = defineProps({
  data: {
    type: Object,
    default: {},
  },
  showType: {
    type: String,
    default: 'normal',
  },
})


const emit = defineEmits(['reload', 'logistics', 'comment', 'commentRe', "payOrder"])
const cancelOrder = () => {
  proxy.Confirm({
    message: '确定要取消吗?',
    okfun: async () => {
      let result = await proxy.Request({
        url: proxy.Api.cancelOrder,
        params: {
          orderId: props.data.orderId,
        },
      })
      if (!result) {
        return
      }
      // 取消接口可能只登记了关单申请，页面以返回的业务状态为准。
      const status = result.data?.orderStatus
      if (status === 8 || status === 9) {
        proxy.Message.warning('正在确认关单结果，请稍后查看订单状态。')
      } else if (status === 4 || status === 5 || status == null) {
        proxy.Message.success('取消成功')
      } else {
        proxy.Message.warning('订单状态已变化，请查看最新订单状态。')
      }
      emit('reload')
    },
  })
}

const deleteOrder = () => {
  proxy.Confirm({
    message: '确定要删除订单吗?',
    okfun: async () => {
      let result = await proxy.Request({
        url: proxy.Api.deleteOrder,
        params: {
          orderId: props.data.orderId,
        },
      })
      if (!result) {
        return
      }
      proxy.Message.success('删除成功')
      emit('reload')
    },
  })
}

//确认收货
const confirmOrder = () => {
  proxy.Confirm({
    message: '确定要确认收货吗？确认后订单完结',
    okfun: async () => {
      let result = await proxy.Request({
        url: proxy.Api.confirmOrder,
        params: {
          orderId: props.data.orderId,
        },
      })
      if (!result) {
        return
      }
      proxy.Message.success('确认成功')
      emit('reload')
    },
  })
}

const refundLabels = {
  PROCESSING: '退款处理中',
  UNKNOWN: '退款结果待确认',
  SUCCESS: '退款成功',
  FAILED: '退款失败',
}
const refundResults = reactive({})
const refundBusy = reactive({})
const refundIds = {}
const refundStatus = (item) => {
  if (item.orderItemStatus == 0) return 'SUCCESS'
  return refundResults[item.orderItemId] ?? item.refundStatus ?? (item.orderItemStatus == 2 ? 'PROCESSING' : null)
}
const isRefundPending = (item) => ['PROCESSING', 'UNKNOWN'].includes(refundStatus(item))
const refundKey = (itemId) => `easymall.refund.${itemId}`
const getRefundRequestId = (itemId) => {
  try {
    const saved = sessionStorage.getItem(refundKey(itemId))
    if (saved) return saved
  } catch { /* 存储不可用时沿用当前页面的编号。 */ }
  if (!refundIds[itemId]) {
    if (!globalThis.crypto?.randomUUID) return null
    refundIds[itemId] = globalThis.crypto.randomUUID()
    try { sessionStorage.setItem(refundKey(itemId), refundIds[itemId]) } catch { /* 继续使用内存编号。 */ }
  }
  return refundIds[itemId]
}
const showRefundResult = (itemId, status) => {
  refundResults[itemId] = status
  if (status === 'SUCCESS' || status === 'FAILED') {
    delete refundIds[itemId]
    try { sessionStorage.removeItem(refundKey(itemId)) } catch { /* 内存编号已清除。 */ }
  }
}

const refund = (item) => {
  const orderItemId = item.orderItemId
  if (refundBusy[orderItemId] || isRefundPending(item)) return
  proxy.Confirm({
    message: '确认退回这条商品明细的全部金额？',
    okfun: async () => {
      if (refundBusy[orderItemId] || isRefundPending(item) || refundStatus(item) === 'SUCCESS') return
      refundBusy[orderItemId] = true
      try {
        const requestId = getRefundRequestId(orderItemId)
        if (!requestId) {
          proxy.Message.warning('请使用 HTTPS 或 localhost 提交退款。')
          return
        }
        const result = await proxy.Request({
          url: proxy.Api.refundOrder,
          params: { orderItemId, requestId },
        })
        const status = result?.data?.status
        showRefundResult(orderItemId, status ?? 'UNKNOWN')
        if (status === 'SUCCESS' || status === 'FAILED') emit('reload')
      } finally {
        refundBusy[orderItemId] = false
      }
    },
  })
}

const checkRefund = async (orderItemId) => {
  if (refundBusy[orderItemId]) return
  refundBusy[orderItemId] = true
  try {
    const result = await proxy.Request({ url: proxy.Api.getRefundStatus, params: { orderItemId } })
    if (!result) return
    if (result.data?.status) {
      showRefundResult(orderItemId, result.data.status)
      if (['SUCCESS', 'FAILED'].includes(result.data.status)) emit('reload')
    } else {
      delete refundResults[orderItemId]
      proxy.Message.warning('暂未查到退款记录，可以用原请求再次提交。')
    }
  } finally {
    refundBusy[orderItemId] = false
  }
}

//查看物流
const showLogistics = () => {
  emit('logistics', props.data.orderId)
}
//评价
const comment = () => {
  emit('comment', props.data.orderId)
}

const commentRe = () => {
  emit('commentRe', props.data.orderId)
}

//付款
const payOrder = () => {
  emit('payOrder', props.data.orderId)
}

</script>

<style lang="scss" scoped>
.order-item {
  margin: 5px 0px;

  .order-title {
    background: #ebebeb;
    padding: 10px;
    display: flex;
    border-radius: 5px;
    color: var(--text);

    .order-id {
      margin-left: 10px;
      flex: 1;
    }

    .order-status {
      color: var(--red);
    }
  }

  .sub-item-list {
    padding: 5px 0px;

    .order-sub-item {
      display: flex;
      margin: 10px 0px;
      align-items: flex-start;

      .product-name-panel {
        flex: 1;
        width: 0;
        margin: 0px 10px;
        color: var(--text);

        .product-name {
          cursor: pointer;
          color: var(--pink);

          &:hover {
            color: var(--red);
          }
        }

        .property {
          margin-top: 10px;
          font-size: 12px;
          color: var(--text2);
        }

        .sub-order-info {
          display: flex;
          align-items: center;
          margin-top: 10px;
        }
      }

      .amount-info {
        width: 100px;
        display: flex;
        flex-direction: column;
        align-items: flex-end;

        .buy-count {
          font-size: 12px;
          color: var(--text2);
        }
      }

      .total-amount {
        width: 200px;
        display: flex;
        align-items: center;
        justify-content: flex-end;
        font-size: 12px;

        .total-amount-tips {
          margin-right: 3px;
        }
      }

      .order-op-panel {
        margin-left: 10px;
        width: 110px;
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;

        .btn {
          width: 90px;
          margin-bottom: 5px;
        }

        .btn-link {
          margin-bottom: 5px;
          margin-left: 0px;
        }
      }
    }
  }
}
</style>
