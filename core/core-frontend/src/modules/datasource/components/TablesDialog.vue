<script setup lang="ts">
import { reactive, ref, watch } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import { listColumns, listTables, updateColumnComment, updateTableComment } from '@/modules/datasource/api/metadata';
import type { ColumnInfoResponse, TableInfoResponse } from '@/modules/datasource/api/metadata';
import type { DatasourceResponse } from '@/modules/datasource/api/datasource';
import { usePagePagination } from '@/shared/composables/usePagination';
import { errorMessage } from '@/shared/composables/useAsync';
import { commentRemaining, MAX_COMMENT_LENGTH, validateComment } from '@/modules/datasource/mappers';
import { LAYER } from '@/shared/constants/layers';
import EmptyGuide from '@/shared/components/EmptyGuide.vue';
import PreviewDialog from './PreviewDialog.vue';

/**
 * 元数据浏览弹窗：表列表（关键字搜索 + 50/页），行操作预览 / 表注释 / 字段；
 * 字段视图保持原始顺序并支持列注释行内编辑；表注释经 CommentEditor 弹窗（0/150 计数）。
 * 居中弹窗形态，表格限高滚动，避免超出视口。
 *
 * 层级：本弹窗为 {@link LAYER.dialog}；其内的表注释 / 字段列表 / 数据预览均为**嵌套弹窗**，
 * 必须显式抬到 {@link LAYER.dialogNested}，否则与父弹窗同为 2500 会因 DOM 顺序被父弹窗盖住。
 */
const props = defineProps<{
  visible: boolean;
  datasource: DatasourceResponse | null;
}>();

const emit = defineEmits<{ 'update:visible': [boolean] }>();

const keyword = ref('');
const tables = ref<TableInfoResponse[]>([]);
const loading = ref(false);
const pager = usePagePagination<TableInfoResponse>(50);

// 预览
const previewVisible = ref(false);
const previewTable = ref('');

// 表注释弹窗（CommentEditor）
const commentVisible = ref(false);
const commentTarget = ref<TableInfoResponse | null>(null);
const commentDraft = ref('');

// 字段视图
const columnsVisible = ref(false);
const columnsTable = ref<TableInfoResponse | null>(null);
const columns = ref<ColumnInfoResponse[]>([]);
const columnsLoading = ref(false);
/** 列注释行内编辑缓冲：key=字段 id，value=编辑中的自定义注释。 */
const columnEdits = reactive<Record<number, string>>({});

async function loadTables(): Promise<void> {
  if (!props.datasource) {
    return;
  }
  loading.value = true;
  try {
    const resp = await listTables(props.datasource.id, { keyword: keyword.value || undefined, ...pager.params() });
    tables.value = pager.apply(resp);
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
    tables.value = [];
  } finally {
    loading.value = false;
  }
}

function search(): void {
  pager.reset();
  void loadTables();
}

function onPageChange(page: number): void {
  pager.page.value = page;
  void loadTables();
}

function onSizeChange(size: number): void {
  pager.size.value = size;
  pager.reset();
  void loadTables();
}

// ==================== 预览 ====================
function openPreview(row: TableInfoResponse): void {
  previewTable.value = row.tableName;
  previewVisible.value = true;
}

// ==================== 表注释 ====================
function openTableComment(row: TableInfoResponse): void {
  commentTarget.value = row;
  commentDraft.value = row.tableCustomComment ?? '';
  commentVisible.value = true;
}

const commentError = ref('');

async function saveTableComment(): Promise<void> {
  if (!commentTarget.value) {
    return;
  }
  commentError.value = validateComment(commentDraft.value);
  if (commentError.value) {
    return;
  }
  try {
    await updateTableComment(commentTarget.value.id, commentDraft.value);
    MessagePlugin.success('表注释已保存');
    commentVisible.value = false;
    const found = tables.value.find((t) => t.id === commentTarget.value?.id);
    if (found) {
      found.tableCustomComment = commentDraft.value;
    }
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
  }
}

// ==================== 字段视图 ====================
async function openColumns(row: TableInfoResponse): Promise<void> {
  columnsTable.value = row;
  columnsVisible.value = true;
  columnsLoading.value = true;
  columns.value = [];
  for (const key of Object.keys(columnEdits)) {
    delete columnEdits[Number(key)];
  }
  try {
    columns.value = await listColumns(row.id);
    for (const col of columns.value) {
      columnEdits[col.id] = col.columnCustomComment ?? '';
    }
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
  } finally {
    columnsLoading.value = false;
  }
}

async function saveColumnComment(col: ColumnInfoResponse): Promise<void> {
  const value = columnEdits[col.id] ?? '';
  const err = validateComment(value);
  if (err) {
    MessagePlugin.warning(err);
    return;
  }
  try {
    await updateColumnComment(col.id, value);
    col.columnCustomComment = value;
    MessagePlugin.success('字段注释已保存');
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
  }
}

watch(
  () => props.visible,
  (open) => {
    if (open) {
      keyword.value = '';
      pager.reset();
      void loadTables();
    }
  },
);
</script>

<template>
  <t-dialog
    :visible="visible"
    :header="datasource ? `元数据浏览：${datasource.name}` : '元数据浏览'"
    width="1000px"
    placement="center"
    attach="body"
    :z-index="LAYER.dialog"
    :footer="false"
    @update:visible="(v: boolean) => emit('update:visible', v)"
  >
    <div class="dda-tables__toolbar">
      <t-input v-model="keyword" placeholder="按表名搜索" clearable style="max-width: 280px" @enter="search" />
      <t-button theme="primary" @click="search">搜索</t-button>
    </div>

    <div class="dda-table">
      <t-table
        :data="tables"
        :loading="loading"
        row-key="id"
        size="medium"
        hover
        max-height="480"
        :columns="[
          { colKey: 'tableName', title: '表名', width: 220, ellipsis: true },
          { colKey: 'tableComment', title: '原始注释', ellipsis: true },
          { colKey: 'tableCustomComment', title: '自定义注释', ellipsis: true },
          { colKey: 'op', title: '操作', width: 220 },
        ]"
        empty="暂无表数据（可能尚未同步）"
      >
        <template #op="{ row }">
          <t-button variant="text" size="small" @click="openPreview(row)">预览</t-button>
          <t-button variant="text" size="small" @click="openTableComment(row)">表注释</t-button>
          <t-button variant="text" size="small" @click="openColumns(row)">字段</t-button>
        </template>
        <template #tableComment="{ row }">{{ row.tableComment ?? '-' }}</template>
        <template #tableCustomComment="{ row }">{{ row.tableCustomComment ?? '-' }}</template>
      </t-table>
    </div>

    <div class="dda-tables__pager">
      <t-pagination
        :current="pager.page.value"
        :page-size="pager.size.value"
        :total="pager.total.value"
        :page-size-options="[50, 100]"
        @change="(ctx: { current: number; pageSize: number }) => { onPageChange(ctx.current); onSizeChange(ctx.pageSize); }"
      />
    </div>

    <!-- 表注释编辑弹窗 -->
    <t-dialog
      :visible="commentVisible"
      :header="commentTarget ? `表注释：${commentTarget.tableName}` : '表注释'"
      width="520px"
      placement="center"
      attach="body"
      :z-index="LAYER.dialogNested"
      @update:visible="(v: boolean) => (commentVisible = v)"
      @close="commentVisible = false"
    >
      <t-textarea
        v-model="commentDraft"
        placeholder="输入自定义表注释（0~150 字符）"
        :autosize="{ minRows: 3, maxRows: 6 }"
        :maxlength="MAX_COMMENT_LENGTH + 1"
      />
      <div class="dda-comment__meta">
        <span :class="{ 'is-over': commentRemaining(commentDraft) < 0 }">
          {{ commentDraft.length }} / {{ MAX_COMMENT_LENGTH }}
        </span>
        <span v-if="commentError" class="dda-comment__error">{{ commentError }}</span>
      </div>
      <template #footer>
        <t-button variant="outline" @click="commentVisible = false">取消</t-button>
        <t-button theme="primary" :disabled="commentRemaining(commentDraft) < 0" @click="saveTableComment">保存</t-button>
      </template>
    </t-dialog>

    <!-- 字段视图弹窗 -->
    <t-dialog
      :visible="columnsVisible"
      :header="columnsTable ? `字段列表：${columnsTable.tableName}` : '字段列表'"
      width="900px"
      placement="center"
      attach="body"
      :z-index="LAYER.dialogNested"
      :footer="false"
      @update:visible="(v: boolean) => (columnsVisible = v)"
      @close="columnsVisible = false"
    >
      <div v-if="columnsLoading" class="dda-columns__loading">加载中…</div>
      <EmptyGuide v-else-if="columns.length === 0" description="该表暂无字段元数据" />
      <div v-else class="dda-table">
        <t-table
          :data="columns"
          row-key="id"
          size="medium"
          max-height="480"
          :columns="[
            { colKey: 'columnName', title: '字段名', width: 180, ellipsis: true },
            { colKey: 'dataType', title: '类型', width: 120, ellipsis: true },
            { colKey: 'columnComment', title: '原始注释', width: 180, ellipsis: true },
            { colKey: 'custom', title: '自定义注释', width: 300 },
          ]"
        >
          <template #custom="{ row }">
            <div class="dda-columns__inline">
              <t-input v-model="columnEdits[row.id]" size="small" :maxlength="MAX_COMMENT_LENGTH + 1" placeholder="0~150 字符" />
              <t-button size="small" theme="primary" variant="text" @click="saveColumnComment(row)">保存</t-button>
            </div>
          </template>
          <template #columnComment="{ row }">{{ row.columnComment ?? '-' }}</template>
        </t-table>
      </div>
    </t-dialog>

    <PreviewDialog
      v-if="datasource"
      v-model:visible="previewVisible"
      :connection-id="datasource.id"
      :table-name="previewTable"
      :enabled="datasource.status === 'ENABLED'"
      :z-index="LAYER.dialogNested"
    />
  </t-dialog>
</template>

<style scoped>
.dda-tables__toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.dda-tables__pager {
  margin-top: 12px;
}

.dda-comment__meta {
  display: flex;
  justify-content: space-between;
  margin-top: 6px;
  font-size: 12px;
  color: var(--td-text-color-secondary, rgba(0, 0, 0, 0.6));
}

.dda-comment__meta .is-over {
  color: var(--td-error-color, #d54941);
}

.dda-comment__error {
  color: var(--td-error-color, #d54941);
}

.dda-columns__loading {
  padding: 24px;
  text-align: center;
  color: var(--td-text-color-secondary, rgba(0, 0, 0, 0.6));
}

.dda-columns__inline {
  display: flex;
  gap: 6px;
  align-items: center;
}
</style>
