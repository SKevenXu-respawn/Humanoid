<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../api'

const loading = ref(false)
const stats = ref<any>(null)
const llbot = ref<any>(null)

async function load() {
  loading.value = true
  try {
    stats.value = await api.dashboard()
    llbot.value = await api.llbot()
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="toolbar">
      <el-button @click="load">刷新</el-button>
      <el-tag :type="llbot?.reachable ? 'success' : 'danger'">
        LLBot {{ llbot?.reachable ? (llbot?.online ? '在线' : '已连接但离线') : '不可达' }}
      </el-tag>
      <span v-if="llbot?.appName" style="color: #909399; font-size: 13px">
        {{ llbot.appName }} {{ llbot.appVersion }}
      </span>
    </div>

    <div v-if="stats" class="stat-grid">
      <div class="stat-card">
        <div class="label">用户白名单</div>
        <div class="value">{{ stats.users.enabled }}</div>
        <div class="sub">共 {{ stats.users.total }} 个</div>
      </div>
      <div class="stat-card">
        <div class="label">群白名单</div>
        <div class="value">{{ stats.groups.enabled }}</div>
        <div class="sub">共 {{ stats.groups.total }} 个</div>
      </div>
      <div class="stat-card">
        <div class="label">今日对话</div>
        <div class="value">{{ stats.conversations.today }}</div>
        <div class="sub">累计 {{ stats.conversations.total }}</div>
      </div>
      <div class="stat-card">
        <div class="label">上下文消息</div>
        <div class="value">{{ stats.contexts.messages }}</div>
        <div class="sub">{{ stats.contexts.scopes }} 个会话</div>
      </div>
      <div class="stat-card">
        <div class="label">任务</div>
        <div class="value">{{ stats.tasks.pending + stats.tasks.inProgress }}</div>
        <div class="sub">
          待开始 {{ stats.tasks.pending }} / 进行中 {{ stats.tasks.inProgress }} /
          暂停 {{ stats.tasks.paused }} / 完成 {{ stats.tasks.completed }}
        </div>
      </div>
      <div class="stat-card">
        <div class="label">待发送提醒</div>
        <div class="value">{{ stats.reminders.pending }}</div>
        <div class="sub">
          已发 {{ stats.reminders.sent }} / 失败 {{ stats.reminders.failed }} /
          取消 {{ stats.reminders.cancelled }}
        </div>
      </div>
      <div class="stat-card">
        <div class="label">图片缓存</div>
        <div class="value">{{ stats.images }}</div>
        <div class="sub">bot_image 记录</div>
      </div>
      <div class="stat-card">
        <div class="label">工具调用</div>
        <div class="value">{{ stats.toolCalls }}</div>
        <div class="sub">bot_tool_call 记录</div>
      </div>
    </div>
  </div>
</template>
