<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'

const rows = ref<any[]>([])
const activeKey = ref('')
const content = ref('')
const loading = ref(false)
const saving = ref(false)

const current = computed(() => rows.value.find((item) => item.key === activeKey.value))

const SOURCE_LABEL: Record<string, string> = {
  DB: '数据库',
  DEFAULT: '默认文件'
}
const SOURCE_TYPE: Record<string, string> = {
  DB: 'success',
  DEFAULT: 'info'
}

async function load() {
  loading.value = true
  try {
    rows.value = await api.prompts()
    if (!activeKey.value && rows.value.length) {
      activeKey.value = rows.value[0].key
    }
    syncContent()
  } finally {
    loading.value = false
  }
}

function syncContent() {
  content.value = current.value?.content || ''
}

function onTabChange() {
  syncContent()
}

async function save() {
  if (!content.value.trim()) {
    ElMessage.warning('提示词不能为空')
    return
  }
  saving.value = true
  try {
    await api.savePrompt(activeKey.value, content.value)
    ElMessage.success('已保存，下一次调用生效')
    await load()
  } finally {
    saving.value = false
  }
}

async function reset() {
  await ElMessageBox.confirm(
    '恢复为代码中的默认提示词？数据库中的修改会被删除。',
    '恢复默认',
    { type: 'warning' }
  )
  await api.resetPrompt(activeKey.value)
  ElMessage.success('已恢复默认')
  await load()
}

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="toolbar">
      <el-button @click="load">刷新</el-button>
      <el-tag v-if="current" :type="SOURCE_TYPE[current.source] || 'info'">
        {{ SOURCE_LABEL[current.source] || current.source }}
      </el-tag>
      <span class="empty" style="padding: 0 8px" v-if="current?.updatedAt">
        更新时间：{{ current.updatedAt }}
      </span>
      <span class="empty" style="padding: 0 8px">
        保存后下一次调用生效；提示词会直接发给模型，请谨慎修改。
      </span>
    </div>

    <el-tabs v-model="activeKey" @tab-change="onTabChange">
      <el-tab-pane v-for="item in rows" :key="item.key" :label="item.label" :name="item.key" />
    </el-tabs>

    <el-input v-model="content" type="textarea" :rows="24" style="font-family: monospace" />

    <div class="toolbar" style="margin-top: 12px">
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      <el-button @click="reset">恢复默认</el-button>
    </div>
  </div>
</template>
