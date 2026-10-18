<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { MessagePlugin } from 'tdesign-vue-next';
import PageHeader from '@/shared/components/PageHeader.vue';
import StatusTag from '@/shared/components/StatusTag.vue';
import EmptyGuide from '@/shared/components/EmptyGuide.vue';
import RowMenu from '@/shared/components/RowMenu.vue';
import type { RowMenuItem } from '@/shared/components/RowMenu.vue';
import { KB_LIFECYCLE } from '@/shared/enums/dict';
import { errorMessage } from '@/shared/composables/useAsync';
import { usePagePagination } from '@/shared/composables/usePagination';
import { usePolling } from '@/shared/composables/usePolling';
import { confirmDangerous } from '@/shared/composables/useConfirm';
import {
  deleteKnowledgeBase,
  getStats,
  listKnowledgeBases,
} from '../api/knowledgeBase';
import type { KnowledgeBaseStatsWire, KnowledgeBaseWire } from '../api/knowledgeBase';
import KbFormDrawer from '../components/KbFormDrawer.vue';

/** 知识库列表页：全局统计条 + 搜索 + 排序 + 分页卡片网格 + 删除受理轮询收敛。 */
const router = useRouter();

/** 顶部统计 */
const stats = ref<KnowledgeBaseStatsWire>({ totalKnowledgeBases: 0, totalDocuments: 0, totalChunks: 0 });
/** 卡片列表 */
const list = ref<KnowledgeBaseWire[]>([]);
/** 加载态 */
const loading = ref(false);
/** 删除提交中态（按库 ID 记录，用于按钮 loading） */
const deletingIds = reactive(new Set<number>());

/** 查询条件 */
const keyword = ref('');
const sortBy = ref('createdAt');
const sortOrder = ref('desc');

const pager = usePagePagination<KnowledgeBaseWire>(12);

const SORT_FIELD_OPTIONS = [
  { value: 'createdAt', label: '创建时间' },
  { value: 'updatedAt', label: '更新时间' },
  { value: 'name', label: '名称' },
];
const ORDER_OPTIONS = [
  { value: 'desc', label: '降序' },
  { value: 'asc', label: '升序' },
];

/** 抽屉状态 */
const drawerVisible = ref(false);
const editingKb = ref<KnowledgeBaseWire | null>(null);

/** 加载分页列表（供首次、翻页、轮询复用） */
async function loadList(): Promise<KnowledgeBaseWire[]> {
  const resp = await listKnowledgeBases({
    keyword: keyword.value.trim() || undefined,
    page: pager.page.value,
    size: pager.size.value,
    sortBy: sortBy.value,
    sortOrder: sortOrder.value,
  });
  const items = pager.apply(resp);
  list.value = items;
  return items;
}

/** 加载统计条 */
async function loadStats(): Promise<void> {
  try {
    stats.value = await getStats();
  } catch (e) {
    MessagePlugin.error(`统计加载失败：${errorMessage(e)}`);
  }
}

/** 刷新列表与统计 */
async function refresh(): Promise<void> {
  loading.value = true;
  try {
    await loadList();
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
  } finally {
    loading.value = false;
  }
  void loadStats();
}

/** 条件轮询：只要列表仍存在 DELETING 库就继续刷新，全部收敛后停止。 */
const polling = usePolling(async () => {
  const items = await loadList();
  const stillDeleting = items.some((item) => item.lifecycleStatus === 'DELETING');
  if (!stillDeleting) {
    void loadStats();
  }
  return stillDeleting;
}, 4000);

/** 关键字搜索（回车/清空触发） */
function onSearch(): void {
  pager.reset();
  void refresh();
}

/** 排序变更 */
function onSortChange(): void {
  pager.reset();
  void refresh();
}

/** 翻页 */
function onPageChange(current: number): void {
  pager.page.value = current;
  void refresh();
}

/** 进入详情 */
function goDetail(kb: KnowledgeBaseWire): void {
  if (kb.lifecycleStatus !== 'ACTIVE') {
    return;
  }
  router.push({ name: 'kb-detail', params: { kbId: String(kb.id) } });
}

/** 卡片点击进入详情（仅活跃态可进入） */
function onCardClick(kb: KnowledgeBaseWire): void {
  goDetail(kb);
}

/** 打开新建抽屉 */
function openCreate(): void {
  editingKb.value = null;
  drawerVisible.value = true;
}

/** 打开编辑抽屉 */
function openEdit(kb: KnowledgeBaseWire): void {
  editingKb.value = kb;
  drawerVisible.value = true;
}

/** 保存成功：刷新列表；新建直接停轮询刷新，编辑保留轮询状态 */
function onSaved(): void {
  void refresh();
}

/**
 * 删除知识库：二次确认 → 受理（code=202）→ 卡片乐观置删除中并启动轮询；
 * 已 DELETE_FAILED 的库经本流程重删续跑（后端 DELETE_FAILED → DELETING）。
 */
async function onDelete(kb: KnowledgeBaseWire): Promise<void> {
  const failed = kb.lifecycleStatus === 'DELETE_FAILED';
  const confirmed = await confirmDangerous({
    header: failed ? '重新删除知识库' : '删除知识库',
    body: failed
      ? `知识库「${kb.name}」上次删除失败，将重新发起清退，确认继续？`
      : `确认删除知识库「${kb.name}」？该操作不可逆，将异步清退其全部文档与切片。`,
    theme: 'danger',
    confirmText: '删除',
  });
  if (!confirmed) {
    return;
  }
  deletingIds.add(kb.id);
  try {
    const { accepted } = await deleteKnowledgeBase(kb.id);
    if (accepted) {
      // 乐观更新：本地先置删除中，随后由轮询以服务端真实状态收敛
      kb.lifecycleStatus = 'DELETING';
      kb.errorMessage = null;
      polling.start();
    } else {
      MessagePlugin.warning('删除请求未被受理，请稍后重试');
      void refresh();
    }
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
  } finally {
    deletingIds.delete(kb.id);
  }
}

/** 卡片操作项：统一收进"更多"菜单（与全站表格行操作同一交互模式），危险项红色分隔呈现。 */
function cardActions(kb: KnowledgeBaseWire): RowMenuItem[] {
  const failed = kb.lifecycleStatus === 'DELETE_FAILED';
  const busy = deletingIds.has(kb.id);
  const deletable = kb.lifecycleStatus !== 'DELETING' && !busy;
  return [
    {
      key: 'edit',
      label: '编辑',
      icon: 'edit',
      disabled: kb.lifecycleStatus !== 'ACTIVE' || busy,
    },
    {
      key: failed ? 'redelete' : 'delete',
      label: failed ? '重新删除' : '删除',
      icon: 'delete',
      destructive: true,
      disabled: !deletable,
    },
  ];
}

/** 卡片操作分发 */
function onCardAction(kb: KnowledgeBaseWire, key: string): void {
  if (key === 'edit') {
    openEdit(kb);
    return;
  }
  void onDelete(kb);
}

/** 格式化时间为 YYYY-MM-DD HH:mm */
function formatTime(iso: string | null): string {
  if (!iso) {
    return '-';
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  const pad = (n: number): string => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

onMounted(() => {
  void refresh();
});
</script>

<template>
  <div class="dda-page">
    <PageHeader title="知识库" subtitle="RAG 知识域的文档摄入、切片、检索与问答入口" icon="folder">
      <template #actions>
        <t-button theme="primary" @click="openCreate">
          <t-icon name="add" />
          新建知识库
        </t-button>
      </template>
    </PageHeader>

    <div class="dda-statbar">
      <div class="dda-stat">
        <span class="dda-stat__value">{{ stats.totalKnowledgeBases }}</span>
        <span class="dda-stat__label">知识库</span>
      </div>
      <div class="dda-stat">
        <span class="dda-stat__value">{{ stats.totalDocuments }}</span>
        <span class="dda-stat__label">文档</span>
      </div>
      <div class="dda-stat">
        <span class="dda-stat__value">{{ stats.totalChunks }}</span>
        <span class="dda-stat__label">切片</span>
      </div>
    </div>

    <div class="dda-toolbar">
      <t-input
        v-model="keyword"
        class="dda-toolbar__grow"
        placeholder="按名称/描述搜索"
        clearable
        @enter="onSearch"
        @clear="onSearch"
      >
        <template #prefix-icon><t-icon name="search" /></template>
      </t-input>
      <t-select v-model="sortBy" style="width: 140px" :options="SORT_FIELD_OPTIONS" @change="onSortChange" />
      <t-select v-model="sortOrder" style="width: 104px" :options="ORDER_OPTIONS" @change="onSortChange" />
      <t-button variant="outline" :loading="loading" @click="refresh">
        <t-icon name="refresh" />
        刷新
      </t-button>
    </div>

    <t-loading :loading="loading" size="small">
      <div v-if="list.length === 0 && !loading" class="kb-empty dda-section">
        <EmptyGuide description="暂无知识库，先新建一个知识库开始摄入文档并构建检索问答能力" icon="folder">
          <template #action>
            <t-button theme="primary" @click="openCreate">新建知识库</t-button>
          </template>
        </EmptyGuide>
      </div>

      <div v-else class="dda-card-grid">
        <article
          v-for="kb in list"
          :key="kb.id"
          class="dda-card kb-card"
          :class="{ 'kb-card--clickable': kb.lifecycleStatus === 'ACTIVE' }"
          :role="kb.lifecycleStatus === 'ACTIVE' ? 'link' : undefined"
          :tabindex="kb.lifecycleStatus === 'ACTIVE' ? 0 : undefined"
          :aria-label="kb.lifecycleStatus === 'ACTIVE' ? `打开知识库 ${kb.name}` : undefined"
          @click="onCardClick(kb)"
          @keydown.enter="onCardClick(kb)"
        >
          <header class="kb-card__head">
            <h4 class="kb-card__name dda-ellipsis" :title="kb.name">{{ kb.name }}</h4>
            <StatusTag :code="kb.lifecycleStatus" :dict="KB_LIFECYCLE" />
          </header>

          <p class="kb-card__desc">{{ kb.description || '（暂无描述）' }}</p>

          <p v-if="kb.lifecycleStatus === 'DELETE_FAILED' && kb.errorMessage" class="kb-card__error">
            {{ kb.errorMessage }}
          </p>

          <footer class="kb-card__foot">
            <span class="kb-card__time dda-faint">更新于 {{ formatTime(kb.updatedAt) }}</span>
          </footer>

          <div class="dda-card__actions kb-card__actions" @click.stop>
            <RowMenu :items="cardActions(kb)" @select="(key: string) => onCardAction(kb, key)" />
          </div>
        </article>
      </div>
    </t-loading>

    <div v-if="pager.total.value > 0" class="kb-pagination">
      <t-pagination
        :current="pager.page.value"
        :page-size="pager.size.value"
        :total="pager.total.value"
        :page-size-options="[]"
        show-jumper
        @change="(ctx: { current: number }) => onPageChange(ctx.current)"
      />
    </div>

    <KbFormDrawer v-model="drawerVisible" :kb="editingKb" @saved="onSaved" />
  </div>
</template>

<style scoped>
.kb-card {
  gap: 10px;
  min-height: 168px;
}

.kb-card--clickable {
  cursor: pointer;
}

.kb-card__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.kb-card__name {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--td-text-color-primary);
}

.kb-card__desc {
  display: -webkit-box;
  flex: 1;
  min-height: 40px;
  margin: 0;
  overflow: hidden;
  font-size: 13px;
  color: var(--td-text-color-secondary);
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.kb-card__error {
  margin: 0;
  padding: 6px 10px;
  font-size: 12px;
  color: var(--td-error-color);
  background: var(--td-error-color-1);
  border-radius: var(--td-radius-small);
}

.kb-card__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.kb-card__time {
  font-size: 12px;
}

/* 操作区悬浮在卡片右下角，避免挤占内容高度 */
.kb-card__actions {
  position: absolute;
  right: 12px;
  bottom: 12px;
  padding: 2px 4px;
  background: var(--td-bg-color-container);
  border-radius: var(--td-radius-round);
  box-shadow: var(--td-shadow-1);
}

.kb-pagination {
  display: flex;
  justify-content: center;
  margin-top: 24px;
}

.kb-empty {
  padding: 0;
}
</style>