<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'

const rows = ref<any[]>([])
const loading = ref(false)

const dialog = ref(false)
const editing = ref<any>(null)
const form = reactive({ value: '' })

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

async function load() {
  loading.value = true
  try {
    rows.value = await api.secrets()
  } finally {
    loading.value = false
  }
}

function openEdit(row: any) {
  editing.value = row
  form.value = ''
  dialog.value = true
}

async function save() {
  if (!form.value.trim()) {
    ElMessage.warning('请输入密钥')
    return
  }
  await api.saveSecret(editing.value.key, form.value.trim())
  ElMessage.success('已保存，立即生效')
  dialog.value = false
  load()
}

async function clear(row: any) {
  await ElMessageBox.confirm(
    `清除数据库中的「${row.label}」？清除后会回退到环境变量配置。`,
    '清除密钥',
    { type: 'warning' }
  )
  await api.clearSecret(row.key)
  ElMessage.success('已清除')
  load()
}

onMounted(load)
</script>

<template>
  <div>
    <div class="toolbar">
      <el-button type="primary" @click="load">刷新</el-button>
      <span class="empty" style="padding: 0 8px">
        密钥只显示掩码，保存后立即生效。数据库配置优先于 .env / application.properties。
      </span>
    </div>

    <el-table v-loading="loading" :data="rows" border>
      <el-table-column prop="label" label="配置项" min-width="200" />
      <el-table-column prop="key" label="键" width="180" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="SOURCE_TYPE[row.source] || 'info'">
            {{ SOURCE_LABEL[row.source] || row.source }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="masked" label="当前值" width="140">
        <template #default="{ row }">{{ row.masked || '-' }}</template>
      </el-table-column>
      <el-table-column prop="updatedAt" label="更新时间" width="180" />
      <el-table-column label="操作" width="200">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">设置</el-button>
          <el-button v-if="row.source === 'DB'" link type="danger" @click="clear(row)">
            清除覆盖
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-card shadow="never" style="margin-top: 12px">
      <div class="empty" style="text-align: left; padding: 0; line-height: 1.8">
        <b>说明</b><br />
        · DeepSeek、豆包语音、LLBot API Token、Brave 均支持在此修改，保存后下一次调用生效。<br />
        · 清除数据库覆盖后会回退到 `.env` / `application.properties` 中的值。<br />
        · `PMHQ_AUTH_TOKEN` 由 LLBot 容器启动时读取，无法由应用运行时修改；如需更换，请修改 `.env` 并重启 `llbot` 容器。<br />
        · 管理台没有登录，请只在受信任的内网访问。
      </div>
    </el-card>

    <el-dialog v-model="dialog" :title="`设置 ${editing?.label || ''}`" width="520px">
      <el-form label-width="80px">
        <el-form-item label="新密钥">
          <el-input v-model="form.value" type="password" show-password placeholder="不会回显当前值" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
