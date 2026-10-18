<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import StatusTag from '@/shared/components/StatusTag.vue';
import EmptyGuide from '@/shared/components/EmptyGuide.vue';
import { CHUNK_CONTENT_TYPES, CHUNK_SOURCES } from '@/shared/enums/dict';
import { errorMessage } from '@/shared/composables/useAsync';
import { usePagePagination } from '@/shared/composables/usePagination';
import { confirmDangerous } from '@/shared/composables/useConfirm';
import { listDocuments } from '../../api/documents';
import { batchDeleteChunks, deleteChunk, listChunks } from '../../api/chunks';
import type { ChunkWire } from '../../api/chunks';
import ChunkEditorDrawer from './ChunkEditorDrawer.vue';

/**
 * 知识库切片管理面板（筛选 + 卡片/表格双视图 + 分页 + 单条/批量删除 + 新增）。
 * <p>筛选支持文档、切片序号、内容关键字；批量删除前端拦截：超 500 拒绝提交、混入 PARSED 拒绝发起
 * 请求并给出引导；PARSED 切片单条删除入口禁用/引导，仅 MANUAL 可删。新增/编辑/查看复用编辑抽屉。</p>
 */
const props = defineProps<{
  /** 所属知识库ID */
  kbId: number;
}>();

/** 单次批量删除上限（对齐后端批次口径）。 */
const BATCH_DELETE_MAX = 500;

/** PARSED 单条/批量删除引导文案（与后端闸门文案一致）。 */
const PARSED_DELETE_GUIDE =
  '解析产生的切片不支持单独删除（其图谱账本贡献会失效），请通过「重新解析」整篇重建或删除整个文档来处理';

/** 列表数据。 */
const list = ref<ChunkWire[]>([]);
const loading = ref(false);
const batchDeleting = ref(false);

/** 视图切换。 */
const viewMode = ref<'card' | 'table'>('card');

/** 筛选条件。 */
const documentIdFilter = ref<number | null>(null);
const sequenceFilter = ref<number | null>(null);
const keyword = ref('');

/** 文档下拉选项（供筛选与新增切片挂载）。 */
const documentOptions = ref<Array<{ value: number; label: string }>>([]);

/** 选中集合（当前页）。 */
const selectedIds = reactive(new Set<number>());

/** 分页（切片默认每页 20）。 */
const pager = usePagePagination<ChunkWire>(20);

/** 抽屉状态。 */
const drawerVisible = ref(false);
const drawerMode = ref<'create' | 'edit' | 'view'>('create');
const editingChunk = ref<ChunkWire | null>(null);

/** 表格列定义（切片内容摘要与更新时间经插槽渲染）。 */
const columns = [
  { colKey: 'selection', title: '', width: 46, cell: 'selection' },
  { colKey: 'sequence', title: '#序号', width: 80, cell: 'sequence' },
  { colKey: 'id', title: 'ID', width: 90 },
  { colKey: 'chunkContent', title: '内容摘要', cell: 'chunkContent', minWidth: 240 },
  { colKey: 'chunkContentType', title: '形态', width: 80, cell: 'chunkContentType' },
  { colKey: 'sourceFileName', title: '来源文件', width: 160, ellipsis: true },
  { colKey: 'sourceType', title: '来源', width: 80, cell: 'sourceType' },
  { colKey: 'tokens', title: 'tokens', width: 80, cell: 'tokens' },
  { colKey: 'updatedAt', title: '更新时间', width: 150, cell: 'updatedAt' },
  { colKey: 'action', title: '操作', width: 180, cell: 'action' },
];

/** 当前选中且仍在列表中的切片。 */
const selectedChunks = computed<ChunkWire[]>(() => list.value.filter((item) => selectedIds.has(item.id)));

/** 当前页是否已全选。 */
const allSelected = computed(() => list.value.length > 0 && list.value.every((item) => selectedIds.has(item.id)));

/** 加载文档下拉（size 上限 100，管理端量级可接受）。 */
async function loadDocuments(): Promise<void> {
  try {
    const resp = await listDocuments({ kbId: props.kbId, page: 1, size: 100 });
    documentOptions.value = resp.list.map((doc) => ({ value: doc.id, label: doc.fileName }));
  } catch (e) {
    MessagePlugin.error(`文档列表加载失败：${errorMessage(e)}`);
  }
}

/** 加载一页切片列表。 */
async function loadList(): Promise<void> {
  const resp = await listChunks({
    kbId: props.kbId,
    documentId: documentIdFilter.value ?? undefined,
    sequence: sequenceFilter.value ?? undefined,
    keyword: keyword.value.trim() || undefined,
    page: pager.page.value,
    size: pager.size.value,
  });
  list.value = pager.apply(resp);
  // 翻页/重查后清空跨页选中，避免陈旧 ID 影响 PARSED 混批判定
  selectedIds.clear();
}

/** 刷新（含错误提示）。 */
async function refresh(): Promise<void> {
  loading.value = true;
  try {
    await loadList();
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
  } finally {
    loading.value = false;
  }
}

/** 筛选/搜索：回到第一页重查。 */
function onSearch(): void {
  pager.reset();
  void refresh();
}

/** 翻页。 */
function onPageChange(ctx: { current: number; pageSize: number }): void {
  pager.page.value = ctx.current;
  pager.size.value = ctx.pageSize;
  void refresh();
}

/** 切换视图。 */
function onModeChange(value: 'card' | 'table'): void {
  viewMode.value = value;
}

/** 切换行选中。 */
function toggleSelect(id: number): void {
  if (selectedIds.has(id)) {
    selectedIds.delete(id);
  } else {
    selectedIds.add(id);
  }
}

/** 全选/取消全选当前页。 */
function toggleSelectAll(): void {
  if (allSelected.value) {
    list.value.forEach((item) => selectedIds.delete(item.id));
  } else {
    list.value.forEach((item) => selectedIds.add(item.id));
  }
}

/** 打开查看抽屉。 */
function onView(chunk: ChunkWire): void {
  editingChunk.value = chunk;
  drawerMode.value = 'view';
  drawerVisible.value = true;
}

/** 打开编辑抽屉。 */
function onEdit(chunk: ChunkWire): void {
  editingChunk.value = chunk;
  drawerMode.value = 'edit';
  drawerVisible.value = true;
}

/** 打开新增抽屉。 */
function onCreate(): void {
  editingChunk.value = null;
  drawerMode.value = 'create';
  drawerVisible.value = true;
}

/** 抽屉保存/删除后刷新列表。 */
function onDrawerSaved(): void {
  void refresh();
}

/** 单条删除：PARSED 前端拦截给引导且不发请求；MANUAL 二次确认后删除。 */
async function onDeleteOne(chunk: ChunkWire): Promise<void> {
  if (chunk.sourceType === 'PARSED') {
    MessagePlugin.warning(PARSED_DELETE_GUIDE);
    return;
  }
  const confirmed = await confirmDangerous({
    header: '删除手工切片',
    body: `确认删除该手工切片（#${chunk.sequence ?? '-'} / ID ${chunk.id}）？该操作不可逆。`,
    theme: 'danger',
    confirmText: '删除',
  });
  if (!confirmed) {
    return;
  }
  try {
    await deleteChunk(chunk.id);
    MessagePlugin.success('切片已删除');
    void refresh();
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
  }
}

/** 批量删除：前端拦截超量与 PARSED 混批，通过校验后二次确认提交。 */
async function onBatchDelete(): Promise<void> {
  const targets = selectedChunks.value;
  if (targets.length === 0) {
    MessagePlugin.warning('请先勾选要删除的切片');
    return;
  }
  if (targets.length > BATCH_DELETE_MAX) {
    MessagePlugin.warning(`单次最多删除 ${BATCH_DELETE_MAX} 个切片，请分批操作`);
    return;
  }
  if (targets.some((item) => item.sourceType === 'PARSED')) {
    MessagePlugin.warning(PARSED_DELETE_GUIDE);
    return;
  }
  const confirmed = await confirmDangerous({
    header: '批量删除手工切片',
    body: `确认删除选中的 ${targets.length} 个手工切片？该操作不可逆。`,
    theme: 'danger',
    confirmText: '删除',
  });
  if (!confirmed) {
    return;
  }
  batchDeleting.value = true;
  try {
    await batchDeleteChunks(targets.map((item) => item.id));
    MessagePlugin.success(`已删除 ${targets.length} 个切片`);
    selectedIds.clear();
    await refresh();
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
  } finally {
    batchDeleting.value = false;
  }
}

/** 内容摘要（截断至 200 字）。 */
function abstract(text: string): string {
  return text.length > 200 ? `${text.slice(0, 200)}…` : text;
}

/** 人性化时间到分钟。 */
function toMinute(value: string | null): string {
  if (!value) {
    return '-';
  }
  return value.slice(0, 16).replace('T', ' ');
}

onMounted(() => {
  void loadDocuments();
  void refresh();
});
</script>

<template>
  <div class="chunk-panel">
    <div class="chunk-panel__toolbar">
      <t-select
        v-model="documentIdFilter"
        class="chunk-panel__doc"
        :options="documentOptions"
        placeholder="按文档筛选"
        clearable
        filterable
        @change="onSearch"
      />
      <t-input-number
        v-model="sequenceFilter"
        class="chunk-panel__seq"
        :min="0"
        :decimal-places="0"
        theme="column"
        placeholder="切片序号"
      />
      <t-input
        v-model="keyword"
        class="chunk-panel__keyword"
        placeholder="按切片内容搜索"
        clearable
        @enter="onSearch"
        @clear="onSearch"
      />
      <t-button theme="primary" @click="onSearch">查询</t-button>
      <div class="chunk-panel__spacer" />
      <t-radio-group :value="viewMode" variant="default-filled" size="small" @change="(v: string | number | boolean) => onModeChange(v as 'card' | 'table')">
        <t-radio-button value="card">卡片</t-radio-button>
        <t-radio-button value="table">表格</t-radio-button>
      </t-radio-group>
      <t-button theme="primary" @click="onCreate">新增切片</t-button>
    </div>

    <div v-if="viewMode === 'table' || selectedIds.size > 0" class="chunk-panel__bulk">
      <t-checkbox :checked="allSelected" :disabled="list.length === 0" @change="toggleSelectAll">全选本页</t-checkbox>
      <span class="chunk-panel__count">已选 {{ selectedChunks.length }} 项</span>
      <t-button
        size="small"
        theme="danger"
        variant="text"
        :loading="batchDeleting"
        :disabled="selectedChunks.length === 0"
        @click="onBatchDelete"
      >
        批量删除
      </t-button>
    </div>

    <t-loading :loading="loading" size="small">
      <!-- 表格视图 -->
      <t-table
        v-if="viewMode === 'table'"
        row-key="id"
        :data="list"
        :columns="columns"
        bordered
        hover
        size="medium"
        table-layout="auto"
        max-height="max(460px, calc(100vh - 460px))"
      >
        <template #selection="{ row }">
          <t-checkbox :checked="selectedIds.has(row.id)" @change="toggleSelect(row.id)" />
        </template>
        <template #sequence="{ row }">#{{ row.sequence }}</template>
        <template #chunkContent="{ row }">{{ abstract(row.chunkContent) }}</template>
        <template #chunkContentType="{ row }">
          <StatusTag :code="row.chunkContentType || 'TEXT'" :dict="CHUNK_CONTENT_TYPES" />
        </template>
        <template #sourceType="{ row }">
          <StatusTag :code="row.sourceType" :dict="CHUNK_SOURCES" />
        </template>
        <template #tokens="{ row }">{{ row.tokens ?? '-' }}</template>
        <template #updatedAt="{ row }">{{ toMinute(row.updatedAt) }}</template>
        <template #action="{ row }">
          <t-space size="small">
            <t-link theme="primary" hover="color" @click="onView(row)">查看</t-link>
            <t-link theme="primary" hover="color" @click="onEdit(row)">编辑</t-link>
            <t-tooltip v-if="row.sourceType === 'PARSED'" :content="PARSED_DELETE_GUIDE" placement="top">
              <t-link theme="default" disabled>删除</t-link>
            </t-tooltip>
            <t-link v-else theme="danger" hover="color" @click="onDeleteOne(row)">删除</t-link>
          </t-space>
        </template>
        <template #empty>
          <EmptyGuide description="暂无切片，先解析文档或手工新增切片" />
        </template>
      </t-table>

      <!-- 卡片视图 -->
      <div v-else-if="list.length > 0" class="chunk-grid">
        <t-card v-for="chunk in list" :key="chunk.id" class="chunk-card" :bordered="true" hover-shadow>
          <div class="chunk-card__head">
            <t-checkbox :checked="selectedIds.has(chunk.id)" @change="toggleSelect(chunk.id)" />
            <span class="chunk-card__seq">#{{ chunk.sequence }}</span>
            <span class="chunk-card__id">ID {{ chunk.id }}</span>
            <StatusTag :code="chunk.sourceType" :dict="CHUNK_SOURCES" />
            <StatusTag :code="chunk.chunkContentType || 'TEXT'" :dict="CHUNK_CONTENT_TYPES" />
          </div>
          <p class="chunk-card__content">{{ abstract(chunk.chunkContent) }}</p>
          <div class="chunk-card__foot">
            <span class="chunk-card__meta">来源：{{ chunk.sourceFileName || '-' }}</span>
            <span class="chunk-card__meta">tokens：{{ chunk.tokens ?? '-' }}</span>
            <span class="chunk-card__meta">更新：{{ toMinute(chunk.updatedAt) }}</span>
          </div>
          <div class="chunk-card__ops">
            <t-button size="small" variant="text" theme="primary" @click="onView(chunk)">查看</t-button>
            <t-button size="small" variant="text" @click="onEdit(chunk)">编辑</t-button>
            <t-tooltip v-if="chunk.sourceType === 'PARSED'" :content="PARSED_DELETE_GUIDE" placement="top">
              <t-button size="small" variant="text" disabled>删除</t-button>
            </t-tooltip>
            <t-button v-else size="small" variant="text" theme="danger" @click="onDeleteOne(chunk)">删除</t-button>
          </div>
        </t-card>
      </div>

      <EmptyGuide v-else description="暂无切片，先解析文档或手工新增切片" />
    </t-loading>

    <div v-if="pager.total.value > 0" class="chunk-panel__pager">
      <t-pagination
        :current="pager.page.value"
        :page-size="pager.size.value"
        :total="pager.total.value"
        :page-size-options="[20, 50, 100]"
        :show-jumper="false"
        @change="onPageChange"
      />
    </div>

    <ChunkEditorDrawer
      v-model="drawerVisible"
      :kb-id="kbId"
      :mode="drawerMode"
      :chunk="editingChunk"
      :document-options="documentOptions"
      @saved="onDrawerSaved"
    />
  </div>
</template>

<style scoped>
.chunk-panel__toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  margin-bottom: 12px;
}

.chunk-panel__doc {
  flex: 0 0 220px;
}

.chunk-panel__seq {
  flex: 0 0 130px;
}

.chunk-panel__keyword {
  flex: 0 0 220px;
}

.chunk-panel__spacer {
  flex: 1 1 auto;
}

.chunk-panel__bulk {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  padding: 8px 12px;
  font-size: 13px;
  background: var(--td-bg-color-secondarycontainer, #f3f3f3);
  border-radius: 4px;
}

.chunk-panel__count {
  color: var(--td-text-color-secondary, rgba(0, 0, 0, 0.6));
}

.chunk-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 16px;
}

.chunk-card__head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.chunk-card__seq {
  font-weight: 600;
}

.chunk-card__id {
  font-size: 12px;
  color: var(--td-text-color-placeholder, rgba(0, 0, 0, 0.4));
}

.chunk-card__content {
  min-height: 48px;
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--td-text-color-secondary, rgba(0, 0, 0, 0.6));
  word-break: break-all;
}

.chunk-card__foot {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  font-size: 12px;
  color: var(--td-text-color-placeholder, rgba(0, 0, 0, 0.4));
}

.chunk-card__ops {
  display: flex;
  justify-content: flex-end;
  gap: 2px;
  margin-top: 8px;
}

.chunk-panel__pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
