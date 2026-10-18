<script setup lang="ts">
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/app/store/auth';
import { MessagePlugin } from 'tdesign-vue-next';
import { errorMessage } from '@/shared/composables/useAsync';

/**
 * 应用外壳：左侧分组导航（仅后端有契约的八个入口）+ 底部用户区。
 * <p>侧栏分组与顶栏面包屑共用同一套中文分组名（路由 meta.group）：
 * 智能体平台（智能体 / 会话）→ 资源管理（模型配置 / 运行环境 / 文件 / 技能）→ 知识与数据（知识库 / 数据源）。</p>
 * <p>导航项必须携带 `to`，TDesign 菜单仅在配置了 `to`/`routerLink` 时才会触发路由跳转，
 * 否则点击只高亮不导航（历史缺陷：除默认知识库外其余入口均无法进入）。</p>
 * <p>三个一级分组通过 menu 的 default-expanded 默认展开，用户仍可点击折叠。</p>
 * <p>部署/Vault/记忆/可观测/评测/MCP 等裁剪能力在此 MUST NOT 出现。</p>
 */
const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();

const activeMenu = computed(() => `/${route.path.split('/')[1] || 'kb'}`);
const userEmail = computed(() => authStore.user?.email ?? '');
/** 头像文字：取邮箱首字符（空邮箱回落 D）。 */
const avatarText = computed(() => (userEmail.value.charAt(0) || 'D').toUpperCase());
/** 面包屑分组名（路由 meta.group，缺省回落侧栏分组标题）。 */
const breadcrumbGroup = computed(() => (route.meta.group as string | undefined) ?? '智能体平台');
/** 面包屑当前页名（路由 meta.title，缺省回落侧栏菜单文案）。 */
const breadcrumbTitle = computed(() => (route.meta.title as string | undefined) ?? '页面');

async function onLogout(): Promise<void> {
  try {
    await authStore.logout();
  } catch (e) {
    MessagePlugin.warning(errorMessage(e));
  }
  router.replace('/login');
}
</script>

<template>
  <t-layout class="shell">
    <t-aside class="shell__aside" :width="'var(--dda-sidebar-width)'">
      <div class="shell__brand">
        <span class="shell__brand-mark">D</span>
        <span class="shell__brand-text">
          <span class="shell__brand-name">DeepDataAgent</span>
          <span class="shell__brand-sub">数据智能体平台</span>
        </span>
      </div>

      <t-menu
        :value="activeMenu"
        theme="light"
        class="shell__menu"
        :default-expanded="['agents-platform', 'resource-manage', 'knowledge-data']"
      >
        <t-submenu value="agents-platform" title="智能体平台">
          <template #icon><t-icon name="app" /></template>
          <t-menu-item value="/agents" to="/agents">
            <template #icon><t-icon name="control-platform" /></template>
            智能体
          </t-menu-item>
          <t-menu-item value="/sessions" to="/sessions">
            <template #icon><t-icon name="chat-bubble" /></template>
            会话
          </t-menu-item>
        </t-submenu>

        <t-submenu value="resource-manage" title="资源管理">
          <template #icon><t-icon name="setting" /></template>
          <t-menu-item value="/models" to="/models">
            <template #icon><t-icon name="layers" /></template>
            模型配置
          </t-menu-item>
          <t-menu-item value="/environments" to="/environments">
            <template #icon><t-icon name="desktop" /></template>
            运行环境
          </t-menu-item>
          <t-menu-item value="/files" to="/files">
            <template #icon><t-icon name="file-copy" /></template>
            文件
          </t-menu-item>
          <t-menu-item value="/skills" to="/skills">
            <template #icon><t-icon name="code" /></template>
            技能
          </t-menu-item>
        </t-submenu>

        <t-submenu value="knowledge-data" title="知识与数据">
          <template #icon><t-icon name="data" /></template>
          <t-menu-item value="/kb" to="/kb">
            <template #icon><t-icon name="folder" /></template>
            知识库
          </t-menu-item>
          <t-menu-item value="/datasource" to="/datasource">
            <template #icon><t-icon name="server" /></template>
            数据源
          </t-menu-item>
        </t-submenu>
      </t-menu>

      <div class="shell__user">
        <t-avatar class="shell__avatar" size="small">{{ avatarText }}</t-avatar>
        <span class="shell__email dda-ellipsis" :title="userEmail">{{ userEmail || '未登录' }}</span>
        <t-tooltip content="退出登录">
          <t-button variant="text" shape="square" size="small" aria-label="退出登录" @click="onLogout">
            <t-icon name="logout" />
          </t-button>
        </t-tooltip>
      </div>
    </t-aside>

    <t-layout class="shell__main">
      <header class="dda-topbar">
        <nav class="dda-breadcrumb" aria-label="面包屑">
          <span>{{ breadcrumbGroup }}</span>
          <span class="dda-breadcrumb__sep" aria-hidden="true">›</span>
          <span class="dda-breadcrumb__current">{{ breadcrumbTitle }}</span>
        </nav>
      </header>
      <t-content class="shell__content">
        <router-view v-slot="{ Component }">
          <transition name="dda-page-fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </t-content>
    </t-layout>
  </t-layout>
</template>

<style scoped>
.shell {
  height: 100%;
}

.shell__aside {
  display: flex;
  flex-direction: column;
  padding: 14px 12px;
  background: var(--td-bg-color-container);
  border-right: 1px solid var(--dda-card-border);
}

.shell__brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 8px 16px;
}

.shell__brand-mark {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  font-size: 17px;
  font-weight: 800;
  color: #fff;
  background: linear-gradient(135deg, var(--td-brand-color-5) 0%, var(--td-brand-color-8) 100%);
  border-radius: 10px;
  box-shadow: 0 4px 10px rgba(var(--td-brand-rgb), 0.28);
}

.shell__brand-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
  line-height: 1.25;
}

.shell__brand-name {
  font-size: 15px;
  font-weight: 700;
  color: var(--td-text-color-primary);
}

.shell__brand-sub {
  font-size: 11px;
  color: var(--td-text-color-placeholder);
}

.shell__menu {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  border: none;
  background: transparent;
}

/* 菜单项：圆角胶囊 + 悬浮/选中统一色调 */
.shell__menu :deep(.t-menu__item) {
  margin: 2px 0;
  border-radius: 10px;
  color: var(--td-text-color-secondary);
}

.shell__menu :deep(.t-menu__item:hover) {
  color: var(--td-text-color-primary);
  background: var(--td-bg-color-container-hover);
}

.shell__menu :deep(.t-menu__item.t-is-active) {
  font-weight: 600;
  color: var(--td-brand-color);
  background: var(--td-brand-color-light);
}

/* 一级分组折叠层：触发器加粗，箭头靠右 */
.shell__menu :deep(.t-submenu > .t-menu__item) {
  font-weight: 600;
}

.shell__menu :deep(.t-submenu > .t-menu__item .t-fake-arrow) {
  margin-left: auto;
  opacity: 0.5;
}

/* 折叠层内部：菜单项整体缩进一级，层级关系一目了然 */
.shell__menu :deep(.t-menu__sub) {
  padding: 0;
  overflow: hidden;
}

.shell__menu :deep(.t-menu__sub .t-menu__item) {
  padding-left: 26px;
}

.shell__user {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
  padding: 10px 10px;
  background: var(--td-bg-color-secondarycontainer);
  border-radius: 12px;
}

.shell__avatar {
  flex: none;
  color: var(--td-brand-color);
  background: var(--td-brand-color-2);
}

.shell__email {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  color: var(--td-text-color-secondary);
}

.shell__content {
  min-width: 0;
  height: calc(100vh - 52px);
  overflow: auto;
  background: var(--td-bg-color-page);
}

/*
 * 内容主区是 aside 的 flex 兄弟：flex 子项默认 min-width:auto，
 * 宽表格会把主区撑破导致整页横向滚动。置 0 后主区被约束在剩余宽度内，
 * 表格改由自身容器横向滚动（TDesign fixed 布局自带 overflow）。
 */
.shell__main {
  min-width: 0;
}
</style>