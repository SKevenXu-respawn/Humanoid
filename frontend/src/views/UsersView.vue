<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'

const rows = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const enabled = ref<boolean | null>(null)
const loading = ref(false)

const dialog = ref(false)
const editing = ref<number | null>(null)
const form = reactive({ userId: '', nickname: '', remark: '', enabled: true })

async function load() {
  loading.value = true
  try {
    const data = await api.users({
      page: page.value,
      size: size.value,
      keyword: keyword.value || undefined,
      enabled: enabled.value === null ? undefined : enabled.value
    })
    rows.value = data.records
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  Object.assign(form, { userId: '', nickname: '', remark: '', enabled: true })
  dialog.value = true
}

function openEdit(row: any) {
  editing.value = row.id
  Object.assign(form, {
    userId: String(row.userId),
    nickname: row.nickname || '',
    remark: row.remark || '',
    enabled: !!row.enabled
  })
  dialog.value = true
}

async function save() {
  const body = {
    userId: Number(form.userId),
    nickname: form.nickname,
    remark: form.remark,
    enabled: form.enabled
  }
  if (!body.userId) {
    ElMessage.warning('请输入 QQ 号')
    return
  }
  if (editing.value) {
    await api.updateUser(editing.value, body)
  } else {
    await api.createUser(body)
  }
  ElMessage.success('已保存')
  dialog.value = false
  load()
}

async function toggle(row: any) {
  try {
    await api.toggleUser(row.id, row.enabled)
  } catch {
    row.enabled = !row.enabled
  }
}

async function remove(row: any) {
  await ElMessageBox.confirm(`确定删除用户 ${row.userId}？`, '提示', { type: 'warning' })
  await api.deleteUser(row.id)
  ElMessage.success('已删除')
  load()
}

onMounted(load)
</script>

<template>
  <div>
    <div class="toolbar">
      <el-input
        v-model="keyword"
        placeholder="QQ / 昵称 / 备注"
        clearable
        style="width: 220px"
        @keyup.enter="page = 1; load()"
      />
      <el-select v-model="enabled" placeholder="状态" clearable style="width: 120px">
        <el-option label="启用" :value="true" />
        <el-option label="停用" :value="false" />
      </el-select>
      <el-button type="primary" @click="page = 1; load()">搜索</el-button>
      <el-button @click="openCreate">新增用户</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="userId" label="QQ 号" width="140" />
      <el-table-column prop="nickname" label="昵称" min-width="140" />
      <el-table-column prop="remark" label="备注" min-width="120" />
      <el-table-column label="启用" width="90">
        <template #default="{ row }">
          <el-switch v-model="row.enabled" @change="toggle(row)" />
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" width="180" />
      <el-table-column label="操作" width="140">
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

    <el-dialog v-model="dialog" :title="editing ? '编辑用户' : '新增用户'" width="480px">
      <el-form label-width="80px">
        <el-form-item label="QQ 号">
          <el-input v-model="form.userId" :disabled="!!editing" />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="form.nickname" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
