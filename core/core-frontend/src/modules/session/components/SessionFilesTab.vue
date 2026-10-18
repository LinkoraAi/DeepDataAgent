<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import type { PrimaryTableCol } from 'tdesign-vue-next';
import EmptyGuide from '@/shared/components/EmptyGuide.vue';
import { ApiError } from '@/shared/types/api';
import { errorMessage, useAsync } from '@/shared/composables/useAsync';
import { confirmDangerous } from '@/shared/composables/useConfirm';
import { LAYER } from '@/shared/constants/layers';
import { listFiles } from '@/modules/assetfile/api';
import type { FileWire } from '@/modules/assetfile/api';
import { addResources, listResources, removeResource } from '../api/sessions';
import type { SessionResourceInput } from '../api/sessions';
import type { SessionResourceWire } from '../types';
import { defaultMountPath, validateMountPath } from '../mappers';

/**
 * 文件 Tab：列出会话已挂载资源（file_id / mount_path / created_at），支持追加挂载与卸载。
 * <p>追加：从文件库选择 + 挂载路径（缺省 mounts/{file_id}，前端先校验形态），提交 addResources；
 * 重复挂载 / 文件未就绪 / 归档冲突等后端 409 直显原因，列表保持原状。
 * 卸载经 confirmDangerous 二次确认。归档 / 终止态禁用全部写操作。</p>
 */

const props = defineProps<{
  /** 会话 ID */
  sessionId: string;
  /** 是否只读（归档或终止：禁用挂载/卸载） */
  readOnly: boolean;
}>();

/** 资源列表每页条数。 */
const PAGE_LIMIT = 100;

/** 已挂载资源。 */
const resources = ref<SessionResourceWire[]>([]);

/** 添加对话框显隐。 */
const addVisible = ref(false);

/** 文件库列表。 */
const files = ref<FileWire[]>([]);
/** 文件库下一页游标。 */
const filesCursor = ref<string | null>(null);

/** 勾选待挂载项（fileId → mountPath）。 */
interface PickDraft {
  fileId: string;
  filename: string;
  mountPath: string;
}

/** 当前对话框勾选草稿。 */
const picks = ref<Record<string, PickDraft>>({});

/** 对话框内后端错误（409 直显）。 */
const serverError = ref('');

/** 路径校验错误（fileId → 文案）。 */
const pathErrors = computed<Record<string, string>>(() => {
  const out: Record<string, string> = {};
  for (const [fileId, draft] of Object.entries(picks.value)) {
    const message = validateMountPath(draft.mountPath);
    if (message) {
      out[fileId] = message;
    }
  }
  return out;
});

const listTask = useAsync<SessionResourceWire[]>();
const filesTask = useAsync<void>();
const addTask = useAsync<void>();
const removeTask = useAsync<void>();

/** 列表加载中；必须取 .value，否则拿到 Ref 对象恒为真值导致表格永久 loading。 */
const loading = computed(() => listTask.loading.value);
/** 卸载请求进行中（模板中 `removeTask.loading` 属嵌套属性访问，Vue 不会自动解包，
 * 裸 Ref 对象经 `||` 短路后恒为真值 → 表格加载遮罩常驻；故必须经 computed 取 .value）。 */
const removeTaskLoading = computed(() => removeTask.loading.value);

/**
 * 文件库（对话框选择表）加载中。
 * 必须用 computed 取 .value：模板里 `filesTask.loading` 属于嵌套属性访问，Vue 不会自动解包，
 * 传入 Ref 对象恒为真值 → 表格 loading 遮罩常驻、按钮永久 loading/禁用。
 */
const filesLoading = computed(() => filesTask.loading.value);
/** 挂载提交中（同上，必须取 .value）。 */
const addSubmitting = computed(() => addTask.loading.value);

const columns: PrimaryTableCol[] = [
  { title: '文件 ID', colKey: 'file_id', minWidth: 180, cell: 'fileId' },
  { title: '挂载路径', colKey: 'mount_path', minWidth: 200, cell: 'mountPath', ellipsis: true },
  { title: '挂载时间', colKey: 'created_at', width: 180, cell: 'createdAt' },
  { title: '操作', colKey: 'action', width: 90, cell: 'action' },
];

/** 拉取全部已挂载资源（游标翻页累积）。 */
async function loadResources(): Promise<void> {
  // 空会话 ID 防御：杜绝任何时序问题下发出 /sessions//resources 畸形请求
  if (!props.sessionId) {
    return;
  }
  const data = await listTask.run(async () => {
    const acc: SessionResourceWire[] = [];
    let afterId: string | undefined;
    for (let page = 0; page < 50; page += 1) {
      const resp = await listResources(props.sessionId, { limit: PAGE_LIMIT, after_id: afterId });
      acc.push(...(resp.data ?? []));
      if (!resp.has_more || !resp.last_id) {
        break;
      }
      afterId = resp.last_id;
    }
    return acc;
  });
  resources.value = data ?? [];
  if (listTask.error.value) {
    MessagePlugin.error(errorMessage(listTask.error.value));
  }
}

/** 加载一页文件库。 */
async function loadFiles(append: boolean): Promise<void> {
  await filesTask.run(async () => {
    const resp = await listFiles({ cursor: append ? filesCursor.value ?? undefined : undefined, limit: 20 });
    const list = resp.data ?? [];
    files.value = append ? [...files.value, ...list] : list;
    filesCursor.value = resp.nextCursor ?? null;
  });
  if (filesTask.error.value) {
    MessagePlugin.error(errorMessage(filesTask.error.value));
  }
}

/** 打开添加对话框（刷新文件库、复位选择与错误）。 */
function openAdd(): void {
  picks.value = {};
  serverError.value = '';
  addVisible.value = true;
  void loadFiles(false);
}

/** 是否已勾选。 */
function isPicked(fileId: string): boolean {
  return fileId in picks.value;
}

/** 勾选 / 取消勾选文件（勾选即按缺省挂载路径入草稿）。 */
function toggleFile(file: FileWire): void {
  const next = { ...picks.value };
  if (file.id in next) {
    delete next[file.id];
  } else {
    next[file.id] = { fileId: file.id, filename: file.filename, mountPath: defaultMountPath(file.id) };
  }
  picks.value = next;
}

/** 待提交项数量。 */
const pickCount = computed(() => Object.keys(picks.value).length);

/** 提交追加挂载。 */
async function submitAdd(): Promise<void> {
  serverError.value = '';
  const drafts = Object.values(picks.value);
  if (!drafts.length) {
    MessagePlugin.warning('请至少选择一个文件');
    return;
  }
  const firstError = Object.values(pathErrors.value)[0];
  if (firstError) {
    serverError.value = firstError;
    return;
  }
  const inputs: SessionResourceInput[] = drafts.map((draft) => ({
    type: 'file',
    file_id: draft.fileId,
    mount_path: draft.mountPath.trim() || undefined,
  }));
  await addTask.run(async () => {
    await addResources(props.sessionId, inputs);
  });
  if (addTask.error.value) {
    // 409 冲突（重复挂载 / 未就绪 / 归档）等直显后端原因，列表不动、对话框保留
    serverError.value = addTask.error.value instanceof ApiError
      ? `${addTask.error.value.message}`
      : errorMessage(addTask.error.value);
    return;
  }
  MessagePlugin.success('已追加挂载');
  addVisible.value = false;
  await loadResources();
}

/** 卸载单个资源（二次确认）。 */
async function onRemove(row: SessionResourceWire): Promise<void> {
  const confirmed = await confirmDangerous({
    header: '卸载文件',
    body: `即将移除挂载资源「${row.mount_path || row.file_id || row.id}」，卸载不影响文件库中的源文件。`,
    theme: 'warning',
    confirmText: '卸载',
  });
  if (!confirmed) {
    return;
  }
  await removeTask.run(() => removeResource(props.sessionId, row.id));
  if (removeTask.error.value) {
    MessagePlugin.error(errorMessage(removeTask.error.value));
    return;
  }
  MessagePlugin.success('已卸载');
  await loadResources();
}

/** 时间展示（RFC 3339 UTC → 本地分钟）。 */
function toMinute(value: string | null | undefined): string {
  if (!value) {
    return '-';
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false });
}

onMounted(() => {
  void loadResources();
});

defineExpose({ reload: loadResources });
</script>

<template>
  <div class="files-tab">
    <div class="files-tab__bar">
      <t-button theme="primary" size="small" :disabled="readOnly" @click="openAdd">添加文件</t-button>
      <t-button variant="outline" size="small" :loading="loading" @click="loadResources">刷新</t-button>
      <span v-if="readOnly" class="files-tab__hint">会话已归档或终止，挂载操作不可用</span>
    </div>

    <div class="dda-table files-tab__table">
      <t-table
        row-key="id"
        :data="resources"
        :columns="columns"
        :loading="loading || removeTaskLoading"
        hover
        size="small"
      >
        <template #fileId="{ row }">
          <code class="dda-id" :title="row.file_id">{{ row.file_id || '—' }}</code>
        </template>
        <template #mountPath="{ row }">{{ row.mount_path || '—' }}</template>
        <template #createdAt="{ row }">{{ toMinute(row.created_at) }}</template>
        <template #action="{ row }">
          <t-link theme="danger" hover="color" :disabled="readOnly" @click="onRemove(row)">卸载</t-link>
        </template>
        <template #empty>
          <EmptyGuide description="尚未挂载任何文件" />
        </template>
      </t-table>
    </div>

    <t-dialog
      v-model:visible="addVisible"
      header="添加文件"
      :width="820"
      :close-on-overlay-click="false"
      destroy-on-close
      placement="center"
      attach="body"
      :z-index="LAYER.dialog"
    >
      <div class="dda-table">
        <t-table
          row-key="id"
          :data="files"
          :columns="[
            { title: '选择', colKey: 'pick', width: 60, cell: 'pick' },
            { title: '文件名', colKey: 'filename', minWidth: 200, ellipsis: true },
            { title: '挂载路径', colKey: 'mount', minWidth: 240, cell: 'mount' },
          ]"
          :loading="filesLoading"
          hover
          size="small"
          max-height="320"
        >
          <template #pick="{ row }">
            <t-checkbox :checked="isPicked(row.id)" :disabled="readOnly || row.status !== 'ready'" @change="toggleFile(row)" />
          </template>
          <template #mount="{ row }">
            <div v-if="isPicked(row.id)">
              <t-input v-model="picks[row.id].mountPath" size="small" placeholder="mounts/…" />
              <span v-if="pathErrors[row.id]" class="files-tab__field-error">{{ pathErrors[row.id] }}</span>
            </div>
            <span v-else class="files-tab__path-hint">勾选后可编辑（缺省 mounts/{file_id}）</span>
          </template>
          <template #empty>
            <EmptyGuide description="文件库暂无可选文件" />
          </template>
        </t-table>
      </div>

      <div v-if="filesCursor" class="files-tab__more">
        <t-button variant="text" theme="primary" :loading="filesLoading" @click="loadFiles(true)">加载更多文件</t-button>
      </div>

      <t-alert v-if="serverError" theme="error" :message="serverError" class="files-tab__alert" />

      <template #footer>
        <t-space>
          <t-button variant="outline" @click="addVisible = false">取消</t-button>
          <t-button theme="primary" :disabled="!pickCount || addSubmitting" :loading="addSubmitting" @click="submitAdd">
            挂载（{{ pickCount }}）
          </t-button>
        </t-space>
      </template>
    </t-dialog>
  </div>
</template>

<style scoped>
.files-tab {
  padding: 16px;
  overflow-y: auto;
  height: 100%;
}

.files-tab__bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

/* 扁平表格卡片：复用全局 .dda-table 表头/悬浮样式，外层补描边与投影 */
.files-tab__table {
  overflow: hidden;
  border: 1px solid var(--dda-card-border);
  border-radius: var(--td-radius-large);
  box-shadow: var(--dda-card-shadow);
}

.files-tab__hint {
  font-size: 12px;
  color: var(--td-warning-color, #e37318);
}

.files-tab__more {
  margin-top: 8px;
  text-align: center;
}

.files-tab__alert {
  margin-top: 12px;
}

.files-tab__field-error {
  font-size: 12px;
  color: var(--td-error-color, #d54941);
}

.files-tab__path-hint {
  font-size: 12px;
  color: var(--td-text-color-placeholder, rgba(0, 0, 0, 0.4));
}
</style>
