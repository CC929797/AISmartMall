<template>
  <Dialog :show="dialogConfig.show" :title="dialogConfig.title" width="850px" :showCancel="false"
    @close="dialogConfig.show = false">
    <div class="tool-actions">
      <el-button @click="run('/tool/statistics', {}, '统计数据已同步')">同步统计数据</el-button>
      <el-select v-model="source" aria-label="同步对象类型" style="width: 140px">
        <el-option label="商品" value="product" />
        <el-option label="FAQ" value="faq" />
      </el-select>
      <el-input v-model.trim="objectId" placeholder="输入商品 ID 或 FAQ ID" style="width: 210px" />
      <el-button type="primary" :disabled="!objectId" @click="resync">重同步</el-button>
    </div>

    <div class="tool-actions">
      <el-input v-model.trim="taskKey" clearable placeholder="按业务编号筛选" style="width: 210px" />
      <el-select v-model="status" clearable placeholder="任务状态" style="width: 150px">
        <el-option v-for="name in statuses" :key="name" :label="statusLabels[name]" :value="name" />
      </el-select>
      <el-button @click="loadTasks">查询任务</el-button>
    </div>

    <el-table :data="tasks" row-key="taskId" empty-text="暂无符合条件的任务" max-height="360">
      <el-table-column label="任务类型" min-width="130">
        <template #default="{ row }">{{ taskLabels[row.taskType] || row.taskType }}</template>
      </el-table-column>
      <el-table-column prop="bizKey" label="业务编号" min-width="135" show-overflow-tooltip />
      <el-table-column label="状态" width="95">
        <template #default="{ row }">{{ statusLabels[row.status] || row.status }}</template>
      </el-table-column>
      <el-table-column label="执行次数" width="85">
        <template #default="{ row }">{{ row.attempts }}/{{ row.maxAttempts }}</template>
      </el-table-column>
      <el-table-column prop="lastError" label="错误码" min-width="135" show-overflow-tooltip />
      <el-table-column label="操作" width="80">
        <template #default="{ row }">
          <el-button v-if="row.status === 'DEAD'" link type="primary" @click="retry(row)">重试</el-button>
        </template>
      </el-table-column>
    </el-table>

    <details class="migration-tools">
      <summary>迁移队列与同步所有商品</summary>
      <div class="tool-actions">
        <el-button @click="migrate">迁移一批旧队列</el-button>
        <el-input v-model.trim="cursor" placeholder="留空从头开始" style="width: 190px" />
        <el-button @click="backfill">{{ source === 'product' ? '同步所有商品' : '补同步一批 FAQ' }}</el-button>
      </div>
      <p>迁移旧队列前请停止旧版实例。每次点击提交一批，游标自动续接，按提示继续点击。</p>
    </details>
  </Dialog>
</template>

<script setup>
import { ref, getCurrentInstance, watch } from "vue"
const { proxy } = getCurrentInstance()
const dialogConfig = ref({ show: false, title: "小工具" })
const source = ref("product")
const objectId = ref("")
const taskKey = ref("")
const status = ref("")
const cursor = ref("")
const tasks = ref([])
const statusLabels = { READY: "待执行", RUNNING: "执行中", RETRY: "等待重试", SUCCESS: "已成功", DEAD: "已失败" }
const statuses = Object.keys(statusLabels)
const taskLabels = {
  PRODUCT_SEARCH: "商品搜索同步",
  PRODUCT_VECTOR: "商品向量同步",
  FAQ_VECTOR: "FAQ 向量同步",
  ORDER_PAID: "付款后履约",
  ORDER_LOGISTICS_STEP: "模拟物流",
  ORDER_AUTO_CONFIRM: "自动确认收货",
}

// 商品游标和 FAQ 游标来自不同数据表，切换类型后从头开始补同步。
watch(source, () => { cursor.value = "" })

// 业务编号可以是商品 ID、FAQ ID 或订单号，空筛选条件交给后端按默认范围查询。
const loadTasks = async () => {
  const params = { limit: 25 }
  if (taskKey.value) params.bizKey = taskKey.value
  if (status.value) params.status = status.value
  const result = await proxy.Request({ url: "/tool/syncTasks", params })
  if (result) tasks.value = result.data
}

const run = async (url, params, success) => {
  const result = await proxy.Request({ url, params })
  if (result) proxy.Message.success(success)
  return result
}

// 按对象类型选择入口。提交成功后用同一个 ID 刷新任务列表，实际同步由 Worker 执行。
const resync = async () => {
  const product = source.value === "product"
  const result = await run(product ? "/tool/productData" : "/tool/ragData",
    product ? { productId: objectId.value } : { questionId: objectId.value }, "同步任务已提交")
  if (result) {
    taskKey.value = objectId.value
    status.value = ""
    await loadTasks()
  }
}

const retry = async (row) => {
  if (await run("/tool/retrySyncTask", { taskId: row.taskId }, "任务已重新排队")) await loadTasks()
}

// 一次只迁移固定批次，返回三种旧队列本批处理数，三项都为 0 时迁移结束。
const migrate = async () => {
  const result = await proxy.Request({ url: "/tool/migrateLegacyTasks", params: { limit: 25 } })
  if (result) {
    const data = result.data
    proxy.Message.success(`本批迁移知识库 ${data.rag} 条、物流 ${data.logistics} 条、收货 ${data.confirm} 条`)
    await loadTasks()
  }
}

// 后端返回本批最后一个对象的编号，下次发请求时继续从该编号之后处理。
const backfill = async () => {
  const product = source.value === "product"
  const result = await proxy.Request({ url: "/tool/syncBackfill", params: { source: source.value, cursor: cursor.value, limit: 25 } })
  if (result) {
    cursor.value = String(result.data.nextCursor)
    if (product) {
      proxy.Message.success(result.data.count ? "还有商品未同步" : "所有商品同步任务已提交")
    } else {
      proxy.Message.success(result.data.count ? `已提交 ${result.data.count} 个对象` : "当前游标后已无待补同步的对象")
    }
    await loadTasks()
  }
}

const show = () => {
  dialogConfig.value.show = true
  loadTasks()
}
defineExpose({ show })
</script>

<style scoped>
.tool-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; margin: 0 0 12px; }
.migration-tools { margin-top: 16px; }
.migration-tools summary { cursor: pointer; margin-bottom: 12px; color: var(--el-text-color-primary); }
.migration-tools p { font-size: 13px; line-height: 1.5; color: var(--el-text-color-secondary); }
</style>
