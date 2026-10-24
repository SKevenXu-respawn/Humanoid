<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'

const tab = ref('friends')

const friends = ref<any[]>([])
const friendLoading = ref(false)
const keyword = ref('')

const requests = ref<any[]>([])
const requestTotal = ref(0)
const requestPage = ref(1)
const requestSize = ref(20)
const requestStatus = ref('PENDING')
const requestLoading = ref(false)

const doubtRequests = ref<any[]>([])
const doubtLoading = ref(false)

const approveDialog = ref(false)
const approving = ref<any>(null)
const approveForm = reactive({ remark: '' })

const statusMeta: Record<string, { text: string; type: string }> = {
  PENDING: { text: '待处理', type: 'warning' },
  APPROVED: { text: '已同意', type: 'success' },
  REJECTED: { text: '已拒绝', type: 'info' }
}

async function loadFriends() {
  friendLoading.value = true
  try {
    friends.value = await api.friends(keyword.value || undefined)
  } finally {
    friendLoading.value = false
  }
}

async function removeFriend(row: any) {
  const name = row.remark || row.nickname || row.userId
  await ElMessageBox.confirm(
    `确定删除好友 ${name}（${row.userId}）？删除前会自动向对方发送一条告知消息，然后调用 QQ 删除好友。`,
    '删除好友',
    { type: 'warning', confirmButtonText: '发送并删除', confirmButtonClass: 'el-button--danger' }
  )
  await api.deleteFriend(row.userId)
  ElMessage.success('已删除好友')
  loadFriends()
}

async function loadRequests() {
  requestLoading.value = true
  try {
    const data = await api.friendRequests({
      page: requestPage.value,
      size: requestSize.value,
      status: requestStatus.value || undefined
    })
    requests.value = data.records
    requestTotal.value = data.total
  } finally {
    requestLoading.value = false
  }
}

function openApprove(row: any) {
  approving.value = row
  approveForm.remark = ''
  approveDialog.value = true
}

async function submitApprove() {
  await api.approveFriendRequest(approving.value.id, approveForm.remark)
  ElMessage.success('已同意好友申请')
  approveDialog.value = false
  loadRequests()
}

async function reject(row: any) {
  await ElMessageBox.confirm(
    `确定拒绝 ${row.userId} 的好友申请？`,
    '拒绝申请',
    { type: 'warning' }
  )
  await api.rejectFriendRequest(row.id)
  ElMessage.success('已拒绝好友申请')
  loadRequests()
}

async function loadDoubtRequests() {
  doubtLoading.value = true
  try {
    doubtRequests.value = await api.doubtFriendRequests(50)
  } finally {
    doubtLoading.value = false
  }
}

async function approveDoubt(row: any) {
  await ElMessageBox.confirm(
    `确定同意 ${row.nick || row.uin}（${row.uin}）的被过滤申请？该接口只能同意。`,
    '被过滤的好友申请',
    { type: 'warning' }
  )
  await api.approveDoubtFriendRequest(row.flag)
  ElMessage.success('已同意被过滤的好友申请')
  loadDoubtRequests()
}

function onTabChange(name: string) {
  if (name === 'friends') {
    loadFriends()
  } else if (name === 'requests') {
    loadRequests()
  } else if (name === 'doubt') {
    loadDoubtRequests()
  }
}

onMounted(loadFriends)
</script>

<template>
  <el-tabs v-model="tab" @tab-change="onTabChange">
    <el-tab-pane label="好友列表" name="friends">
      <div class="toolbar">
        <el-input
          v-model="keyword"
          placeholder="QQ / 昵称 / 备注 / QID"
          clearable
          style="width: 240px"
          @keyup.enter="loadFriends"
        />
        <el-button type="primary" @click="loadFriends">搜索</el-button>
        <el-button @click="loadFriends">刷新</el-button>
        <span class="empty" style="padding: 0 8px">共 {{ friends.length }} 位好友</span>
      </div>

      <el-table v-loading="friendLoading" :data="friends" border>
        <el-table-column prop="userId" label="QQ 号" width="140" />
        <el-table-column prop="nickname" label="昵称" min-width="140" />
        <el-table-column prop="remark" label="备注" min-width="120" />
        <el-table-column prop="sex" label="性别" width="80" />
        <el-table-column prop="age" label="年龄" width="80" />
        <el-table-column prop="qid" label="QID" width="120" />
        <el-table-column prop="longNick" label="个性签名" min-width="180" show-overflow-tooltip />
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button link type="danger" @click="removeFriend(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-tab-pane>

    <el-tab-pane label="好友申请" name="requests">
      <div class="toolbar">
        <el-select v-model="requestStatus" placeholder="状态" clearable style="width: 140px">
          <el-option label="待处理" value="PENDING" />
          <el-option label="已同意" value="APPROVED" />
          <el-option label="已拒绝" value="REJECTED" />
        </el-select>
        <el-button type="primary" @click="requestPage = 1; loadRequests()">查询</el-button>
      </div>

      <el-table v-loading="requestLoading" :data="requests" border>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="userId" label="申请人 QQ" width="140" />
        <el-table-column prop="comment" label="附加消息" min-width="180" show-overflow-tooltip />
        <el-table-column prop="via" label="来源" width="120" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusMeta[row.status]?.type || 'info'">
              {{ statusMeta[row.status]?.text || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" width="120" />
        <el-table-column prop="eventTime" label="申请时间" width="180" />
        <el-table-column prop="handledAt" label="处理时间" width="180" />
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <template v-if="row.status === 'PENDING'">
              <el-button link type="primary" @click="openApprove(row)">同意</el-button>
              <el-button link type="danger" @click="reject(row)">拒绝</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="requestPage"
          :total="requestTotal"
          :page-size="requestSize"
          layout="total, prev, pager, next"
          @current-change="loadRequests"
        />
      </div>

      <el-dialog v-model="approveDialog" title="同意好友申请" width="420px">
        <el-form label-width="80px">
          <el-form-item label="申请人">
            <span>{{ approving?.userId }}</span>
          </el-form-item>
          <el-form-item label="附加消息">
            <span>{{ approving?.comment || '-' }}</span>
          </el-form-item>
          <el-form-item label="好友备注">
            <el-input v-model="approveForm.remark" maxlength="64" placeholder="可留空" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="approveDialog = false">取消</el-button>
          <el-button type="primary" @click="submitApprove">同意</el-button>
        </template>
      </el-dialog>
    </el-tab-pane>

    <el-tab-pane label="被过滤申请" name="doubt">
      <div class="toolbar">
        <el-button type="primary" @click="loadDoubtRequests">刷新</el-button>
        <span class="empty" style="padding: 0 8px">
          被 QQ 过滤的好友申请；该接口只能同意，不能拒绝。
        </span>
      </div>

      <el-table v-loading="doubtLoading" :data="doubtRequests" border>
        <el-table-column prop="uin" label="QQ 号" width="140" />
        <el-table-column prop="nick" label="昵称" min-width="140" />
        <el-table-column prop="msg" label="附加消息" min-width="180" show-overflow-tooltip />
        <el-table-column prop="source" label="来源" width="160" show-overflow-tooltip />
        <el-table-column prop="reason" label="原因" width="140" show-overflow-tooltip />
        <el-table-column prop="groupCode" label="群号" width="120" />
        <el-table-column prop="time" label="时间" width="180" />
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button link type="primary" @click="approveDoubt(row)">同意</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-tab-pane>
  </el-tabs>
</template>
