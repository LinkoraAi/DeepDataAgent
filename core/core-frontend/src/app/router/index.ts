import { createRouter, createWebHistory } from 'vue-router';
import AgentWorkspacePage from '@/modules/agent/pages/AgentWorkspacePage.vue';
import AgentsPage from '@/modules/agent/pages/AgentsPage.vue';
import ModelProfilesPage from '@/modules/agent/pages/ModelProfilesPage.vue';
import SkillsPage from '@/modules/agent/pages/SkillsPage.vue';
import DatasourceOverviewPage from '@/modules/datasource/pages/DatasourceOverviewPage.vue';
import MemoryOverviewPage from '@/modules/memory/pages/MemoryOverviewPage.vue';

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
      path: '/agent/management',
      name: 'agent-management',
      component: AgentsPage,
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
      path: '/skills',
      redirect: '/agent/skills',
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
