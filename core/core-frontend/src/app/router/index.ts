import { createRouter, createWebHistory } from 'vue-router';
import type { RouteRecordRaw } from 'vue-router';
import AppShell from '@/layouts/AppShell.vue';
import { getAuthToken } from '@/shared/api/http';

/**
 * 路由表：/login 公开，其余业务路由挂在 AppShell 布局下；
 * 导航仅含后端有契约的八个入口（知识库/数据源/智能体/会话/模型配置/运行环境/文件/技能）。
 * meta.group/meta.title 供顶栏面包屑渲染（对齐控制台版式）。
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/modules/auth/pages/LoginPage.vue'),
    meta: { public: true },
  },
  {
    path: '/',
    component: AppShell,
    redirect: '/kb',
    children: [
      {
        path: 'kb',
        name: 'kb-list',
        component: () => import('@/modules/knowledgebase/pages/KbListPage.vue'),
        meta: { group: '知识与数据', title: '知识库' },
      },
      {
        path: 'kb/:kbId',
        name: 'kb-detail',
        component: () => import('@/modules/knowledgebase/pages/KbDetailPage.vue'),
        props: true,
        meta: { group: '知识与数据', title: '知识库详情' },
      },
      {
        path: 'datasource',
        name: 'datasource',
        component: () => import('@/modules/datasource/pages/DatasourcePage.vue'),
        meta: { group: '知识与数据', title: '数据源' },
      },
      {
        path: 'agents',
        name: 'agents',
        component: () => import('@/modules/agent/pages/AgentsPage.vue'),
        meta: { group: '智能体平台', title: '智能体' },
      },
      {
        path: 'agents/:agentId',
        name: 'agent-versions',
        component: () => import('@/modules/agent/pages/AgentVersionsPage.vue'),
        props: true,
        meta: { group: '智能体平台', title: '版本管理' },
      },
      {
        path: 'sessions',
        name: 'sessions',
        component: () => import('@/modules/session/pages/SessionsPage.vue'),
        meta: { group: '智能体平台', title: '会话' },
      },
      {
        path: 'sessions/:sessionId',
        name: 'session-workspace',
        component: () => import('@/modules/session/pages/SessionWorkspacePage.vue'),
        props: true,
        meta: { group: '智能体平台', title: '会话详情' },
      },
      {
        path: 'models',
        name: 'models',
        component: () => import('@/modules/model/pages/ModelProfilesPage.vue'),
        meta: { group: '资源管理', title: '模型配置' },
      },
      {
        path: 'environments',
        name: 'environments',
        component: () => import('@/modules/environment/pages/EnvironmentsPage.vue'),
        meta: { group: '资源管理', title: '运行环境' },
      },
      {
        path: 'files',
        name: 'files',
        component: () => import('@/modules/assetfile/pages/FilesPage.vue'),
        meta: { group: '资源管理', title: '文件' },
      },
      {
        path: 'skills',
        name: 'skills',
        component: () => import('@/modules/skill/pages/SkillsPage.vue'),
        meta: { group: '资源管理', title: '技能' },
      },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/kb' },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
});

/** 全局登录守卫：非 public 路由未登录跳 /login?redirect=。 */
router.beforeEach((to) => {
  if (to.meta.public) {
    return true;
  }
  if (getAuthToken()) {
    return true;
  }
  return { path: '/login', query: to.fullPath === '/' ? {} : { redirect: to.fullPath } };
});

export default router;
