<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'

const rows = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const status = ref('PENDING')
const userId = ref('')
const loading = ref(false)

const STATUS_LABEL: Record<string, string> = {
  PENDING: '待发送',
  SENT: '已发送',
  SKIPPED: '已跳过',
  FAILED: '失败'
}
const STATUS_TYPE: Record<string, string> = {
  PENDING: 'warning',
  SENT: 'success',
  SKIPPED: 'info',
  FAILED: 'danger'
}
const SOURCE_LABEL: Record<string, string> = {
  PROACTIVE: '主动浏览',
  USER: '用户请求',
  ADMIN: '后台'
}

async function load() {
  loading.value = true
  try {
    const data = await api.videoQueue({
      page: page.value,
      size: size.value,
      status: status.value || undefined,
      userId: userId.value || undefined
    })
    rows.value = data.records
    total.value = data.total
  } finally {
    loading.value = false
  }
}

async function send(row: any) {
  await ElMessageBox.confirm(
    `立即发送视频《${row.title || row.url}》给 ${row.userId}？`,
    '发送视频',
    { type: 'warning' }
  )
  await api.sendVideoQueue(row.id)
  ElMessage.success('已发送')
  load()
}

async function skip(row: any) {
  await ElMessageBox.confirm('跳过这条视频？', '提示', { type: 'warning' })
  await api.skipVideoQueue(row.id)
  ElMessage.success('已跳过')
  load()
}

async function requeue(row: any) {
  await api.requeueVideoQueue(row.id)
  ElMessage.success('已重新入队')
  load()
}

async function remove(row: any) {
  await ElMessageBox.confirm('删除这条队列记录？', '提示', { type: 'warning' })
  await api.deleteVideoQueue(row.id)
  ElMessage.success('已删除')
  load()
}

onMounted(load)
</script>

<template>
  <div>
    <div class="toolbar">
      <el-select v-model="status" placeholder="状态" clearable style="width: 130px">
        <el-option v-for="(label, key) in STATUS_LABEL" :key="key" :label="label" :value="key" />
      </el-select>
      <el-input v-model="userId" placeholder="用户 QQ" clearable style="width: 150px" />
      <el-button type="primary" @click="page = 1; load()">搜索</el-button>
      <el-button @click="load">刷新</el-button>
      <span class="empty" style="padding: 0 8px">
        严格模式：先发完最早用户的全部视频，再轮到下一个用户；每轮最多 5 条（可配置）。
      </span>
    </div>

    <el-table v-loading="loading" :data="rows" border>
      <el-table-column prop="id" label="队列 ID" width="90" />
      <el-table-column prop="userId" label="用户" width="140" />
      <el-table-column label="视频" min-width="260">
        <template #default="{ row }">
          <a :href="row.url" target="_blank" rel="noreferrer" style="color: #409eff">
            {{ row.title || row.url }}
          </a>
          <div v-if="row.comment" style="color: #909399; font-size: 12px">
            {{ row.comment }}
          </div>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="STATUS_TYPE[row.status] || 'info'">
            {{ STATUS_LABEL[row.status] || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="来源" width="100">
        <template #default="{ row }">
          {{ SOURCE_LABEL[row.source] || row.source }}
        </template>
      </el-table-column>
      <el-table-column prop="attempts" label="尝试" width="70" />
      <el-table-column prop="createdAt" label="入队时间" width="180" />
      <el-table-column prop="sentAt" label="发送时间" width="180" />
      <el-table-column label="操作" width="220">
        <template #default="{ row }">
          <el-button link type="success" @click="send(row)">发送</el-button>
          <el-button v-if="row.status !== 'SENT'" link type="warning" @click="skip(row)">
            跳过
          </el-button>
          <el-button
            v-if="row.status !== 'PENDING'"
            link
            type="primary"
            @click="requeue(row)"
          >
            重新入队
          </el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination
        v-model:current-page="page"
        :total="total"
        :page-size="size"
        layout="total, prev, pager, next"
        @current-change="load"
      />
    </div>
  </div>
</template>
