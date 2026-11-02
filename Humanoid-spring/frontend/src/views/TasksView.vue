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
const keyword = ref('')
const loading = ref(false)

const drawer = ref(false)
const detail = ref<any>(null)
const newStep = ref('')

const dialog = ref(false)
const form = reactive({
  userId: '',
  messageType: 'private',
  groupId: '',
  title: '',
  stepsText: ''
})

const STATUS_LABEL: Record<string, string> = {
  PENDING: '未开始',
  IN_PROGRESS: '进行中',
  PAUSED: '已暂停',
  COMPLETED: '已完成'
}
const STATUS_TYPE: Record<string, string> = {
  PENDING: 'info',
  IN_PROGRESS: 'warning',
  PAUSED: 'danger',
  COMPLETED: 'success'
}

async function load() {
  loading.value = true
  try {
    const data = await api.tasks({
      page: page.value,
      size: size.value,
      status: status.value || undefined,
      userId: userId.value || undefined,
      keyword: keyword.value || undefined
    })
    rows.value = data.records
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(form, { userId: '', messageType: 'private', groupId: '', title: '', stepsText: '' })
  dialog.value = true
}

async function createTask() {
  if (!form.userId || !form.title) {
    ElMessage.warning('请填写用户 QQ 号和任务标题')
    return
  }
  const steps = form.stepsText
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
  await api.createTask({
    userId: Number(form.userId),
    messageType: form.messageType,
    groupId: form.messageType === 'group' ? Number(form.groupId) : undefined,
    title: form.title,
    steps
  })
  ElMessage.success('任务已创建')
  dialog.value = false
  load()
}

async function openDetail(row: any) {
  detail.value = await api.taskDetail(row.id)
  newStep.value = ''
  drawer.value = true
}

async function refresh() {
  if (!detail.value) {
    return
  }
  detail.value = await api.taskDetail(detail.value.task.id)
  load()
}

async function action(name: string) {
  const id = detail.value.task.id
  if (name === 'pause') {
    const { value } = await ElMessageBox.prompt('暂停原因', '暂停任务', {
      inputPlaceholder: '例如：等待用户提供资料'
    })
    await api.taskAction(id, 'pause', { reason: value })
  } else {
    await api.taskAction(id, name)
  }
  ElMessage.success('操作成功')
  await refresh()
}

async function completeStep(step: any) {
  await api.completeTaskStep(detail.value.task.id, step.id)
  await refresh()
}

async function addStep() {
  if (!newStep.value.trim()) {
    return
  }
  await api.addTaskStep(detail.value.task.id, newStep.value.trim())
  newStep.value = ''
  await refresh()
}

async function removeTask() {
  await ElMessageBox.confirm('删除任务及其所有步骤？', '提示', { type: 'warning' })
  await api.deleteTask(detail.value.task.id)
  ElMessage.success('已删除')
  drawer.value = false
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
      <el-input v-model="keyword" placeholder="标题关键词" clearable style="width: 180px" />
      <el-button type="primary" @click="page = 1; load()">搜索</el-button>
      <el-button @click="openCreate">新建任务</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="title" label="标题" min-width="180" />
      <el-table-column prop="userId" label="用户" width="140" />
      <el-table-column label="会话" width="110">
        <template #default="{ row }">
          {{ row.messageType === 'group' ? `群 ${row.groupId}` : '私聊' }}
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="STATUS_TYPE[row.status] || 'info'">
            {{ STATUS_LABEL[row.status] || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="进度" width="100">
        <template #default="{ row }">{{ row.stepsDone }}/{{ row.stepsTotal }}</template>
      </el-table-column>
      <el-table-column prop="updatedAt" label="更新时间" width="180" />
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
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

    <el-dialog v-model="dialog" title="新建任务" width="560px">
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
        <el-form-item label="标题"><el-input v-model="form.title" /></el-form-item>
        <el-form-item label="步骤">
          <el-input v-model="form.stepsText" type="textarea" :rows="5" placeholder="每行一个步骤" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="createTask">创建</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="drawer" size="560px" :title="detail ? `任务 #${detail.task.id} ${detail.task.title}` : '任务'">
      <div v-if="detail">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="状态">
            <el-tag :type="STATUS_TYPE[detail.task.status] || 'info'">
              {{ STATUS_LABEL[detail.task.status] || detail.task.status }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="用户">{{ detail.task.userId }}</el-descriptions-item>
          <el-descriptions-item label="进度">
            {{ detail.task.stepsDone }}/{{ detail.task.stepsTotal }}
          </el-descriptions-item>
          <el-descriptions-item label="暂停原因">{{ detail.task.pauseReason || '-' }}</el-descriptions-item>
        </el-descriptions>

        <div class="toolbar" style="margin-top: 12px">
          <el-button v-if="detail.task.status === 'PENDING'" type="primary" @click="action('start')">
            开始
          </el-button>
          <el-button v-if="detail.task.status === 'IN_PROGRESS'" @click="action('pause')">暂停</el-button>
          <el-button v-if="detail.task.status === 'PAUSED'" type="primary" @click="action('resume')">
            恢复
          </el-button>
          <el-button v-if="detail.task.status !== 'COMPLETED'" type="success" @click="action('complete')">
            完成
          </el-button>
          <el-button type="danger" plain @click="removeTask">删除</el-button>
        </div>

        <el-divider>步骤</el-divider>
        <div v-for="step in detail.steps" :key="step.id" class="msg-row">
          <div style="display: flex; justify-content: space-between; align-items: center">
            <div>
              <el-tag size="small" :type="step.status === 'COMPLETED' ? 'success' : 'info'">
                {{ step.stepNo }}
              </el-tag>
              <span style="margin-left: 8px">{{ step.content }}</span>
            </div>
            <el-button
              v-if="step.status !== 'COMPLETED'"
              link
              type="primary"
              @click="completeStep(step)"
            >
              完成
            </el-button>
          </div>
        </div>

        <div class="toolbar" style="margin-top: 12px">
          <el-input v-model="newStep" placeholder="新增步骤" @keyup.enter="addStep" />
          <el-button @click="addStep">添加</el-button>
        </div>
      </div>
    </el-drawer>
  </div>
</template>
