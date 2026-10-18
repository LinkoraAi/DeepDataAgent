<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import type { PrimaryTableCol } from 'tdesign-vue-next';
import StatusTag from '@/shared/components/StatusTag.vue';
import EmptyGuide from '@/shared/components/EmptyGuide.vue';
import { useAsync, errorMessage } from '@/shared/composables/useAsync';
import { FILE_PURPOSES } from '@/shared/enums/dict';
import type { EnumDict } from '@/shared/enums/dict';
import { relativeTime } from '@/shared/utils/time';
import FileUploadDialog from '../components/FileUploadDialog.vue';
import { downloadFileContent, listFiles } from '../api';
import type { FileWire } from '../api';
import { formatBytes } from '../mappers';

/**
 * 会话文件管理页。
 * 游标加载列表（响应特例：data/nextCursor 驼峰）+ purpose 筛选；
 * 列：ID/名称/MIME/来源（purpose）/大小（人性化）/状态/上传时间（相对时间）；
 * 关键字为纯展示层过滤（仅对已加载列表按文件名本地匹配，不改服务端分页/游标语义）；
 * 仅 downloadable=true 显示下载；后端无删除端点，本页 MUST NOT 提供删除入口（清理随 Session 生命周期承载）。
 */

/** 每页条数。 */
const PAGE_SIZE = 20;

/** 文件状态字典（后端当前恒为 ready，未知编码由 StatusTag 原样兜底）。 */
const FILE_STATUS: EnumDict = {
  ready: { label: '就绪', theme: 'success' },
};

/** 用途筛选选项（清空 = 全部）。 */
const purposeOptions = Object.entries(FILE_PURPOSES).map(([value, item]) => ({ label: item.label, value }));

const columns: PrimaryTableCol[] = [
  { title: 'ID', colKey: 'id', width: 150 },
  { title: '名称', colKey: 'filename', minWidth: 220, ellipsis: true },
  { title: '类型', colKey: 'mime_type', width: 170, ellipsis: true },
  { title: '创建来源', colKey: 'purpose', width: 130 },
  { title: '大小', colKey: 'size_bytes', width: 110 },
  { title: '状态', colKey: 'status', width: 100 },
  { title: '上传时间', colKey: 'created_at', width: 160 },
  { title: '操作', colKey: 'action', width: 90 },
];

const purpose = ref<string | undefined>(undefined);
const rows = ref<FileWire[]>([]);
/** 下一页游标（后端 nextCursor；null = 已到末页）。 */
const nextCursor = ref<string | null>(null);
const uploading = ref(false);
/** 文件名搜索关键字（纯展示层过滤，仅作用于已加载列表）。 */
const keyword = ref('');

const { loading, error, run } = useAsync<void>();

/** 拉取一页文件（append=true 沿 nextCursor 加载更多）。 */
async function load(append = false): Promise<void> {
  await run(async () => {
    const resp = await listFiles({
      purpose: purpose.value,
      cursor: append ? nextCursor.value ?? undefined : undefined,
      limit: PAGE_SIZE,
    });
    rows.value = append ? [...rows.value, ...(resp.data ?? [])] : resp.data ?? [];
    nextCursor.value = resp.nextCursor ?? null;
  });
  if (error.value) {
    MessagePlugin.error(errorMessage(error.value));
  }
}

/** 下载文件内容（downloadable=false 的行不渲染入口；失败直显后端 message，如 403/500）。 */
async function onDownload(row: FileWire): Promise<void> {
  await run(() => downloadFileContent(row.id));
  if (error.value) {
    MessagePlugin.error(errorMessage(error.value));
  }
}

/**
 * 上传成功：把新文件直接置顶展示。
 * <p>列表按创建时间升序返回且分页，新上传的文件会落在末页——若仅重新加载首页，
 * 用户上传后反而看不到自己刚传的文件。故此处用上传响应把新文件插到列表头部（按 id 去重）。</p>
 * @param file 上传接口返回的已创建文件（缺失时退化为重新加载首页）
 */
async function onUploadSuccess(file?: FileWire): Promise<void> {
  if (file) {
    rows.value = [file, ...rows.value.filter((row) => row.id !== file.id)];
  } else {
    await load(false);
  }
}

/** 展示层过滤：按文件名对已加载列表做本地匹配（不改动任何服务端筛选与分页语义）。 */
const visibleRows = computed<FileWire[]>(() => {
  const text = keyword.value.trim().toLowerCase();
  if (!text) {
    return rows.value;
  }
  return rows.value.filter((row) => row.filename.toLowerCase().includes(text));
});

/** 空态文案：有关键字时提示无匹配，否则为上传引导。 */
const emptyDescription = computed(() =>
  keyword.value.trim()
    ? '未找到匹配的文件，试试更换关键字或清空搜索'
    : '暂无会话文件。文本类文件可在此上传，供 Agent 在会话中读取；文件删除由会话生命周期承载，无独立删除入口。',
);

onMounted(() => {
  void load(false);
});
</script>

<template>
  <div class="dda-page">
    <header class="files-head">
      <div class="files-head__text">
        <h2 class="dda-title">文件</h2>
        <p class="dda-subtitle">管理智能体可用的会话文件：仅文本类文件、单文件≤50MB，供 Agent 在会话中读取</p>
      </div>
      <t-button theme="primary" @click="uploading = true">
        <t-icon name="upload" />
        上传文件
      </t-button>
    </header>

    <div class="dda-toolbar">
      <t-input v-model="keyword" class="dda-toolbar__grow files-search" placeholder="搜索文件" clearable>
        <template #prefix-icon><t-icon name="search" /></template>
      </t-input>
      <t-select
        v-model="purpose"
        :options="purposeOptions"
        placeholder="按用途筛选（默认全部）"
        clearable
        style="width: 240px"
        @change="load(false)"
      />
      <t-button variant="outline" :loading="loading" @click="load(false)">
        <t-icon name="refresh" />
        刷新
      </t-button>
    </div>

    <div class="dda-table">
      <t-table
        row-key="id"
        :data="visibleRows"
        :columns="columns"
        :loading="loading"
        hover
        size="small"
        table-layout="fixed"
        max-height="max(320px, calc(100vh - 320px))"
      >
        <template #id="{ row }">
          <span class="dda-id files-id" :title="row.id">{{ row.id }}</span>
        </template>
        <template #filename="{ row }">
          <span class="files-name dda-ellipsis" :title="row.filename">{{ row.filename }}</span>
        </template>
        <template #mime_type="{ row }">
          <span class="files-type" :title="row.mime_type">{{ row.mime_type || '—' }}</span>
        </template>
        <template #purpose="{ row }">
          <StatusTag :code="row.purpose" :dict="FILE_PURPOSES" />
        </template>
        <template #size_bytes="{ row }">
          <span>{{ formatBytes(row.size_bytes) }}</span>
        </template>
        <template #status="{ row }">
          <StatusTag :code="row.status" :dict="FILE_STATUS" />
        </template>
        <template #created_at="{ row }">
          <span :title="row.created_at">{{ relativeTime(row.created_at) }}</span>
        </template>
        <template #action="{ row }">
          <t-button
            v-if="row.downloadable"
            variant="text"
            theme="primary"
            size="small"
            @click="onDownload(row)"
          >
            下载
          </t-button>
          <span v-else class="dda-faint">—</span>
        </template>
        <template #empty>
          <EmptyGuide :description="emptyDescription" icon="file-copy">
            <template #action>
              <t-button v-if="!keyword.trim()" theme="primary" variant="outline" size="small" @click="uploading = true">
                上传文件
              </t-button>
            </template>
          </EmptyGuide>
        </template>
      </t-table>
    </div>

    <div class="files-more">
      <t-button v-if="nextCursor" variant="text" theme="primary" :loading="loading" @click="load(true)">加载更多</t-button>
      <p v-if="rows.length" class="files-more__text dda-faint">已加载 {{ rows.length }} 条（游标分页无总数）</p>
    </div>

    <FileUploadDialog v-model:visible="uploading" @success="onUploadSuccess" />
  </div>
</template>

<style scoped>
.files-head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 20px;
}

.files-head__text {
  min-width: 0;
}

.files-search :deep(.t-input) {
  border-radius: var(--td-radius-round);
}

.files-id {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  vertical-align: bottom;
  white-space: nowrap;
}

.files-name {
  display: inline-block;
  max-width: 100%;
  font-weight: 600;
  color: var(--td-text-color-primary);
}

.files-type {
  display: inline-block;
  max-width: 100%;
  padding: 1px 8px;
  overflow: hidden;
  font-size: 12px;
  color: var(--td-text-color-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
  background: var(--td-bg-color-component);
  border-radius: var(--td-radius-round);
}

.files-more {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  margin-top: 14px;
}

.files-more__text {
  margin: 0;
  font-size: 12px;
}
</style>