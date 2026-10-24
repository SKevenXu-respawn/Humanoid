import { http } from './http'

export const api = {
  dashboard: () => http.get('/dashboard').then((r) => r.data),
  llbot: () => http.get('/dashboard/llbot').then((r) => r.data),

  users: (params: Record<string, unknown>) => http.get('/users', { params }).then((r) => r.data),
  createUser: (body: unknown) => http.post('/users', body).then((r) => r.data),
  updateUser: (id: number, body: unknown) => http.put(`/users/${id}`, body).then((r) => r.data),
  toggleUser: (id: number, enabled: boolean) =>
    http.patch(`/users/${id}/enabled`, { enabled }).then((r) => r.data),
  deleteUser: (id: number) => http.delete(`/users/${id}`),

  groups: (params: Record<string, unknown>) => http.get('/groups', { params }).then((r) => r.data),
  createGroup: (body: unknown) => http.post('/groups', body).then((r) => r.data),
  updateGroup: (id: number, body: unknown) => http.put(`/groups/${id}`, body).then((r) => r.data),
  toggleGroup: (id: number, enabled: boolean) =>
    http.patch(`/groups/${id}/enabled`, { enabled }).then((r) => r.data),
  deleteGroup: (id: number) => http.delete(`/groups/${id}`),

  contexts: (q?: string) => http.get('/contexts', { params: { q } }).then((r) => r.data),
  contextMessages: (params: Record<string, unknown>) =>
    http.get('/contexts/messages', { params }).then((r) => r.data),
  clearContext: (params: Record<string, unknown>) =>
    http.delete('/contexts', { params }).then((r) => r.data),
  getSummary: (params: Record<string, unknown>) =>
    http.get('/contexts/summary', { params }).then((r) => r.data),
  saveSummary: (body: unknown) => http.put('/contexts/summary', body).then((r) => r.data),
  deleteSummary: (params: Record<string, unknown>) => http.delete('/contexts/summary', { params }),

  tasks: (params: Record<string, unknown>) => http.get('/tasks', { params }).then((r) => r.data),
  createTask: (body: unknown) => http.post('/tasks', body).then((r) => r.data),
  taskDetail: (id: number) => http.get(`/tasks/${id}`).then((r) => r.data),
  addTaskStep: (id: number, content: string) =>
    http.post(`/tasks/${id}/steps`, { content }).then((r) => r.data),
  completeTaskStep: (id: number, stepId: number) =>
    http.post(`/tasks/${id}/steps/${stepId}/complete`).then((r) => r.data),
  taskAction: (id: number, action: string, body?: unknown) =>
    http.post(`/tasks/${id}/${action}`, body).then((r) => r.data),
  deleteTask: (id: number) => http.delete(`/tasks/${id}`),

  reminders: (params: Record<string, unknown>) =>
    http.get('/reminders', { params }).then((r) => r.data),
  createReminder: (body: unknown) => http.post('/reminders', body).then((r) => r.data),
  cancelReminder: (id: number) => http.post(`/reminders/${id}/cancel`).then((r) => r.data),
  retryReminder: (id: number) => http.post(`/reminders/${id}/retry`).then((r) => r.data),

  friends: (keyword?: string) =>
    http.get('/friends', { params: { keyword } }).then((r) => r.data),
  deleteFriend: (userId: number) => http.delete(`/friends/${userId}`),
  friendRequests: (params: Record<string, unknown>) =>
    http.get('/friends/requests', { params }).then((r) => r.data),
  approveFriendRequest: (id: number, remark: string) =>
    http.post(`/friends/requests/${id}/approve`, { remark }).then((r) => r.data),
  rejectFriendRequest: (id: number) =>
    http.post(`/friends/requests/${id}/reject`).then((r) => r.data),
  doubtFriendRequests: (count?: number) =>
    http.get('/friends/doubt-requests', { params: { count } }).then((r) => r.data),
  approveDoubtFriendRequest: (flag: string) =>
    http.post('/friends/doubt-requests/approve', { flag }).then((r) => r.data),

  voiceConfig: () => http.get('/voice').then((r) => r.data),
  saveVoiceConfig: (body: unknown) => http.put('/voice', body).then((r) => r.data)
}

export const imageUrl = (id: number) => `/api/admin/images/${id}`
