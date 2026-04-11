import { createRouter, createWebHistory } from 'vue-router';
import AgentWorkspacePage from '@/modules/agent/pages/AgentWorkspacePage.vue';
import DatasourceOverviewPage from '@/modules/datasource/pages/DatasourceOverviewPage.vue';
import MemoryOverviewPage from '@/modules/memory/pages/MemoryOverviewPage.vue';
import SkillsOverviewPage from '@/modules/skills/pages/SkillsOverviewPage.vue';

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
      component: AgentWorkspacePage,
    },
    {
      path: '/skills',
      name: 'skills',
      component: SkillsOverviewPage,
    },
    {
      path: '/memory',
      name: 'memory',
      component: MemoryOverviewPage,
    },
    {
      path: '/datasource',
      name: 'datasource',
      component: DatasourceOverviewPage,
    },
  ],
});

export default router;
