import { createRouter, createWebHistory } from 'vue-router';
import LoginPage from '@/modules/auth/pages/LoginPage.vue';
import AgentWorkspacePage from '@/modules/agent/pages/AgentWorkspacePage.vue';
import AgentsPage from '@/modules/agent/pages/AgentsPage.vue';
import DeploymentsPage from '@/modules/agent/pages/DeploymentsPage.vue';
import EnvironmentsPage from '@/modules/agent/pages/EnvironmentsPage.vue';
import ModelProfilesPage from '@/modules/agent/pages/ModelProfilesPage.vue';
import SkillsPage from '@/modules/agent/pages/SkillsPage.vue';
import VaultsPage from '@/modules/agent/pages/VaultsPage.vue';
import MemoryStoresPage from '@/modules/memory/pages/MemoryStoresPage.vue';
import { useAuthStore } from '@/app/store/auth';

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: LoginPage,
      // 公开路由：未登录可直达（后端白名单仅 auth 端点，本页自身不发受保护请求）
      meta: { public: true },
    },
    {
      path: '/',
      redirect: '/agent',
    },
    {
      path: '/agent',
      name: 'agent',
      component: AgentWorkspacePage,
    },
    {
      path: '/agent/management',
      name: 'agent-management',
      component: AgentsPage,
    },
    {
      path: '/agent/environments',
      name: 'agent-environments',
      component: EnvironmentsPage,
    },
    {
      path: '/agent/model-profiles',
      name: 'agent-model-profiles',
      component: ModelProfilesPage,
    },
    {
      path: '/agent/skills',
      name: 'agent-skills',
      component: SkillsPage,
    },
    {
      path: '/agent/vaults',
      name: 'agent-vaults',
      component: VaultsPage,
    },
    {
      path: '/agent/deployments',
      name: 'agent-deployments',
      component: DeploymentsPage,
    },
    {
      path: '/skills',
      redirect: '/agent/skills',
    },
    {
      path: '/memory',
      name: 'memory',
      component: MemoryStoresPage,
    },
  ],
});

/**
 * 全局登录守卫：非 public 路由未登录一律跳 /login，
 * redirect 查询参数承载原路径供登录成功后回跳。
 */
router.beforeEach((to) => {
  if (to.meta.public) {
    return true;
  }
  const authStore = useAuthStore();
  if (authStore.isAuthenticated) {
    return true;
  }
  return { path: '/login', query: { redirect: to.fullPath } };
});

export default router;
