<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const title = computed(() => (route.meta.title as string) || '管理台')
</script>

<template>
  <el-container class="layout">
    <el-aside width="220px" class="aside">
      <div class="logo">Humanoid</div>
      <el-menu router :default-active="route.path" class="menu">
        <el-menu-item index="/dashboard">概览</el-menu-item>
        <el-menu-item index="/users">用户白名单</el-menu-item>
        <el-menu-item index="/groups">群白名单</el-menu-item>
        <el-menu-item index="/contexts">上下文与总结</el-menu-item>
        <el-menu-item index="/tasks">任务</el-menu-item>
        <el-menu-item index="/reminders">提醒</el-menu-item>
        <el-menu-item index="/friends">好友管理</el-menu-item>
        <el-menu-item index="/voice">语音设置</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span class="title">{{ title }}</span>
        <span class="hint">内部管理台 · 无登录</span>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>
