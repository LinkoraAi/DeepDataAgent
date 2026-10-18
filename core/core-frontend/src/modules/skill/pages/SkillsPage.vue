<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import type { PrimaryTableCol } from 'tdesign-vue-next';
import StatusTag from '@/shared/components/StatusTag.vue';
import EmptyGuide from '@/shared/components/EmptyGuide.vue';
import RowActions from '@/shared/components/RowActions.vue';
import type { RowActionItem } from '@/shared/components/RowActions.vue';
import RowMenu from '@/shared/components/RowMenu.vue';
import type { RowMenuItem } from '@/shared/components/RowMenu.vue';
import { useAsync, errorMessage } from '@/shared/composables/useAsync';
import { useCursorPages } from '@/shared/composables/usePagination';
import { confirmDangerous } from '@/shared/composables/useConfirm';
import { SKILL_SOURCES } from '@/shared/enums/dict';
import { absoluteTime, relativeTime } from '@/shared/utils/time';
import type { CursorPage } from '@/shared/types/api';
import SkillUploadDialog from '../components/SkillUploadDialog.vue';
import { deleteSkill, deleteVersion, downloadVersion, getSkillDetail, listSkills, listVersions } from '../api';
import type { SkillDetailWire, SkillVersionWire, SkillWire } from '../api';

/**
 * 技能包管理页。
 * 游标分页列表（加载更多）+ 关键字/来源筛选（技能市场本期无数据置灰禁选）；
 * 行操作（⋮）：详情（抽屉含版本清单）、更新版本、下载最新版、删除最新版、删除技能；
 * 删除冲突（409）直显后端 message；空态引导上传技能包。
 * 注：列表壳对象不含 name/description（契约在版本对象上），本页按行懒取最新版本回填展示。
 */

/** 列表行模型：技能壳 + 最新版本回填的名称/描述。 */
interface SkillRow extends SkillWire {
  /** frontmatter name（最新版本，缺版本时为空串） */
  name: string;
  /** frontmatter description（最新版本，缺版本时为空串） */
  description: string;
}

/** 每页条数（游标分页，无 total）。 */
const PAGE_SIZE = 20;

/** 来源筛选选项：catalog 本期无市场数据生产方，置灰禁选并在标签提示。 */
const sourceOptions = [
  { label: '自定义', value: 'custom' },
  { label: '技能市场（本期无市场数据）', value: 'catalog', disabled: true },
];

const columns: PrimaryTableCol[] = [
  { title: 'ID', colKey: 'id', width: 150 },
  { title: '名称', colKey: 'name', minWidth: 200, ellipsis: true },
  { title: '描述', colKey: 'description', minWidth: 200, ellipsis: true },
  { title: '来源', colKey: 'source', width: 110 },
  { title: '最新版本', colKey: 'latest_version', width: 170 },
  { title: '创建时间', colKey: 'created_at', width: 160 },
  { title: '操作', colKey: 'action', width: 80 },
];

/** 详情抽屉版本列表列。 */
const versionColumns: PrimaryTableCol[] = [
  { title: '版本键', colKey: 'version', width: 170 },
  { title: '名称', colKey: 'name', width: 150, ellipsis: true },
  { title: '描述', colKey: 'description', minWidth: 160, ellipsis: true },
  { title: '创建时间', colKey: 'created_at', width: 170 },
  { title: '操作', colKey: 'action', width: 90 },
];

const source = ref<'catalog' | 'custom'>('custom');
const keyword = ref('');

const pager = useCursorPages<SkillRow>(PAGE_SIZE);
const { rows, hasMore } = { rows: pager.items, hasMore: pager.hasMore };
const { loading, error, run } = useAsync<void>();

const uploadVisible = ref(false);
const uploadMode = ref<'create' | 'version'>('create');
const uploadTarget = ref<SkillRow | null>(null);

const detailVisible = ref(false);
const detail = ref<SkillDetailWire | null>(null);
const detailAsync = useAsync<void>();

/**
 * 详情预览行（把技能壳补齐 name/description，派生自最新版本）。
 * 纯展示派生：供既有「下载最新版 / 删除技能」处理函数复用（二者签名基于 SkillRow），不承载任何业务规则。
 */
const detailRow = computed<SkillRow | null>(() => {
  const current = detail.value;
  if (!current) {
    return null;
  }
  const latest = current.versions[0];
  return { ...current.skill, name: latest?.name ?? '', description: latest?.description ?? '' };
});

/** 详情内最新版本（版本键倒序的首位；无版本时为 null），供预览卡片展示与操作。 */
const latestVersion = computed<SkillVersionWire | null>(() => detail.value?.versions[0] ?? null);

/** 拉取一页技能列表（append=true 走游标加载更多）。 */
async function load(append = false): Promise<void> {
  await run(async () => {
    const base = { source: source.value, keyword: keyword.value.trim() || undefined };
    const params = append ? { ...base, ...pager.nextParams() } : { ...base, limit: PAGE_SIZE };
    const resp = await listSkills(params);
    const mapped: CursorPage<SkillRow> = {
      ...resp,
      data: resp.data.map((item) => ({ ...item, name: '', description: '' })),
    };
    pager.apply(mapped, append);
  });
  if (error.value) {
    MessagePlugin.error(errorMessage(error.value));
    return;
  }
  // 新页行的名称/描述按行懒取回填（仅处理未回填行，重复调用无副作用）
  void enrichRows();
}

/** 按行懒取最新版本回填名称/描述（列表契约不含该两列，失败静默保持空白）。 */
async function enrichRows(): Promise<void> {
  const targets = rows.value.filter((row) => !row.name);
  await Promise.allSettled(
    targets.map(async (row) => {
      const resp = await listVersions(row.id, { limit: 1 });
      const latest = resp.data[0];
      if (latest) {
        row.name = latest.name;
        row.description = latest.description;
      }
    }),
  );
}

/** 打开上传对话框（create=新建技能；version=对目标技能发版）。 */
function openUpload(mode: 'create' | 'version', row?: SkillRow): void {
  uploadMode.value = mode;
  uploadTarget.value = row ?? null;
  uploadVisible.value = true;
}

/** 上传成功：刷新首页列表。 */
function onUploadSuccess(): void {
  void load(false);
}

/** 下载最新版本 zip（无版本时提示）。 */
async function onDownloadLatest(row: SkillRow): Promise<void> {
  if (!row.latest_version) {
    MessagePlugin.warning('该技能暂无内容版本，无法下载');
    return;
  }
  const versionNo = row.latest_version;
  await run(() => downloadVersion(row.id, versionNo));
  if (error.value) {
    MessagePlugin.error(errorMessage(error.value));
  }
}

/** 删除最新版本（被钉版引用 409 直显原因；成功后刷新列表）。 */
async function onDeleteVersion(row: SkillRow): Promise<void> {
  if (!row.latest_version) {
    MessagePlugin.warning('该技能暂无内容版本，无需删除');
    return;
  }
  const versionNo = row.latest_version;
  const confirmed = await confirmDangerous({
    header: '删除最新版本',
    body: `将删除技能「${row.display_title}」的头版本 ${versionNo}；若该版本被智能体钉版引用将返回冲突原因。删除后最新版本指针自动重算。`,
  });
  if (!confirmed) {
    return;
  }
  await run(() => deleteVersion(row.id, versionNo));
  if (error.value) {
    MessagePlugin.error(errorMessage(error.value));
    return;
  }
  MessagePlugin.success('版本已删除');
  await load(false);
}

/** 删除技能（逻辑删除；仍被未删除智能体版本绑定 409 直显原因）。 */
async function onDeleteSkill(row: SkillRow): Promise<void> {
  const confirmed = await confirmDangerous({
    header: '删除技能',
    body: `将删除技能「${row.display_title}」（逻辑删除，历史版本数据保留）；若仍被未删除的智能体版本绑定将返回冲突原因。`,
  });
  if (!confirmed) {
    return;
  }
  await run(() => deleteSkill(row.id));
  if (error.value) {
    MessagePlugin.error(errorMessage(error.value));
    return;
  }
  MessagePlugin.success('技能已删除');
  detailVisible.value = false;
  await load(false);
}

/** 打开详情抽屉并加载版本列表。 */
async function openDetail(row: SkillRow): Promise<void> {
  detailVisible.value = true;
  await loadDetail(row.id);
}

/** 加载技能详情（壳 + 全部版本）。 */
async function loadDetail(id: string): Promise<void> {
  await detailAsync.run(async () => {
    detail.value = await getSkillDetail(id);
  });
  if (detailAsync.error.value) {
    MessagePlugin.error(errorMessage(detailAsync.error.value));
  }
}

/** 详情内下载指定版本。 */
async function onDownloadVersion(version: SkillVersionWire): Promise<void> {
  await detailAsync.run(() => downloadVersion(version.skill_id, version.version));
  if (detailAsync.error.value) {
    MessagePlugin.error(errorMessage(detailAsync.error.value));
  }
}

/** 详情内删除指定版本（成功后同时刷新详情与列表）。 */
async function onDeleteDetailVersion(version: SkillVersionWire): Promise<void> {
  const confirmed = await confirmDangerous({
    header: '删除版本',
    body: `将删除版本 ${version.version}（技能 ${version.skill_id}）；若被智能体钉版引用将返回冲突原因。`,
  });
  if (!confirmed) {
    return;
  }
  await detailAsync.run(() => deleteVersion(version.skill_id, version.version));
  if (detailAsync.error.value) {
    MessagePlugin.error(errorMessage(detailAsync.error.value));
    return;
  }
  MessagePlugin.success('版本已删除');
  await loadDetail(version.skill_id);
  await load(false);
}

/**
 * 主表行菜单项：详情 / 更新版本 / 下载最新版 / 删除版本 / 删除技能。
 * 无内容版本时禁用下载与删除版本，与原入口禁用约束一致；删除类操作以危险色呈现。
 */
function rowActions(row: SkillRow): RowMenuItem[] {
  return [
    { key: 'detail', label: '详情', icon: 'file-copy' },
    { key: 'version', label: '更新版本', icon: 'upload' },
    { key: 'download', label: '下载最新版', icon: 'download', disabled: !row.latest_version },
    { key: 'deleteVersion', label: '删除版本', icon: 'delete', destructive: true, disabled: !row.latest_version },
    { key: 'deleteSkill', label: '删除技能', icon: 'delete', destructive: true },
  ];
}

/** 主表行操作分发：复用既有处理函数，语义与行为不变。 */
function onRowAction(row: SkillRow, key: string): void {
  if (key === 'detail') {
    void openDetail(row);
    return;
  }
  if (key === 'version') {
    openUpload('version', row);
    return;
  }
  if (key === 'download') {
    void onDownloadLatest(row);
    return;
  }
  if (key === 'deleteVersion') {
    void onDeleteVersion(row);
    return;
  }
  if (key === 'deleteSkill') {
    void onDeleteSkill(row);
  }
}

/** 详情抽屉版本行操作项：下载内联，删除收进「更多」下拉。 */
function versionActions(): RowActionItem[] {
  return [
    { key: 'download', label: '下载', theme: 'primary', tip: '下载该版本 zip' },
    { key: 'delete', label: '删除', theme: 'danger', tip: '删除该版本（被钉版引用时失败）' },
  ];
}

/** 详情抽屉版本行操作分发：复用既有处理函数，语义与行为不变。 */
function onVersionAction(version: SkillVersionWire, key: string): void {
  if (key === 'download') {
    void onDownloadVersion(version);
    return;
  }
  if (key === 'delete') {
    void onDeleteDetailVersion(version);
  }
}

onMounted(() => {
  void load(false);
});
</script>

<template>
  <div class="dda-page">
    <header class="skill-head">
      <div class="skill-head__text">
        <h2 class="dda-title">技能</h2>
        <p class="dda-subtitle">上传与维护 Agent 可绑定的技能包（zip + SKILL.md frontmatter）</p>
      </div>
      <t-button theme="primary" @click="openUpload('create')">
        <t-icon name="upload" />
        上传技能包
      </t-button>
    </header>

    <div class="dda-toolbar">
      <t-input
        v-model="keyword"
        class="dda-toolbar__grow skill-search"
        placeholder="按展示名搜索，回车查询"
        clearable
        @enter="load(false)"
      >
        <template #prefix-icon><t-icon name="search" /></template>
      </t-input>
      <t-select v-model="source" :options="sourceOptions" style="width: 220px" @change="load(false)" />
      <t-button theme="primary" variant="outline" :loading="loading" @click="load(false)">
        <t-icon name="search" />
        查询
      </t-button>
    </div>

    <div class="dda-table">
      <t-table
        row-key="id"
        :data="rows"
        :columns="columns"
        :loading="loading"
        hover
        size="small"
        table-layout="fixed"
        max-height="max(320px, calc(100vh - 320px))"
      >
        <template #id="{ row }">
          <span class="dda-id skill-id" :title="row.id">{{ row.id }}</span>
        </template>
        <template #name="{ row }">
          <span class="skill-name">
            <t-icon name="code" size="16px" />
            <span class="dda-ellipsis">{{ row.name || '—' }}</span>
          </span>
        </template>
        <template #description="{ row }">
          <span class="dda-ellipsis" :title="row.description">{{ row.description || '—' }}</span>
        </template>
        <template #source="{ row }">
          <StatusTag :code="row.source" :dict="SKILL_SOURCES" />
        </template>
        <template #latest_version="{ row }">
          <span class="dda-mono">{{ row.latest_version ?? '—' }}</span>
        </template>
        <template #created_at="{ row }">
          <span :title="row.created_at">{{ relativeTime(row.created_at) }}</span>
        </template>
        <template #action="{ row }">
          <RowMenu :items="rowActions(row)" @select="(key: string) => onRowAction(row, key)" />
        </template>
        <template #empty>
          <EmptyGuide
            description="暂无技能包。上传符合结构要求的 .zip 技能包（唯一顶级目录 + SKILL.md）即可创建技能。"
            icon="code"
          >
            <template #action>
              <t-button theme="primary" variant="outline" size="small" @click="openUpload('create')">上传技能包</t-button>
            </template>
          </EmptyGuide>
        </template>
      </t-table>
    </div>

    <div class="skill-more">
      <t-button v-if="hasMore" variant="text" theme="primary" :loading="loading" @click="load(true)">加载更多</t-button>
      <p v-if="rows.length" class="skill-more__text dda-faint">已加载 {{ rows.length }} 条（游标分页无总数）</p>
    </div>

    <SkillUploadDialog
      v-model:visible="uploadVisible"
      :mode="uploadMode"
      :skill-id="uploadTarget?.id"
      :skill-name="uploadTarget?.display_title"
      @success="onUploadSuccess"
    />

    <t-drawer v-model:visible="detailVisible" header="技能详情" size="72%" :footer="false" placement="right">
      <template v-if="detail">
        <div class="skill-detail">
          <header class="skill-detail__head">
            <div class="skill-detail__title">
              <t-icon name="code" size="18px" />
              <span class="skill-detail__name">{{ detail.skill.display_title }}</span>
              <StatusTag :code="detail.skill.source" :dict="SKILL_SOURCES" />
            </div>
            <div class="skill-detail__head-actions">
              <t-tooltip content="下载最新版">
                <t-button
                  variant="outline"
                  shape="square"
                  aria-label="下载最新版"
                  :disabled="!detail.skill.latest_version"
                  @click="detailRow ? onDownloadLatest(detailRow) : undefined"
                >
                  <t-icon name="download" />
                </t-button>
              </t-tooltip>
              <t-tooltip content="删除技能">
                <t-button
                  variant="outline"
                  theme="danger"
                  shape="square"
                  aria-label="删除技能"
                  @click="detailRow ? onDeleteSkill(detailRow) : undefined"
                >
                  <t-icon name="delete" />
                </t-button>
              </t-tooltip>
            </div>
          </header>

          <div class="dda-metabar skill-detail__meta">
            <span class="dda-metabar__item">
              <span class="skill-detail__label">ID</span>
              <span class="dda-id">{{ detail.skill.id }}</span>
            </span>
            <span class="dda-metabar__item">
              <span class="skill-detail__label">创建时间</span>
              <span>{{ absoluteTime(detail.skill.created_at) }}</span>
            </span>
            <span class="dda-metabar__item">
              <span class="skill-detail__label">更新时间</span>
              <span>{{ relativeTime(detail.skill.updated_at) }}</span>
            </span>
          </div>

          <section class="dda-panel skill-detail__card">
            <h4 class="skill-detail__card-title">描述</h4>
            <p class="skill-detail__desc">{{ latestVersion?.description || '—' }}</p>
          </section>

          <section class="dda-panel skill-detail__card">
            <h4 class="skill-detail__card-title">版本</h4>
            <div v-if="latestVersion" class="skill-detail__version">
              <div class="skill-detail__version-main">
                <span class="dda-mono">{{ latestVersion.version }}</span>
                <t-tag theme="success" variant="light" size="small">最新</t-tag>
                <span class="dda-faint">{{ relativeTime(latestVersion.created_at) }}</span>
              </div>
              <div class="skill-detail__version-actions">
                <t-button variant="text" size="small" @click="onDownloadVersion(latestVersion)">下载</t-button>
                <t-button variant="text" size="small" theme="danger" @click="onDeleteDetailVersion(latestVersion)">
                  删除
                </t-button>
              </div>
            </div>
            <p v-else class="dda-faint">该技能暂无内容版本</p>
          </section>

          <h4 class="skill-detail__block-title">版本清单（{{ detail.versions.length }}）</h4>
          <div class="dda-table">
            <t-table
              row-key="id"
              :data="detail.versions"
              :columns="versionColumns"
              :loading="detailAsync.loading.value"
              hover
              size="small"
              table-layout="fixed"
            >
              <template #version="{ row }">
                <span class="dda-mono">{{ row.version }}</span>
              </template>
              <template #created_at="{ row }">
                <span :title="row.created_at">{{ relativeTime(row.created_at) }}</span>
              </template>
              <template #action="{ row }">
                <RowActions :actions="versionActions()" @select="(key: string) => onVersionAction(row, key)" />
              </template>
              <template #empty>
                <EmptyGuide description="该技能暂无内容版本（全部版本已被删除），可通过「更新版本」重新上传技能包。" />
              </template>
            </t-table>
          </div>
        </div>
      </template>
      <template v-else>
        <EmptyGuide description="详情加载中或加载失败，请关闭后重试。" icon="code" />
      </template>
    </t-drawer>
  </div>
</template>

<style scoped>
.skill-head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 20px;
}

.skill-head__text {
  min-width: 0;
}

.skill-search :deep(.t-input) {
  border-radius: var(--td-radius-round);
}

.skill-id {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  vertical-align: bottom;
  white-space: nowrap;
}

.skill-name {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  color: var(--td-text-color-primary);
}

.skill-more {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  margin-top: 14px;
}

.skill-more__text {
  margin: 0;
  font-size: 12px;
}

/* --------------------------- 预览式详情 --------------------------- */
.skill-detail__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--td-component-border);
}

.skill-detail__title {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  font-size: 17px;
  font-weight: 600;
  color: var(--td-text-color-primary);
}

.skill-detail__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.skill-detail__head-actions {
  display: flex;
  flex: none;
  gap: 8px;
  align-items: center;
}

.skill-detail__meta {
  margin: 14px 0 18px;
}

.skill-detail__label {
  color: var(--td-text-color-placeholder);
}

.skill-detail__card {
  margin-bottom: 14px;
  padding: 16px;
}

.skill-detail__card-title {
  margin: 0 0 10px;
  font-size: 13px;
  font-weight: 600;
  color: var(--td-text-color-primary);
}

.skill-detail__desc {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--td-text-color-secondary);
  white-space: pre-wrap;
  word-break: break-word;
}

.skill-detail__version {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.skill-detail__version-main {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.skill-detail__version-actions {
  display: flex;
  flex: none;
  gap: 4px;
  align-items: center;
}

.skill-detail__block-title {
  margin: 18px 0 10px;
  font-size: 13px;
  font-weight: 600;
  color: var(--td-text-color-primary);
}
</style>