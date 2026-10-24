<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, imageUrl } from '../api'

const scopes = ref<any[]>([])
const q = ref('')
const loading = ref(false)
const selected = ref<any>(null)

const summary = ref('')
const summaryExists = ref(false)
const messages = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)

function scopeParams() {
  return {
    userId: selected.value.userId,
    messageType: selected.value.messageType,
    groupId: selected.value.messageType === 'group' ? selected.value.groupId : undefined
  }
}

async function loadScopes() {
  loading.value = true
  try {
    scopes.value = await api.contexts(q.value || undefined)
  } finally {
    loading.value = false
  }
}

async function select(row: any) {
  selected.value = row
  page.value = 1
  await loadSummary()
  await loadMessages()
}

async function loadSummary() {
  const data = await api.getSummary(scopeParams())
  summaryExists.value = !!data
  summary.value = data?.summary || ''
}

async function saveSummary() {
  await api.saveSummary({ ...scopeParams(), summary: summary.value })
  ElMessage.success('总结已保存')
  await loadSummary()
  await loadScopes()
}

async function deleteSummary() {
  await ElMessageBox.confirm('删除该会话的滚动总结？', '提示', { type: 'warning' })
  await api.deleteSummary(scopeParams())
  ElMessage.success('已删除')
  await loadSummary()
  await loadScopes()
}

async function loadMessages() {
  const data = await api.contextMessages({ ...scopeParams(), page: page.value, size: size.value })
  messages.value = data.records
  total.value = data.total
}

async function clearContext() {
  await ElMessageBox.confirm(
    '清空该会话的 AI 上下文？对话审计记录不会删除。',
    '提示',
    { type: 'warning' }
  )
  const result = await api.clearContext(scopeParams())
  ElMessage.success(`已清空 ${result.deleted} 条`)
  await loadMessages()
  await loadScopes()
}

function scopeLabel(row: any) {
  return row.messageType === 'group' ? `群 ${row.groupId}` : '私聊'
}

onMounted(loadScopes)
</script>

<template>
  <el-row :gutter="12">
    <el-col :span="8">
      <el-card shadow="never">
        <div class="toolbar">
          <el-input
            v-model="q"
            placeholder="用户 / 群"
            clearable
            @keyup.enter="loadScopes"
          />
          <el-button @click="loadScopes">搜索</el-button>
        </div>
        <el-table
          v-loading="loading"
          :data="scopes"
          border
          highlight-current-row
          height="620"
          @current-change="(row: any) => row && select(row)"
        >
          <el-table-column label="会话" min-width="130">
            <template #default="{ row }">
              <div>{{ row.userId }}</div>
              <div style="color: #909399; font-size: 12px">{{ scopeLabel(row) }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="messageCount" label="消息" width="70" />
          <el-table-column label="总结" width="70">
            <template #default="{ row }">{{ row.summaryChars }}</template>
          </el-table-column>
          <el-table-column prop="lastMessageAt" label="最后消息" width="160" />
        </el-table>
      </el-card>
    </el-col>

    <el-col :span="16">
      <el-card shadow="never" v-if="selected">
        <template #header>
          <div style="display: flex; justify-content: space-between; align-items: center">
            <span>用户 {{ selected.userId }} · {{ scopeLabel(selected) }}</span>
            <el-button type="danger" size="small" @click="clearContext">清空上下文</el-button>
          </div>
        </template>

        <div style="margin-bottom: 12px">
          <div style="display: flex; justify-content: space-between; margin-bottom: 6px">
            <b>滚动总结</b>
            <div>
              <el-button size="small" type="primary" @click="saveSummary">保存</el-button>
              <el-button v-if="summaryExists" size="small" type="danger" plain @click="deleteSummary">
                删除
              </el-button>
            </div>
          </div>
          <el-input
            v-model="summary"
            type="textarea"
            :rows="5"
            placeholder="还没有总结"
          />
        </div>

        <el-divider />

        <div v-if="messages.length === 0" class="empty">没有上下文消息</div>
        <div v-for="message in messages" :key="message.id" class="msg-row">
          <div class="msg-meta">
            <el-tag size="small" :type="message.role === 'assistant' ? 'success' : 'info'">
              {{ message.role === 'assistant' ? '助手' : '用户' }}
            </el-tag>
            <span>{{ message.createdAt }}</span>
          </div>
          <div class="msg-content">{{ message.content || '[图片]' }}</div>
          <div v-if="message.imageIds.length" class="msg-images">
            <el-image
              v-for="imageId in message.imageIds"
              :key="imageId"
              :src="imageUrl(imageId)"
              :preview-src-list="message.imageIds.map(imageUrl)"
              fit="cover"
              preview-teleported
            />
          </div>
        </div>

        <div class="pager">
          <el-pagination
            v-model:current-page="page"
            :total="total"
            :page-size="size"
            layout="total, prev, pager, next"
            @current-change="loadMessages"
          />
        </div>
      </el-card>
      <el-card v-else shadow="never">
        <div class="empty">请选择左侧的会话</div>
      </el-card>
    </el-col>
  </el-row>
</template>
