<template>
  <t-layout class="app-shell">
    <t-header class="app-shell__header">
      <div>
        <p class="app-shell__eyebrow">AgentScope Java + TDesign</p>
        <h1 class="app-shell__title">DeepDataAgent</h1>
      </div>
      <div class="app-shell__nav">
        <RouterLink v-for="item in navItems" :key="item.to" :to="item.to" class="app-shell__nav-link">
          <t-button variant="text" theme="default">{{ item.label }}</t-button>
        </RouterLink>
        <span v-if="authStore.user" class="app-shell__user">{{ authStore.user.email }}</span>
        <t-button v-if="authStore.isAuthenticated" size="small" variant="outline" @click="handleLogout">退出登录</t-button>
      </div>
    </t-header>
    <t-content class="app-shell__content">
      <RouterView />
    </t-content>
  </t-layout>
</template>

<script setup lang="ts">
import { RouterLink, RouterView, useRouter } from 'vue-router';
import type { NavigationItem } from '@/shared/types/navigation';
import { useAuthStore } from '@/app/store/auth';

const router = useRouter();
const authStore = useAuthStore();

const navItems: NavigationItem[] = [
  { label: 'Agent', to: '/agent' },
  { label: 'Agent 管理', to: '/agent/management' },
  { label: '环境管理', to: '/agent/environments' },
  { label: '模型配置', to: '/agent/model-profiles' },
  { label: '技能管理', to: '/agent/skills' },
  { label: '保管库', to: '/agent/vaults' },
  { label: '定时部署', to: '/agent/deployments' },
  { label: '记忆库', to: '/memory' },
];

/** 登出：撤销服务端 token 并清理本地会话，回登录页。 */
async function handleLogout(): Promise<void> {
  await authStore.logout();
  await router.replace('/login');
}
</script>

<style scoped>
.app-shell__user {
  display: inline-flex;
  align-items: center;
  font-size: 13px;
  color: var(--td-text-color-secondary);
}
</style>
