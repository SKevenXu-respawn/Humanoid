<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'

const tab = ref('memories')

const rows = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const status = ref('')
const memoryType = ref('')
const userId = ref('')
const q = ref('')
const loading = ref(false)

const dialog = ref(false)
const editing = ref<any>(null)
const form = reactive({ content: '', importance: 3, status: 'ACTIVE' })

const summaries = ref<any[]>([])
const summaryTotal = ref(0)
const summaryPage = ref(1)
const summarySize = ref(20)
const summaryScopeType = ref('')
const summaryScopeId = ref('')
const summaryOwner = ref('')
const summaryLoading = ref(false)

const summaryDialog = ref(false)
const editingSummary = ref<any>(null)
const summaryText = ref('')

const TYPE_LABEL: Record<string, string> = {
  PRIVATE: '私有',
  GROUP: '群公共',
  RELATIONSHIP: '关系'
}
const TYPE_TAG: Record<string, string> = {
  PRIVATE: 'warning',
  GROUP: 'success',
  RELATIONSHIP: 'primary'
}
const STATUS_LABEL: Record<string, string> = {
  ACTIVE: '生效',
  SUMMARIZED: '已总结',
  DELETED: '已删除'
}
const STATUS_TAG: Record<string, string> = {
  ACTIVE: 'success',
  SUMMARIZED: 'info',
  DELETED: 'danger'
}
const SCOPE_LABEL: Record<string, string> = {
  USER: '用户',
  GROUP: '群',
  GLOBAL: '全局'
}

async function load() {
  loading.value = true
  try {
    const data = await api.memories({
      page: page.value,
      size: size.value,
      status: status.value || undefined,
      memoryType: memoryType.value || undefined,
      userId: userId.value || undefined,
      q: q.value || undefined
    })
    rows.value = data.records
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function openEdit(row: any) {
  editing.value = row
  form.content = row.content || ''
  form.importance = row.importance || 3
  form.status = row.status || 'ACTIVE'
  dialog.value = true
}

async function save() {
  if (!form.content.trim()) {
    ElMessage.warning('内容不能为空')
    return
  }
  await api.updateMemory(editing.value.id, {
    content: form.content,
    importance: form.importance,
    status: form.status
  })
  ElMessage.success('已保存')
  dialog.value = false
  load()
}

async function remove(row: any) {
  await ElMessageBox.confirm('删除这条长期记忆？', '提示', { type: 'warning' })
  await api.deleteMemory(row.id)
  ElMessage.success('已删除')
  load()
}

async function loadSummaries() {
  summaryLoading.value = true
  try {
    const data = await api.memorySummaries({
      page: summaryPage.value,
      size: summarySize.value,
      scopeType: summaryScopeType.value || undefined,
      scopeId: summaryScopeId.value || undefined,
      ownerUserId: summaryOwner.value || undefined
    })
    summaries.value = data.records
    summaryTotal.value = data.total
  } finally {
    summaryLoading.value = false
  }
}

function openSummaryEdit(row: any) {
  editingSummary.value = row
  summaryText.value = row.summary || ''
  summaryDialog.value = true
}

async function saveSummary() {
  await api.updateMemorySummary(editingSummary.value.id, summaryText.value)
  ElMessage.success('已保存')
  summaryDialog.value = false
  loadSummaries()
}

async function removeSummary(row: any) {
  await ElMessageBox.confirm('删除这条记忆总结？', '提示', { type: 'warning' })
  await api.deleteMemorySummary(row.id)
  ElMessage.success('已删除')
  loadSummaries()
}

async function runSummary() {
  await ElMessageBox.confirm(
    '立即压缩旧的长期记忆？会调用 AI，可能需要一会儿。',
    '立即总结',
    { type: 'warning' }
  )
  const result = await api.runMemorySummary()
  ElMessage.success(`已完成，处理作用域 ${result.summarized} 个`)
  loadSummaries()
  load()
}

function onTabChange(name: string) {
  if (name === 'summaries') {
    loadSummaries()
  } else {
    load()
  }
}

onMounted(load)
</script>

<template>
  <el-tabs v-model="tab" @tab-change="onTabChange">
    <el-tab-pane label="长期记忆" name="memories">
      <div class="toolbar">
        <el-select v-model="status" placeholder="状态" clearable style="width: 120px">
          <el-option v-for="(label, key) in STATUS_LABEL" :key="key" :label="label" :value="key" />
        </el-select>
        <el-select v-model="memoryType" placeholder="类型" clearable style="width: 120px">
          <el-option v-for="(label, key) in TYPE_LABEL" :key="key" :label="label" :value="key" />
        </el-select>
        <el-input v-model="userId" placeholder="归属用户 QQ" clearable style="width: 150px" />
        <el-input v-model="q" placeholder="内容关键词" clearable style="width: 180px" />
        <el-button type="primary" @click="page = 1; load()">搜索</el-button>
        <el-button @click="load">刷新</el-button>
      </div>

      <el-table v-loading="loading" :data="rows" border>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="类型" width="100">
          <template #default="{ row }">
            <el-tag :type="TYPE_TAG[row.memoryType] || 'info'">
              {{ TYPE_LABEL[row.memoryType] || row.memoryType }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="作用域" width="130">
          <template #default="{ row }">
            {{ SCOPE_LABEL[row.scopeType] || row.scopeType }} {{ row.scopeId }}
          </template>
        </el-table-column>
        <el-table-column prop="ownerUserId" label="归属用户" width="120" />
        <el-table-column prop="content" label="内容" min-width="280" show-overflow-tooltip />
        <el-table-column prop="importance" label="重要度" width="80" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="STATUS_TAG[row.status] || 'info'">
              {{ STATUS_LABEL[row.status] || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="updatedAt" label="更新时间" width="180" />
        <el-table-column label="操作" width="130">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
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
    </el-tab-pane>

    <el-tab-pane label="记忆总结" name="summaries">
      <div class="toolbar">
        <el-select v-model="summaryScopeType" placeholder="作用域" clearable style="width: 120px">
          <el-option label="用户" value="USER" />
          <el-option label="群" value="GROUP" />
        </el-select>
        <el-input v-model="summaryScopeId" placeholder="作用域 ID" clearable style="width: 150px" />
        <el-input v-model="summaryOwner" placeholder="归属用户 QQ" clearable style="width: 150px" />
        <el-button type="primary" @click="summaryPage = 1; loadSummaries()">搜索</el-button>
        <el-button @click="loadSummaries">刷新</el-button>
        <el-button type="warning" @click="runSummary">立即总结</el-button>
      </div>

      <el-table v-loading="summaryLoading" :data="summaries" border>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="作用域" width="140">
          <template #default="{ row }">
            {{ SCOPE_LABEL[row.scopeType] || row.scopeType }} {{ row.scopeId }}
          </template>
        </el-table-column>
        <el-table-column prop="ownerUserId" label="归属用户" width="120" />
        <el-table-column prop="summary" label="总结" min-width="320" show-overflow-tooltip />
        <el-table-column prop="memoryCount" label="记忆数" width="90" />
        <el-table-column prop="updatedAt" label="更新时间" width="180" />
        <el-table-column label="操作" width="130">
          <template #default="{ row }">
            <el-button link type="primary" @click="openSummaryEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="removeSummary(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="summaryPage"
          :total="summaryTotal"
          :page-size="summarySize"
          layout="total, prev, pager, next"
          @current-change="loadSummaries"
        />
      </div>
    </el-tab-pane>
  </el-tabs>

  <el-dialog v-model="dialog" title="编辑长期记忆" width="640px">
    <el-form label-width="90px">
      <el-form-item label="内容">
        <el-input v-model="form.content" type="textarea" :rows="5" />
      </el-form-item>
      <el-form-item label="重要度">
        <el-input-number v-model="form.importance" :min="1" :max="5" />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="form.status" style="width: 160px">
          <el-option v-for="(label, key) in STATUS_LABEL" :key="key" :label="label" :value="key" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialog = false">取消</el-button>
      <el-button type="primary" @click="save">保存</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="summaryDialog" title="编辑记忆总结" width="640px">
    <el-input v-model="summaryText" type="textarea" :rows="8" />
    <template #footer>
      <el-button @click="summaryDialog = false">取消</el-button>
      <el-button type="primary" @click="saveSummary">保存</el-button>
    </template>
  </el-dialog>
</template>
