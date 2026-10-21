<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import StatusTag from '@/shared/components/StatusTag.vue';
import EmptyGuide from '@/shared/components/EmptyGuide.vue';
import { downloadFile } from '@/shared/api/blob';
import { DOC_ACTIVE_STATUSES, DOC_STATUS } from '@/shared/enums/dict';
import { errorMessage } from '@/shared/composables/useAsync';
import { usePagePagination } from '@/shared/composables/usePagination';
import { usePolling } from '@/shared/composables/usePolling';
import { confirmDangerous } from '@/shared/composables/useConfirm';
import { compactTime } from '@/shared/utils/time';
import {
  deleteDocument,
  documentDownloadPath,
  listDocuments,
  reparseDocument,
} from '../../api/documents';
import type { DocumentWire } from '../../api/documents';
import {
  chunkModeLabel,
  describeDocChunkStrategy,
  parseChunkStrategy,
} from '../../mappers.documents';
import UploadDialog from './UploadDialog.vue';
import DocReaderDialog from './DocReaderDialog.vue';
import DocChunkStrategyDialog from './DocChunkStrategyDialog.vue';

/**
 * 知识库文档管理表格（列表 / 筛选 / 分页 / 解析状态轮询 / 生命周期操作 / 文档级分块策略）。
 * <p>行操作：在线阅读（鉴权预览对话框）、下载（attachment）、重新解析（二次确认清切片语义）、
 * 删除（异步受理 → DELETING → 轮询收敛）。存在 PENDING/PROCESSING/DELETING 文档时按 4s 轮询刷新。</p>
 * <p>「分块策略」列展示文档生效策略模式名（未设置=继承库级），点击打开策略弹窗；
 * 弹窗确认 = 保存并重新解析（带 chunkStrategyOverride），二次确认清切片语义后发起。
 * 行内普通重解析同样回传文档已存策略作为覆盖值，防止后端把文档策略静默刷回库级快照。</p>
 */
const props = defineProps<{
  /** 所属知识库ID */
  kbId: number;
  /** 库级分块策略 JSON 原文（继承态展示与弹窗回显默认值；不可用时为 null） */
  kbChunkStrategy?: string | null;
}>();

/** 列表数据。 */
const list = ref<DocumentWire[]>([]);
/** 顶层加载态。 */
const loading = ref(false);
/** 行操作中集合（重解析/删除按 ID 记录，用于按钮禁用）。 */
const operatingIds = reactive(new Set<number>());
/** 勾选集合（列表勾选列，预留批量入口）。 */
const selectedIds = reactive(new Set<number>());

/** 筛选条件。 */
const fileNameKeyword = ref('');
const statusFilter = ref('');

/** 分页（文档列表默认每页 10）。 */
const pager = usePagePagination<DocumentWire>(10);

/** 状态筛选选项（全部 + 六态）。 */
const statusOptions = [
  { label: '全部状态', value: '' },
  ...Object.entries(DOC_STATUS).map(([value, item]) => ({ label: item.label, value })),
];

/** 表格列定义（勾选列 + 文件名/状态/切片数/分块策略/大小/类型/上传时间/操作）。 */
const columns = [
  { colKey: 'selection', title: '', width: 46, cell: 'selection' },
  { colKey: 'fileName', title: '文件名', width: 210, ellipsis: true, cell: 'fileName' },
  { colKey: 'status', title: '状态', width: 120, cell: 'status' },
  { colKey: 'chunkCount', title: '切片数', width: 80, cell: 'chunkCount' },
  { colKey: 'chunkStrategy', title: '分块策略', width: 130, ellipsis: true, cell: 'chunkStrategy' },
  { colKey: 'fileSize', title: '大小', width: 90, cell: 'fileSize' },
  { colKey: 'fileType', title: '类型', width: 80, cell: 'fileType' },
  { colKey: 'createdAt', title: '上传时间', width: 140, cell: 'createdAt' },
  { colKey: 'action', title: '操作', width: 240, cell: 'action' },
];

/** 上传对话框。 */
const uploadVisible = ref(false);
/** 阅读对话框。 */
const readerVisible = ref(false);
const readerDoc = ref<DocumentWire | null>(null);
/** 分块策略编辑对话框。 */
const strategyVisible = ref(false);
/** 当前编辑策略的目标文档行。 */
const strategyRow = ref<DocumentWire | null>(null);

/** 是否存在处理中/删除中文档（轮询触发条件）。 */
function hasActive(items: DocumentWire[]): boolean {
  return items.some((item) => DOC_ACTIVE_STATUSES.includes(String(item.status)));
}

/** 加载一页列表（供首屏、翻页、筛选、轮询复用），返回本页数据用于轮询判定。 */
async function loadList(): Promise<DocumentWire[]> {
  const resp = await listDocuments({
    kbId: props.kbId,
    fileName: fileNameKeyword.value.trim() || undefined,
    status: statusFilter.value || undefined,
    page: pager.page.value,
    size: pager.size.value,
  });
  const items = pager.apply(resp);
  list.value = items;
  return items;
}

/** 条件轮询：仍有处理中/删除中文档则继续，全部收敛后自动停止。 */
const polling = usePolling(async () => {
  const items = await loadList();
  return hasActive(items);
}, 4000);

/** 刷新列表并在存在在途文档时启动轮询。 */
async function refresh(): Promise<void> {
  loading.value = true;
  try {
    const items = await loadList();
    if (hasActive(items)) {
      polling.start();
    }
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
  } finally {
    loading.value = false;
  }
}

/** 文件名关键字搜索（回车/清空触发）。 */
function onSearch(): void {
  pager.reset();
  void refresh();
}

/** 状态筛选变更。 */
function onFilterChange(): void {
  pager.reset();
  void refresh();
}

/** 翻页。 */
function onPageChange(ctx: { current: number; pageSize: number }): void {
  pager.page.value = ctx.current;
  pager.size.value = ctx.pageSize;
  void refresh();
}

/** 切换行勾选。 */
function toggleSelect(id: number): void {
  if (selectedIds.has(id)) {
    selectedIds.delete(id);
  } else {
    selectedIds.add(id);
  }
}

/** 打开阅读对话框。 */
function onRead(doc: DocumentWire): void {
  readerDoc.value = doc;
  readerVisible.value = true;
}

/** 下载原文。 */
async function onDownload(doc: DocumentWire): Promise<void> {
  try {
    await downloadFile(documentDownloadPath(doc.id), doc.fileName);
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
  }
}

/** 重新解析禁用条件：排队中/解析中行禁止重复触发。 */
function reparseDisabled(doc: DocumentWire): boolean {
  return doc.status === 'PENDING' || doc.status === 'PROCESSING';
}

/**
 * 重新解析：二次确认「清空全部切片（含手工）」语义 → 提交 → 刷新并按需轮询。
 * <p>文档已存有自身策略时 MUST 以该策略为覆盖值发起（design D2）：后端在不带覆盖值时
 * 回退读库级快照并覆写文档策略，不回传会把用户此前保存的文档级策略静默刷回库级。</p>
 */
async function onReparse(doc: DocumentWire): Promise<void> {
  const confirmed = await confirmDangerous({
    header: '重新解析文档',
    body: `即将重新解析「${doc.fileName}」。解析成功后将清空该文档全部切片（含手工切片）并按解析结果重建，此操作不可撤销，确认继续？`,
    theme: 'warning',
    confirmText: '重新解析',
  });
  if (!confirmed) {
    return;
  }
  operatingIds.add(doc.id);
  try {
    await reparseDocument(doc.id, doc.chunkStrategy ? { chunkStrategyOverride: doc.chunkStrategy } : undefined);
    MessagePlugin.success('已发起重新解析');
    await refresh();
  } catch (e) {
    // PROCESSING 重复触发 / 删除链冲突为后端 409，直显原因
    MessagePlugin.error(errorMessage(e));
  } finally {
    operatingIds.delete(doc.id);
  }
}

/** 分块策略列点击：打开策略编辑弹窗（行操作在途或删除中的文档不允许修改）。 */
function onStrategyClick(doc: DocumentWire): void {
  if (operatingIds.has(doc.id) || doc.status === 'DELETING') {
    return;
  }
  strategyRow.value = doc;
  strategyVisible.value = true;
}

/**
 * 策略弹窗确认 = 保存并重新解析：新策略经 chunkStrategyOverride 随重解析一次落库生效；
 * 「恢复为继承库级」（null）则不带覆盖值发起（后端回退库级快照）。执行前二次确认清切片语义。
 */
async function onStrategyConfirm(strategyJson: string | null): Promise<void> {
  const doc = strategyRow.value;
  if (!doc) {
    return;
  }
  const restoreInherit = strategyJson === null;
  const modeName = restoreInherit ? '' : chunkModeLabel(parseChunkStrategy(strategyJson).chunkMode);
  const confirmed = await confirmDangerous({
    header: restoreInherit ? '恢复继承库级分块策略' : '保存分块策略并重新解析',
    body: restoreInherit
      ? `即将把文档「${doc.fileName}」恢复为继承知识库级分块策略并立即重新解析。解析成功后将清空该文档全部切片（含手工切片）并按库级策略重建，此操作不可撤销，确认继续？`
      : `即将为文档「${doc.fileName}」保存分块策略「${modeName}」并立即重新解析。解析成功后将清空该文档全部切片（含手工切片）并按新策略重建，此操作不可撤销，确认继续？`,
    theme: 'warning',
    confirmText: '保存并重新解析',
  });
  if (!confirmed) {
    return;
  }
  operatingIds.add(doc.id);
  try {
    await reparseDocument(doc.id, restoreInherit ? undefined : { chunkStrategyOverride: strategyJson });
    MessagePlugin.success(restoreInherit ? '已恢复继承库级策略，已发起重新解析' : `已保存策略「${modeName}」，已发起重新解析`);
    await refresh();
  } catch (e) {
    // 后端值域 400 / PROCESSING 与删除链冲突 409：直显原因
    MessagePlugin.error(errorMessage(e));
  } finally {
    operatingIds.delete(doc.id);
  }
}

/** 删除文档：二次确认 → 异步受理（code=202）→ 乐观置删除中并启动轮询。 */
async function onDelete(doc: DocumentWire): Promise<void> {
  const confirmed = await confirmDangerous({
    header: '删除文档',
    body: `确认删除文档「${doc.fileName}」？该操作不可逆，将异步清退其全部切片。`,
    theme: 'danger',
    confirmText: '删除',
  });
  if (!confirmed) {
    return;
  }
  operatingIds.add(doc.id);
  try {
    const { accepted } = await deleteDocument(doc.id);
    if (accepted) {
      doc.status = 'DELETING';
      polling.start();
    } else {
      MessagePlugin.warning('删除请求未被受理，请稍后重试');
      await refresh();
    }
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
  } finally {
    operatingIds.delete(doc.id);
  }
}

/** 上传完成回调：刷新并启动轮询。 */
function onUploaded(): void {
  pager.reset();
  void refresh();
}

/** 人性化文件大小。 */
function humanSize(bytes: number | null): string {
  if (bytes === null || bytes === undefined) {
    return '-';
  }
  if (bytes >= 1024 * 1024) {
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  }
  if (bytes >= 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  return `${bytes} B`;
}

onMounted(() => {
  void refresh();
});
</script>

<template>
  <div class="doc-table">
    <div class="doc-table__toolbar">
      <t-input
        v-model="fileNameKeyword"
        class="doc-table__search"
        placeholder="按文件名搜索"
        clearable
        @enter="onSearch"
        @clear="onSearch"
      />
      <t-select
        v-model="statusFilter"
        class="doc-table__status"
        :options="statusOptions"
        placeholder="全部状态"
        @change="onFilterChange"
      />
      <t-button theme="primary" @click="uploadVisible = true">上传文档</t-button>
    </div>

    <t-table
      row-key="id"
      :data="list"
      :columns="columns"
      :loading="loading"
      bordered
      hover
      size="medium"
      table-layout="fixed"
      max-height="max(420px, calc(100vh - 420px))"
    >
      <template #selection="{ row }">
        <t-checkbox :checked="selectedIds.has(row.id)" @change="toggleSelect(row.id)" />
      </template>

      <template #fileName="{ row }">
        <t-link theme="primary" hover="color" @click="onRead(row)">{{ row.fileName }}</t-link>
      </template>

      <template #status="{ row }">
        <t-tooltip
          v-if="row.status === 'FAILED' && row.errorMessage"
          :content="row.errorMessage"
          placement="top"
        >
          <StatusTag :code="row.status" :dict="DOC_STATUS" />
        </t-tooltip>
        <StatusTag v-else :code="row.status" :dict="DOC_STATUS" />
      </template>

      <template #chunkCount="{ row }">{{ row.chunkCount ?? 0 }}</template>

      <template #chunkStrategy="{ row }">
        <t-link
          theme="primary"
          hover="color"
          :disabled="reparseDisabled(row) || operatingIds.has(row.id) || row.status === 'DELETING'"
          @click="onStrategyClick(row)"
        >
          {{ describeDocChunkStrategy(row.chunkStrategy, props.kbChunkStrategy) }}
        </t-link>
      </template>

      <template #fileSize="{ row }">{{ humanSize(row.fileSize) }}</template>
      <template #fileType="{ row }">{{ row.fileType }}</template>
      <template #createdAt="{ row }">{{ compactTime(row.createdAt) }}</template>

      <template #action="{ row }">
        <t-space size="small">
          <t-link theme="primary" hover="color" @click="onRead(row)">阅读</t-link>
          <t-link theme="primary" hover="color" @click="onDownload(row)">下载</t-link>
          <t-link
            theme="primary"
            hover="color"
            :disabled="reparseDisabled(row) || operatingIds.has(row.id)"
            @click="onReparse(row)"
          >
            重解析
          </t-link>
          <t-link
            theme="danger"
            hover="color"
            :disabled="row.status === 'DELETING' || operatingIds.has(row.id)"
            @click="onDelete(row)"
          >
            删除
          </t-link>
        </t-space>
      </template>

      <template #empty>
        <EmptyGuide description="该知识库还没有文档，先上传文件开始摄入">
          <template #action>
            <t-button theme="primary" size="small" @click="uploadVisible = true">上传文档</t-button>
          </template>
        </EmptyGuide>
      </template>
    </t-table>

    <div v-if="pager.total.value > 0" class="doc-table__pager">
      <t-pagination
        :current="pager.page.value"
        :page-size="pager.size.value"
        :total="pager.total.value"
        :page-size-options="[10, 20, 50, 100]"
        :show-jumper="false"
        @change="onPageChange"
      />
    </div>

    <UploadDialog
      v-model="uploadVisible"
      :kb-id="kbId"
      :kb-chunk-strategy="kbChunkStrategy"
      @uploaded="onUploaded"
    />
    <DocReaderDialog v-model="readerVisible" :document="readerDoc" />
    <DocChunkStrategyDialog
      v-model="strategyVisible"
      :initial-strategy="strategyRow?.chunkStrategy ?? kbChunkStrategy ?? null"
      :file-type="strategyRow?.fileType ?? ''"
      mode="edit"
      @confirm="onStrategyConfirm"
    />
  </div>
</template>

<style scoped>
.doc-table__toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 16px;
}

.doc-table__search {
  flex: 0 0 240px;
}

.doc-table__status {
  flex: 0 0 160px;
}

.doc-table__pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
