<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'

const rows = ref<any[]>([])
const q = ref('')
const loading = ref(false)

const dialog = ref(false)
const editing = ref(false)
const form = reactive({ userId: '', content: '' })
const saving = ref(false)

async function load() {
  loading.value = true
  try {
    rows.value = await api.userPrompts(q.value || undefined)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = false
  form.userId = ''
  form.content = ''
  dialog.value = true
}

function openEdit(row: any) {
  editing.value = true
  form.userId = String(row.userId)
  form.content = row.content || ''
  dialog.value = true
}

async function loadTemplate() {
  if (form.content.trim()) {
    await ElMessageBox.confirm('当前编辑内容会被全局系统提示词覆盖，继续？', '载入模板', {
      type: 'warning'
    })
  }
  const prompts = await api.prompts()
  const system = prompts.find((item: any) => item.key === 'system_prompt')
  if (!system) {
    ElMessage.warning('没有找到系统提示词')
    return
  }
  form.content = system.content
  ElMessage.success('已载入全局系统提示词模板')
}

async function save() {
  const userId = Number(form.userId)
  if (!userId) {
    ElMessage.warning('请输入 QQ 号')
    return
  }
  if (!form.content.trim()) {
    ElMessage.warning('提示词不能为空')
    return
  }
  saving.value = true
  try {
    await api.saveUserPrompt(userId, form.content)
    ElMessage.success('已保存，该用户后续消息生效')
    dialog.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function remove(row: any) {
  await ElMessageBox.confirm(
    `删除 ${row.userId} 的自定义提示词？删除后该用户会回退到全局系统提示词。`,
    '删除绑定',
    { type: 'warning' }
  )
  await api.deleteUserPrompt(row.userId)
  ElMessage.success('已删除绑定')
  load()
}

onMounted(load)
</script>

<template>
  <div>
    <div class="toolbar">
      <el-input v-model="q" placeholder="QQ 号 / 昵称" clearable style="width: 200px" @keyup.enter="load" />
      <el-button type="primary" @click="load">搜索</el-button>
      <el-button @click="openCreate">新增绑定</el-button>
      <span class="empty" style="padding: 0 8px">
        按 QQ 绑定自定义系统提示词；未绑定或删除绑定则使用全局系统提示词。
      </span>
    </div>

    <el-table v-loading="loading" :data="rows" border>
      <el-table-column prop="userId" label="QQ 号" width="150" />
      <el-table-column prop="nickname" label="昵称" width="160" />
      <el-table-column label="提示词" min-width="320">
        <template #default="{ row }">
          {{ (row.content || '').slice(0, 160) }}{{ (row.content || '').length > 160 ? '…' : '' }}
        </template>
      </el-table-column>
      <el-table-column label="字数" width="90">
        <template #default="{ row }">{{ (row.content || '').length }}</template>
      </el-table-column>
      <el-table-column prop="updatedAt" label="更新时间" width="180" />
      <el-table-column label="操作" width="130">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="dialog"
      :title="editing ? '编辑用户提示词' : '新增用户提示词'"
      width="760px"
    >
      <el-form label-width="90px">
        <el-form-item label="QQ 号">
          <el-input v-model="form.userId" :disabled="editing" placeholder="绑定到哪个 QQ" />
        </el-form-item>
        <el-form-item label="提示词">
          <div style="width: 100%">
            <div class="toolbar" style="margin-bottom: 8px">
              <el-button size="small" @click="loadTemplate">载入全局系统提示词模板</el-button>
              <span class="empty" style="padding: 0 8px">
                自定义提示词只对该 QQ 发送的消息生效
              </span>
            </div>
            <el-input v-model="form.content" type="textarea" :rows="18" style="font-family: monospace" />
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
