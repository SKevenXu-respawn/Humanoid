import { createRouter, createWebHashHistory } from 'vue-router'

import DashboardView from '../views/DashboardView.vue'
import UsersView from '../views/UsersView.vue'
import GroupsView from '../views/GroupsView.vue'
import ContextsView from '../views/ContextsView.vue'
import TasksView from '../views/TasksView.vue'
import RemindersView from '../views/RemindersView.vue'
import FriendsView from '../views/FriendsView.vue'
import VoiceView from '../views/VoiceView.vue'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/dashboard' },
    { path: '/dashboard', component: DashboardView, meta: { title: '概览' } },
    { path: '/users', component: UsersView, meta: { title: '用户白名单' } },
    { path: '/groups', component: GroupsView, meta: { title: '群白名单' } },
    { path: '/contexts', component: ContextsView, meta: { title: '上下文与总结' } },
    { path: '/tasks', component: TasksView, meta: { title: '任务' } },
    { path: '/reminders', component: RemindersView, meta: { title: '提醒' } },
    { path: '/friends', component: FriendsView, meta: { title: '好友管理' } },
    { path: '/voice', component: VoiceView, meta: { title: '语音设置' } }
  ]
})

export default router
