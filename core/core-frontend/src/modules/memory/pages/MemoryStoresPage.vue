<template>
  <PageSection title="记忆库管理" description="维护 Agent 会话挂载的持久化记忆库（条目按 path 组织，更新走 OCC 版本冲突控制，版本快照不可变且支持 redact）。">
    <t-card :bordered="true">
      <div class="mem-toolbar">
        <t-button theme="primary" @click="openCreateDialog">新建记忆库</t-button>
        <t-button variant="outline" @click="reload">刷新</t-button>
      </div>

      <t-table
        :data="stores"
        :columns="columns"
        row-key="storeId"
        :loading="loading"
        :pagination="pagination"
        :hover="true"
        @page-change="onPageChange"
      >
        <template #status="{ row }">
          <t-tag v-if="row.status === 'archived'" theme="default" variant="light">已归档</t-tag>
          <t-tag v-else theme="success" variant="light">活跃</t-tag>
        </template>
        <template #size="{ row }">
          <span>{{ row.entryCount }} 条 · {{ formatSize(row.totalSize) }}</span>
        </template>
        <template #op="{ row }">
          <div class="op-cell">
            <t-button size="small" variant="text" @click="openEntryDrawer(row)">条目</t-button>
            <t-popconfirm v-if="row.status !== 'archived'" content="归档后条目只读，确认归档？" @confirm="handleArchive(row)">
              <t-button size="small" variant="text" theme="warning">归档</t-button>
            </t-popconfirm>
            <t-popconfirm content="删除将连带全部条目与版本历史（被会话引用时拒绝），确认删除？" @confirm="handleDelete(row)">
              <t-button size="small" variant="text" theme="danger">删除</t-button>
            </t-popconfirm>
          </div>
        </template>
      </t-table>
    </t-card>

    <!-- 新建记忆库对话框 -->
    <t-dialog
      v-model:visible="createVisible"
      header="新建记忆库"
      width="520px"
      :confirm-btn="{ content: '创建', loading: creating }"
      :cancel-btn="{}"
      @confirm="handleCreate"
    >
      <t-form :data="createForm" :rules="createRules" layout="vertical" label-align="left">
        <t-form-item label="名称" name="name">
          <t-input v-model="createForm.name" placeholder="记忆库名称（≤64 字符）" />
        </t-form-item>
        <t-form-item label="描述" name="description">
          <t-textarea v-model="createForm.description" :autosize="{ minRows: 2, maxRows: 4 }" placeholder="可选（≤500 字符）" />
        </t-form-item>
      </t-form>
      <p v-if="createError" class="dialog-error">{{ createError }}</p>
    </t-dialog>

    <!-- 条目管理抽屉 -->
    <t-drawer
      v-model:visible="entryVisible"
      :header="`记忆条目（${entryStore?.name || entryStore?.storeId || ''}）`"
      size="760px"
      :footer="false"
    >
      <div class="mem-toolbar">
        <t-button theme="primary" size="small" @click="openEntryForm(null)">新建条目</t-button>
        <t-button variant="outline" size="small" @click="reloadEntries">刷新</t-button>
      </div>
      <t-table
        :data="entries"
        :columns="entryColumns"
        row-key="memoryId"
        :loading="entryLoading"
        :hover="true"
        expandable
      >
        <template #expandRow="{ row }">
          <div class="entry-detail">
            <p class="entry-detail__label">当前内容（v{{ row.version }}）：</p>
            <pre class="entry-content">{{ entryContents[row.memoryId]?.unavailable ? '(正文不可用：加载失败或已被遮蔽)' : entryContents[row.memoryId]?.content ?? '加载中…' }}</pre>
            <p class="entry-detail__label">版本历史：</p>
            <t-table
              :data="entryVersions[row.memoryId] ?? []"
              :columns="versionColumns"
              row-key="versionId"
              size="small"
              :hover="true"
            >
              <template #action="{ row: ver }">
                <t-tag size="small" variant="light" :theme="ver.action === 'created' ? 'success' : ver.action === 'deleted' ? 'danger' : 'warning'">
                  {{ ver.action }}
                </t-tag>
              </template>
              <template #op="{ row: ver }">
                <t-popconfirm v-if="!ver.redacted" content="遮蔽后正文不可恢复，确认 redact？" @confirm="handleRedact(ver)">
                  <t-button size="extra-small" variant="text" theme="danger">遮蔽</t-button>
                </t-popconfirm>
                <span v-else class="muted">已遮蔽</span>
              </template>
            </t-table>
          </div>
        </template>
        <template #op="{ row }">
          <div class="op-cell">
            <t-button size="small" variant="text" @click="openEntryForm(row)">编辑</t-button>
            <t-popconfirm content="删除条目将保留版本历史（tombstone），确认删除？" @confirm="handleDeleteEntry(row)">
              <t-button size="small" variant="text" theme="danger">删除</t-button>
            </t-popconfirm>
          </div>
        </template>
      </t-table>

      <!-- 条目新建 / 编辑对话框（编辑携带 OCC version） -->
      <t-dialog
        v-model:visible="entryFormVisible"
        :header="entryEditTarget ? `编辑条目（${entryEditTarget.path}，当前 v${entryEditTarget.version}）` : '新建条目'"
        width="640px"
        :confirm-btn="{ content: '保存', loading: entrySaving, disabled: entryFormBlocked }"
        :cancel-btn="{}"
        @confirm="handleSaveEntry"
        :attach="null"
      >
        <t-form :data="entryForm" layout="vertical" label-align="left">
          <t-form-item v-if="!entryEditTarget" label="路径（相对路径，无首斜杠）">
            <t-input v-model="entryForm.path" placeholder="如 notes/客户台账.md" />
          </t-form-item>
          <t-form-item label="内容（≤100KB）">
            <t-textarea v-model="entryForm.content" :autosize="{ minRows: 6, maxRows: 16 }" placeholder="记忆正文" />
          </t-form-item>
        </t-form>
        <p v-if="entryFormError" class="dialog-error">{{ entryFormError }}</p>
      </t-dialog>
    </t-drawer>
  </PageSection>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import type { FormRule, PrimaryTableCol } from 'tdesign-vue-next';
import PageSection from '@/shared/components/PageSection.vue';
import {
  archiveMemoryStore,
  createMemoryEntry,
  createMemoryStore,
  deleteMemoryEntry,
  deleteMemoryStore,
  getMemoryEntry,
  listMemoryEntries,
  listMemoryStores,
  listMemoryVersions,
  redactMemoryVersion,
  updateMemoryEntry,
  type MemoryDetailDto,
  type MemoryEntryDto,
  type MemoryStoreDto,
  type MemoryVersionDto,
} from '../api/memory-stores';

const columns: PrimaryTableCol<MemoryStoreDto>[] = [
  { colKey: 'name', title: '名称', ellipsis: true },
  { colKey: 'storeId', title: '记忆库 ID', width: 220, ellipsis: true },
  { colKey: 'status', title: '状态', width: 90 },
  { colKey: 'size', title: '规模', width: 140 },
  { colKey: 'createdAt', title: '创建时间', width: 180 },
  { colKey: 'op', title: '操作', width: 190 },
];

const entryColumns: PrimaryTableCol<MemoryEntryDto>[] = [
  { colKey: 'path', title: '路径', ellipsis: true },
  { colKey: 'version', title: '版本', width: 70 },
  { colKey: 'size', title: '大小', width: 90, cell: (_h, { row }) => formatSize(row.size) },
  { colKey: 'updatedAt', title: '更新时间', width: 180 },
  { colKey: 'op', title: '操作', width: 120 },
];

const versionColumns: PrimaryTableCol<MemoryVersionDto>[] = [
  { colKey: 'version', title: '版本', width: 60 },
  { colKey: 'action', title: '动作', width: 90 },
  { colKey: 'createdAt', title: '时间', width: 180 },
  { colKey: 'op', title: '操作', width: 90 },
];

function formatSize(size: number): string {
  if (size >= 1024 * 1024) {
    return `${(size / 1024 / 1024).toFixed(1)}MB`;
  }
  if (size >= 1024) {
    return `${(size / 1024).toFixed(1)}KB`;
  }
  return `${size}B`;
}

// —— store 列表 ——
const stores = ref<MemoryStoreDto[]>([]);
const loading = ref(false);
const pagination = reactive({ current: 1, pageSize: 20, total: 0 });

const createVisible = ref(false);
const creating = ref(false);
const createError = ref('');
const createForm = reactive({ name: '', description: '' });
const createRules: Record<string, FormRule[]> = {
  name: [{ required: true, message: '记忆库名称不能为空' }],
};

function openCreateDialog(): void {
  createForm.name = '';
  createForm.description = '';
  createError.value = '';
  createVisible.value = true;
}

async function handleCreate(): Promise<void> {
  if (!createForm.name.trim()) {
    createError.value = '名称为必填项';
    return;
  }
  creating.value = true;
  createError.value = '';
  try {
    await createMemoryStore({
      name: createForm.name.trim(),
      description: createForm.description.trim() || null,
    });
    createVisible.value = false;
    await reload();
  } catch (e) {
    createError.value = (e as Error).message;
  } finally {
    creating.value = false;
  }
}

async function handleArchive(row: MemoryStoreDto): Promise<void> {
  try {
    await archiveMemoryStore(row.storeId);
  } catch (e) {
    MessagePlugin.error(`归档失败: ${(e as Error).message}`);
    return;
  }
  await reload();
}

async function handleDelete(row: MemoryStoreDto): Promise<void> {
  try {
    await deleteMemoryStore(row.storeId);
  } catch (e) {
    MessagePlugin.error(`删除失败: ${(e as Error).message}`);
    return;
  }
  await reload();
}

// —— 条目抽屉 ——
const entryVisible = ref(false);
const entryStore = ref<MemoryStoreDto | null>(null);
const entries = ref<MemoryEntryDto[]>([]);
const entryLoading = ref(false);
/** 条目正文缓存状态：content = 已拉取正文；unavailable = 加载失败或正文缺失（redact），禁止预填覆盖保存。 */
interface EntryContentState {
  content?: string;
  unavailable?: boolean;
}

/** 展开行按需加载的正文与版本缓存（memoryId 索引）。 */
const entryContents = reactive<Record<string, EntryContentState>>({});
const entryVersions = reactive<Record<string, MemoryVersionDto[]>>({});

function openEntryDrawer(row: MemoryStoreDto): void {
  entryStore.value = row;
  entryVisible.value = true;
  void reloadEntries();
}

async function reloadEntries(): Promise<void> {
  if (!entryStore.value) {
    return;
  }
  entryLoading.value = true;
  try {
    entries.value = await listMemoryEntries(entryStore.value.storeId);
    // 预取正文（展开行即时显示）；失败以不可用态标记，编辑时兜底重拉
    for (const entry of entries.value) {
      void preloadEntry(entry.storeId, entry.memoryId);
    }
  } catch (e) {
    MessagePlugin.error(`条目列表加载失败: ${(e as Error).message}`);
  } finally {
    entryLoading.value = false;
  }
}

/**
 * 预取条目正文与版本历史（两者独立 try/catch：版本接口失败不得污染正文缓存）。
 * <p>不再向缓存写入占位文案：加载失败 / 正文缺失（redact）统一记不可用态，
 * 展开行按状态渲染，编辑打开时兜底重拉。</p>
 */
async function preloadEntry(storeId: string, memoryId: string): Promise<void> {
  try {
    const detail: MemoryDetailDto = await getMemoryEntry(storeId, memoryId);
    entryContents[memoryId] =
      detail.content === undefined || detail.content === null
        ? { unavailable: true }
        : { content: detail.content };
  } catch {
    entryContents[memoryId] = { unavailable: true };
  }
  try {
    entryVersions[memoryId] = await listMemoryVersions(storeId, memoryId);
  } catch {
    entryVersions[memoryId] = [];
  }
}

// —— 条目表单（新建 / OCC 编辑） ——
const entryFormVisible = ref(false);
const entrySaving = ref(false);
const entryFormError = ref('');
const entryEditTarget = ref<MemoryEntryDto | null>(null);
/** 正文不可得（加载失败 / 已遮蔽）：打开表单但留空正文并禁用保存，防以空文覆盖原文。 */
const entryFormBlocked = ref(false);
const entryForm = reactive({ path: '', content: '' });

/** 打开条目表单；编辑时缓存缺失 / 不可用态则实时调详情接口拉正文。 */
async function openEntryForm(target: MemoryEntryDto | null): Promise<void> {
  const store = entryStore.value;
  entryEditTarget.value = target;
  entryForm.path = target?.path ?? '';
  entryForm.content = '';
  entryFormError.value = '';
  entryFormBlocked.value = false;
  entryFormVisible.value = true;
  if (!target || !store) {
    return;
  }
  const cached = entryContents[target.memoryId];
  if (cached && cached.content !== undefined) {
    entryForm.content = cached.content;
    return;
  }
  try {
    const detail = await getMemoryEntry(store.storeId, target.memoryId);
    if (detail.content === undefined || detail.content === null) {
      entryContents[target.memoryId] = { unavailable: true };
      entryFormBlocked.value = true;
      entryFormError.value = '无法加载原文（内容已被遮蔽），禁止覆盖保存';
      return;
    }
    entryContents[target.memoryId] = { content: detail.content };
    entryForm.content = detail.content;
  } catch (e) {
    entryContents[target.memoryId] = { unavailable: true };
    entryFormBlocked.value = true;
    entryFormError.value = `无法加载原文，禁止覆盖保存: ${(e as Error).message}`;
  }
}

async function handleSaveEntry(): Promise<void> {
  if (!entryStore.value) {
    return;
  }
  if (entryFormBlocked.value) {
    entryFormError.value = '无法加载原文，禁止覆盖保存';
    return;
  }
  if (entryEditTarget.value) {
    // OCC：version 取列表当前值，冲突由后端 409
    if (!entryForm.content) {
      entryFormError.value = '内容不能为空';
      return;
    }
    entrySaving.value = true;
    entryFormError.value = '';
    try {
      await updateMemoryEntry(entryStore.value.storeId, entryEditTarget.value.memoryId, {
        content: entryForm.content,
        version: entryEditTarget.value.version,
      });
      entryFormVisible.value = false;
      await reloadEntries();
    } catch (e) {
      entryFormError.value = (e as Error).message;
    } finally {
      entrySaving.value = false;
    }
    return;
  }
  if (!entryForm.path.trim() || !entryForm.content) {
    entryFormError.value = '路径与内容为必填项';
    return;
  }
  entrySaving.value = true;
  entryFormError.value = '';
  try {
    await createMemoryEntry(entryStore.value.storeId, {
      path: entryForm.path.trim(),
      content: entryForm.content,
    });
    entryFormVisible.value = false;
    await reloadEntries();
  } catch (e) {
    entryFormError.value = (e as Error).message;
  } finally {
    entrySaving.value = false;
  }
}

async function handleDeleteEntry(row: MemoryEntryDto): Promise<void> {
  if (!entryStore.value) {
    return;
  }
  try {
    await deleteMemoryEntry(entryStore.value.storeId, row.memoryId);
  } catch (e) {
    MessagePlugin.error(`条目删除失败: ${(e as Error).message}`);
    return;
  }
  await reloadEntries();
}

async function handleRedact(ver: MemoryVersionDto): Promise<void> {
  if (!entryStore.value) {
    return;
  }
  let redacted: MemoryVersionDto;
  try {
    redacted = await redactMemoryVersion(entryStore.value.storeId, ver.versionId);
  } catch (e) {
    MessagePlugin.error(`版本遮蔽失败: ${(e as Error).message}`);
    return;
  }
  const list = entryVersions[redacted.entryId];
  if (list) {
    entryVersions[redacted.entryId] = list.map((item) => (item.versionId === redacted.versionId ? redacted : item));
  }
}

async function reload(): Promise<void> {
  loading.value = true;
  try {
    const page = await listMemoryStores(pagination.current, pagination.pageSize);
    stores.value = page.list;
    pagination.total = page.total;
  } catch (e) {
    MessagePlugin.error(`记忆库列表加载失败: ${(e as Error).message}`);
  } finally {
    loading.value = false;
  }
}

function onPageChange(pageInfo: { current: number; pageSize: number }): void {
  pagination.current = pageInfo.current;
  pagination.pageSize = pageInfo.pageSize;
  void reload();
}

onMounted(() => {
  void reload();
});
</script>

<style scoped>
.mem-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
}

.op-cell {
  display: flex;
  align-items: center;
  gap: 4px;
}

.entry-detail {
  padding: 8px 16px;
}

.entry-detail__label {
  margin: 8px 0 4px;
  font-size: 13px;
  color: var(--td-text-color-secondary);
}

.entry-content {
  margin: 0;
  padding: 8px 12px;
  max-height: 240px;
  overflow: auto;
  background: var(--td-bg-color-secondarycontainer);
  border-radius: 3px;
  font-size: 13px;
  white-space: pre-wrap;
  word-break: break-all;
}

.muted {
  color: var(--td-text-color-disabled);
  font-size: 12px;
}

.dialog-error {
  margin: 8px 0 0;
  color: var(--td-error-color);
  font-size: 13px;
}
</style>
