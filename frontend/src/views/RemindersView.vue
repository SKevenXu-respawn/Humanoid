<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'

const rows = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const status = ref('')
const userId = ref('')
const loading = ref(false)

const dialog = ref(false)
const form = reactive({
  userId: '',
  messageType: 'private',
  groupId: '',
  content: '',
  remindAt: ''
})

const STATUS_LABEL: Record<string, string> = {
  PENDING: '待触发',
  SENT: '已交给 AI',
  FAILED: '失败',
  CANCELLED: '已取消'
}
const STATUS_TYPE: Record<string, string> = {
  PENDING: 'warning',
  SENT: 'success',
  FAILED: 'danger',
  CANCELLED: 'info'
}

async function load() {
  loading.value = true
  try {
    const data = await api.reminders({
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

function openCreate() {
  Object.assign(form, {
    userId: '',
    messageType: 'private',
    groupId: '',
    content: '',
    remindAt: ''
  })
  dialog.value = true
}

async function createReminder() {
  if (!form.userId || !form.content || !form.remindAt) {
    ElMessage.warning('请填写用户、提示词和时间')
    return
  }
  await api.createReminder({
    userId: Number(form.userId),
    messageType: form.messageType,
    groupId: form.messageType === 'group' ? Number(form.groupId) : undefined,
    content: form.content,
    remindAt: form.remindAt
  })
  ElMessage.success('提醒已创建')
  dialog.value = false
  load()
}

async function cancel(row: any) {
  await ElMessageBox.confirm('取消这条提醒？', '提示', { type: 'warning' })
  await api.cancelReminder(row.id)
  ElMessage.success('已取消')
  load()
}

async function retry(row: any) {
  await api.retryReminder(row.id)
  ElMessage.success('已重新加入触发队列')
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
      <el-button @click="openCreate">新建提醒</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="userId" label="用户" width="140" />
      <el-table-column label="会话" width="110">
        <template #default="{ row }">
          {{ row.messageType === 'group' ? `群 ${row.groupId}` : '私聊' }}
        </template>
      </el-table-column>
      <el-table-column prop="content" label="提示词" min-width="220" show-overflow-tooltip />
      <el-table-column prop="remindAt" label="提醒时间" width="180" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="STATUS_TYPE[row.status] || 'info'">
            {{ STATUS_LABEL[row.status] || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="attempts" label="尝试" width="70" />
      <el-table-column label="操作" width="140">
        <template #default="{ row }">
          <el-button v-if="row.status === 'PENDING'" link type="danger" @click="cancel(row)">
            取消
          </el-button>
          <el-button
            v-if="row.status === 'FAILED' || row.status === 'CANCELLED'"
            link
            type="primary"
            @click="retry(row)"
          >
            重试
          </el-button>
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

    <el-dialog v-model="dialog" title="新建提醒" width="520px">
      <el-form label-width="90px">
        <el-form-item label="用户 QQ"><el-input v-model="form.userId" /></el-form-item>
        <el-form-item label="会话类型">
          <el-select v-model="form.messageType" style="width: 160px">
            <el-option label="私聊" value="private" />
            <el-option label="群聊" value="group" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.messageType === 'group'" label="群号">
          <el-input v-model="form.groupId" />
        </el-form-item>
        <el-form-item label="提示词">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="4"
            placeholder="到点后交给主 AI 执行的提示，例如：提醒我今晚八点开会，并顺便看一下明天的天气"
          />
        </el-form-item>
        <el-form-item label="提醒时间">
          <el-date-picker
            v-model="form.remindAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            format="YYYY-MM-DD HH:mm"
            placeholder="选择时间"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="createReminder">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>
