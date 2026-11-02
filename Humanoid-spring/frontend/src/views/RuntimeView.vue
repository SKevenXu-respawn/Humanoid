<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { api } from '../api'

const data = ref<any>(null)
const loading = ref(false)
const refreshedAt = ref('')
let timer: number | undefined

const SOURCE_LABEL: Record<string, string> = {
  DB: '数据库',
  ENV: '环境变量',
  NONE: '未配置'
}
const SOURCE_TYPE: Record<string, string> = {
  DB: 'success',
  ENV: 'warning',
  NONE: 'info'
}

const uptimeText = computed(() => {
  const seconds = data.value?.app?.uptimeSeconds || 0
  const days = Math.floor(seconds / 86400)
  const hours = Math.floor((seconds % 86400) / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const parts = []
  if (days) parts.push(`${days} 天`)
  if (hours) parts.push(`${hours} 小时`)
  parts.push(`${minutes} 分钟`)
  return parts.join(' ')
})

const heapPercent = computed(() => {
  const used = data.value?.app?.heapUsedMb || 0
  const max = data.value?.app?.heapMaxMb || 1
  return Math.min(100, Math.round((used / max) * 100))
})

async function load() {
  loading.value = true
  try {
    data.value = await api.runtime()
    refreshedAt.value = new Date().toLocaleTimeString()
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  load()
  timer = window.setInterval(load, 20000)
})

onUnmounted(() => {
  if (timer) {
    clearInterval(timer)
  }
})
</script>

<template>
  <div v-loading="loading">
    <div class="toolbar">
      <el-button @click="load">刷新</el-button>
      <span class="empty" style="padding: 0 8px">每 20 秒自动刷新，上次刷新：{{ refreshedAt }}</span>
      <span class="empty" style="padding: 0 8px" v-if="data?.app">
        工具 {{ data.app.toolCount }} 个（浏览器 {{ data.app.browserToolCount }} 个）
      </span>
    </div>

    <el-alert
      v-if="data?.warnings?.length"
      type="warning"
      :closable="false"
      show-icon
      style="margin-bottom: 12px"
    >
      <template #title>
        <span v-for="(warning, index) in data.warnings" :key="index">
          {{ warning }}<br v-if="index < data.warnings.length - 1" />
        </span>
      </template>
    </el-alert>

    <div class="stat-grid" v-if="data">
      <el-card shadow="never" class="stat-card">
        <div class="label">应用运行时长</div>
        <div class="value">{{ uptimeText }}</div>
        <div class="sub">启动于 {{ data.app.startTime }}</div>
      </el-card>
      <el-card shadow="never" class="stat-card">
        <div class="label">JVM 内存</div>
        <div class="value">{{ data.app.heapUsedMb }} / {{ data.app.heapMaxMb }} MB</div>
        <el-progress :percentage="heapPercent" :stroke-width="6" style="margin-top: 8px" />
        <div class="sub">线程 {{ data.app.threads }}</div>
      </el-card>
      <el-card shadow="never" class="stat-card">
        <div class="label">LLBot</div>
        <div class="value">
          <el-tag :type="data.llbot.online ? 'success' : 'danger'">
            {{ data.llbot.online ? '在线' : (data.llbot.reachable ? '离线' : '不可达') }}
          </el-tag>
        </div>
        <div class="sub">
          {{ data.llbot.nickname || '-' }}
          <span v-if="data.llbot.qq">（{{ data.llbot.qq }}）</span>
          <span v-if="data.llbot.appVersion"> · {{ data.llbot.appName }} {{ data.llbot.appVersion }}</span>
        </div>
      </el-card>
      <el-card shadow="never" class="stat-card">
        <div class="label">数据库</div>
        <div class="value">
          <el-tag :type="data.database.reachable ? 'success' : 'danger'">
            {{ data.database.reachable ? '正常' : '不可达' }}
          </el-tag>
        </div>
        <div class="sub">MySQL · humanoid</div>
      </el-card>
    </div>

    <el-row :gutter="12" style="margin-top: 12px" v-if="data">
      <el-col :span="12">
        <el-card shadow="never">
          <template #header><b>队列积压</b></template>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="待触发提醒">{{ data.queues.remindersPending }}</el-descriptions-item>
            <el-descriptions-item label="进行中任务">{{ data.queues.tasksInProgress }}</el-descriptions-item>
            <el-descriptions-item label="待发送视频">{{ data.queues.videoQueuePending }}</el-descriptions-item>
            <el-descriptions-item label="待处理好友申请">{{ data.queues.friendRequestsPending }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="never">
          <template #header><b>语音</b></template>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="发送开关">
              <el-tag :type="data.voice.enabled ? 'success' : 'info'">
                {{ data.voice.enabled ? '开启' : '关闭' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="TTS Key">
              <el-tag :type="data.voice.configured ? 'success' : 'danger'">
                {{ data.voice.configured ? '已配置' : '未配置' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="ASR">
              <el-tag :type="data.voice.asrConfigured ? 'success' : 'warning'">
                {{ data.voice.asrConfigured ? '可用' : '未启用' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="音色">{{ data.voice.speaker }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="12" style="margin-top: 12px" v-if="data">
      <el-col :span="12">
        <el-card shadow="never">
          <template #header><b>调度器</b></template>
          <el-table :data="data.schedulers" border>
            <el-table-column prop="label" label="任务" width="120" />
            <el-table-column prop="lastRunAt" label="上次运行" width="180" />
            <el-table-column prop="detail" label="结果" />
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="never">
          <template #header><b>密钥</b></template>
          <el-table :data="data.secrets" border>
            <el-table-column prop="label" label="配置项" min-width="160" />
            <el-table-column label="来源" width="100">
              <template #default="{ row }">
                <el-tag :type="SOURCE_TYPE[row.source] || 'info'">
                  {{ SOURCE_LABEL[row.source] || row.source }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="row.configured ? 'success' : (row.required ? 'danger' : 'info')">
                  {{ row.configured ? '已配置' : (row.required ? '缺失' : '可选') }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="masked" label="掩码" width="120">
              <template #default="{ row }">{{ row.masked || '-' }}</template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" style="margin-top: 12px" v-if="data">
      <template #header><b>数据表行数</b></template>
      <div style="display: flex; flex-wrap: wrap; gap: 8px">
        <el-tag v-for="(count, table) in data.database.counts" :key="table" type="info">
          {{ table }}: {{ count }}
        </el-tag>
      </div>
    </el-card>
  </div>
</template>
