<template>
  <PageSection title="技能管理" description="以技能包（zip 包 / 文件树）维护不可变 epoch 版本，供 Agent 版本绑定引用。">
    <t-card :bordered="true">
      <div class="skill-toolbar">
        <t-input
          v-model="keyword"
          placeholder="按展示名搜索"
          clearable
          class="skill-toolbar__search"
          @enter="reload"
        />
        <t-select v-model="sourceFilter" :options="sourceFilterOptions" class="skill-toolbar__source" />
        <t-button theme="primary" @click="reload">搜索</t-button>
        <t-button theme="primary" variant="outline" @click="openCreate">新建技能</t-button>
      </div>

      <t-table
        :data="skills"
        :columns="columns"
        row-key="id"
        :loading="loading"
        :hover="true"
      >
        <template #source="{ row }">
          <t-tag :theme="row.source === 'custom' ? 'primary' : 'warning'" variant="light">
            {{ row.source === 'custom' ? '自建' : '目录' }}
          </t-tag>
        </template>
        <template #latest_version="{ row }">
          <span>{{ row.latest_version ? formatVersion(row.latest_version) : '无版本' }}</span>
        </template>
        <template #op="{ row }">
          <div class="op-cell">
            <t-button size="small" variant="text" @click="openPublish(row)">发布新版本</t-button>
            <t-button size="small" variant="text" @click="openVersions(row)">版本列表</t-button>
            <t-popconfirm content="删除后技能不可见（历史版本数据保留）；被 Agent 绑定时将拒绝。确认删除？" @confirm="handleDelete(row)">
              <t-button size="small" variant="text" theme="danger">删除</t-button>
            </t-popconfirm>
          </div>
        </template>
      </t-table>

      <div class="skill-more">
        <t-button v-if="hasMore" variant="text" :loading="loading" @click="loadMore">加载更多</t-button>
      </div>
    </t-card>

    <!-- 新建技能（multipart 技能包；展示名创建后不可改） -->
    <t-dialog
      v-model:visible="createVisible"
      header="新建技能"
      width="640px"
      :confirm-btn="{ content: '创建', loading: submitting }"
      :cancel-btn="{}"
      @confirm="handleCreate"
    >
      <t-alert
        theme="info"
        message="上传单个 .zip（≤50MB）或直接选择技能目录；包须有唯一顶级目录，且目录名等于顶级 SKILL.md frontmatter 的 name。"
        class="form-alert"
      />
      <t-form :data="createForm" layout="vertical" label-align="left">
        <t-form-item label="展示名（display_title，可选，≤255，创建后不可改）">
          <t-input v-model="createForm.displayTitle" placeholder="缺省取 zip 名或 frontmatter name" />
        </t-form-item>
        <t-form-item label="技能包">
          <SkillPackagePicker v-model="createForm.files" />
        </t-form-item>
      </t-form>
    </t-dialog>

    <!-- 发布新版本（multipart 发版；frontmatter name 须与首版一致） -->
    <t-dialog
      v-model:visible="publishVisible"
      :header="`发布新版本（${publishTarget?.display_title ?? ''}）`"
      width="640px"
      :confirm-btn="{ content: '发布', loading: submitting }"
      :cancel-btn="{}"
      @confirm="handlePublish"
    >
      <t-alert
        theme="info"
        message="版本不可变：发布生成新的 epoch 版本，旧版本仍可查询与被旧绑定引用；frontmatter name 须与首版一致。"
        class="form-alert"
      />
      <t-form :data="publishForm" layout="vertical" label-align="left">
        <t-form-item label="技能包">
          <SkillPackagePicker v-model="publishForm.files" />
        </t-form-item>
      </t-form>
    </t-dialog>

    <!-- 版本列表抽屉（游标 + zip 下载 + 删除版本） -->
    <t-drawer v-model:visible="versionsVisible" size="680px" :header="`版本列表（${versionsTarget?.display_title ?? ''}）`">
      <t-table :data="versions" :columns="versionColumns" row-key="id" :loading="versionsLoading" max-height="56vh">
        <template #version="{ row }">
          <span>{{ formatVersion(row.version) }}</span>
        </template>
        <template #op="{ row }">
          <div class="op-cell">
            <t-button size="small" variant="text" @click="handleDownload(row)">下载 zip</t-button>
            <t-popconfirm content="仍被 Agent 版本钉版绑定的版本将拒绝删除。确认删除该版本？" @confirm="handleDeleteVersion(row)">
              <t-button size="small" variant="text" theme="danger">删除</t-button>
            </t-popconfirm>
          </div>
        </template>
      </t-table>
      <div class="skill-more">
        <t-button v-if="versionsHasMore" variant="text" :loading="versionsLoading" @click="loadMoreVersions">
          加载更多
        </t-button>
      </div>
    </t-drawer>
  </PageSection>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { MessagePlugin, type PrimaryTableCol } from 'tdesign-vue-next';
import PageSection from '@/shared/components/PageSection.vue';
import SkillPackagePicker from '../components/SkillPackagePicker.vue';
import {
  SKILL_SOURCE_OPTIONS,
  createSkill,
  createSkillVersion,
  deleteSkill,
  deleteSkillVersion,
  downloadSkillVersion,
  listSkills,
  listSkillVersions,
  type SkillDto,
  type SkillSourceValue,
  type SkillVersionDto,
} from '../api/skills';

const columns: PrimaryTableCol<SkillDto>[] = [
  { colKey: 'display_title', title: '展示名', ellipsis: true },
  { colKey: 'source', title: '来源', width: 90 },
  { colKey: 'latest_version', title: '最新版本', width: 200 },
  { colKey: 'updated_at', title: '更新时间', width: 180 },
  { colKey: 'op', title: '操作', width: 240 },
];

const versionColumns: PrimaryTableCol<SkillVersionDto>[] = [
  { colKey: 'version', title: '版本（epoch 微秒）', width: 200 },
  { colKey: 'name', title: 'frontmatter name', width: 140 },
  { colKey: 'description', title: '描述', ellipsis: true },
  { colKey: 'op', title: '操作', width: 160 },
];

const sourceFilterOptions = [{ label: '全部来源', value: '' }, ...SKILL_SOURCE_OPTIONS];

const skills = ref<SkillDto[]>([]);
const loading = ref(false);
const hasMore = ref(false);
const nextAfterId = ref<string | undefined>(undefined);
const keyword = ref('');
const sourceFilter = ref<'' | SkillSourceValue>('');

const submitting = ref(false);

const createVisible = ref(false);
const createForm = reactive<{ displayTitle: string; files: File[] }>({ displayTitle: '', files: [] });

const publishVisible = ref(false);
const publishTarget = ref<SkillDto | null>(null);
const publishForm = reactive<{ files: File[] }>({ files: [] });

const versionsVisible = ref(false);
const versionsLoading = ref(false);
const versionsTarget = ref<SkillDto | null>(null);
const versions = ref<SkillVersionDto[]>([]);
const versionsHasMore = ref(false);
const versionsAfterId = ref<string | undefined>(undefined);

/** 版本键展示：epoch 微秒 → 本地时间（无法解析时原样展示）。 */
function formatVersion(version: string): string {
  const micros = Number(version);
  if (!Number.isFinite(micros) || micros <= 0) {
    return version;
  }
  return new Date(micros / 1000).toLocaleString();
}

async function reload(append = false): Promise<void> {
  loading.value = true;
  try {
    const page = await listSkills({
      source: sourceFilter.value || undefined,
      keyword: keyword.value.trim() || undefined,
      limit: 20,
      afterId: append ? nextAfterId.value : undefined,
    });
    skills.value = append ? [...skills.value, ...page.data] : page.data;
    hasMore.value = page.has_more;
    nextAfterId.value = page.last_id ?? undefined;
  } catch (e) {
    MessagePlugin.error(`技能列表加载失败: ${(e as Error).message}`);
  } finally {
    loading.value = false;
  }
}

function loadMore(): void {
  void reload(true);
}

function openCreate(): void {
  createForm.displayTitle = '';
  createForm.files = [];
  createVisible.value = true;
}

async function handleCreate(): Promise<void> {
  if (createForm.files.length === 0) {
    MessagePlugin.warning('请选择 zip 技能包或技能目录');
    return;
  }
  submitting.value = true;
  try {
    await createSkill(createForm.files, createForm.displayTitle);
    createVisible.value = false;
    MessagePlugin.success('技能已创建');
    await reload();
  } catch (e) {
    MessagePlugin.error(`创建失败: ${(e as Error).message}`);
  } finally {
    submitting.value = false;
  }
}

function openPublish(row: SkillDto): void {
  publishTarget.value = row;
  publishForm.files = [];
  publishVisible.value = true;
}

async function handlePublish(): Promise<void> {
  const target = publishTarget.value;
  if (!target) {
    return;
  }
  if (publishForm.files.length === 0) {
    MessagePlugin.warning('请选择 zip 技能包或技能目录');
    return;
  }
  submitting.value = true;
  try {
    const created = await createSkillVersion(target.id, publishForm.files);
    publishVisible.value = false;
    MessagePlugin.success(`新版本已发布（${formatVersion(created.version)}）`);
    await reload();
  } catch (e) {
    MessagePlugin.error(`发布失败: ${(e as Error).message}`);
  } finally {
    submitting.value = false;
  }
}

async function openVersions(row: SkillDto): Promise<void> {
  versionsTarget.value = row;
  versions.value = [];
  versionsAfterId.value = undefined;
  versionsHasMore.value = false;
  versionsVisible.value = true;
  await refreshVersions(true);
}

async function refreshVersions(reset: boolean): Promise<void> {
  const target = versionsTarget.value;
  if (!target) {
    return;
  }
  versionsLoading.value = true;
  try {
    const page = await listSkillVersions(target.id, {
      limit: 20,
      afterId: reset ? undefined : versionsAfterId.value,
    });
    versions.value = reset ? page.data : [...versions.value, ...page.data];
    versionsHasMore.value = page.has_more;
    versionsAfterId.value = page.last_id ?? undefined;
  } catch (e) {
    MessagePlugin.error(`版本列表加载失败: ${(e as Error).message}`);
  } finally {
    versionsLoading.value = false;
  }
}

function loadMoreVersions(): void {
  void refreshVersions(false);
}

async function handleDownload(version: SkillVersionDto): Promise<void> {
  const target = versionsTarget.value;
  if (!target) {
    return;
  }
  try {
    await downloadSkillVersion(target.id, version.version);
  } catch (e) {
    MessagePlugin.error(`下载失败: ${(e as Error).message}`);
  }
}

async function handleDeleteVersion(version: SkillVersionDto): Promise<void> {
  const target = versionsTarget.value;
  if (!target) {
    return;
  }
  try {
    await deleteSkillVersion(target.id, version.version);
  } catch (e) {
    MessagePlugin.error(`版本删除失败: ${(e as Error).message}`);
    return;
  }
  MessagePlugin.success('版本已删除');
  await refreshVersions(true);
  await reload();
}

async function handleDelete(row: SkillDto): Promise<void> {
  try {
    await deleteSkill(row.id);
  } catch (e) {
    MessagePlugin.error(`技能删除失败: ${(e as Error).message}`);
    return;
  }
  MessagePlugin.success('技能已删除');
  await reload();
}

onMounted(() => {
  void reload();
});
</script>

<style scoped>
.skill-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
}

.skill-toolbar__search {
  width: 240px;
}

.skill-toolbar__source {
  width: 160px;
}

.skill-more {
  display: flex;
  justify-content: center;
  margin-top: 8px;
}

.op-cell {
  display: flex;
  align-items: center;
  gap: 4px;
}

.form-alert {
  margin-bottom: 12px;
}
</style>