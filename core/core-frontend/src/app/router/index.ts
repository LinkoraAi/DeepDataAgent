import { createRouter, createWebHistory } from 'vue-router';

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      redirect: '/agent',
    },
    {
      path: '/agent',
      name: 'agent',
      component: () => import('@/modules/agent/pages/AgentWorkspacePage.vue'),
    },
    {
      path: '/settings',
      name: 'settings',
      component: () => import('@/settings/pages/SettingsPage.vue'),
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/agent',
    },
  ],
});

export default router;
