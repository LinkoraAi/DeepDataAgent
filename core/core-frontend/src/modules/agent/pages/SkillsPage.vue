<template>
  <PageSection title="技能管理" description="上传技能包（zip）并发布版本，供 Agent 版本挂载。">
    <t-card :bordered="true">
      <div class="skill-toolbar">
        <t-input
          v-model="keyword"
          placeholder="按名称搜索"
          clearable
          class="skill-toolbar__search"
          @enter="reload"
        />
        <t-button theme="primary" @click="reload">搜索</t-button>
        <t-button theme="primary" variant="outline" @click="openUpload(null)">上传技能</t-button>
      </div>

      <t-table
        :data="skills"
        :columns="columns"
        row-key="skillId"
        :loading="loading"
        :pagination="pagination"
        :hover="true"
        @page-change="onPageChange"
      >
        <template #versionNumber="{ row }">
          <span>v{{ row.versionNumber }}</span>
        </template>
        <template #status="{ row }">
          <t-tag :theme="row.status === 'ACTIVE' ? 'success' : 'default'" variant="light">
            {{ row.status === 'ACTIVE' ? '已发布' : row.status }}
          </t-tag>
        </template>
        <template #op="{ row }">
          <div class="op-cell">
            <t-button size="small" variant="text" @click="openUpload(row)">发布版本</t-button>
            <t-button size="small" variant="text" @click="openVersions(row)">版本列表</t-button>
            <t-popconfirm content="删除将级联清除全部版本与内容，确认删除？" @confirm="handleDelete(row)">
              <t-button size="small" variant="text" theme="danger">删除</t-button>
            </t-popconfirm>
          </div>
        </template>
      </t-table>
    </t-card>

    <!-- 上传 / 发布版本共用对话框 -->
    <t-dialog
      v-model:visible="uploadVisible"
      :header="uploadTarget ? `发布新版本（${uploadTarget.name}）` : '上传技能'"
      width="520px"
      :confirm-btn="{ content: uploadTarget ? '发布' : '上传', loading: uploading }"
      :cancel-btn="{}"
      :confirm-disabled="!uploadFile"
      @confirm="handleUpload"
    >
      <t-form :data="uploadForm" :rules="uploadRules" layout="vertical" label-align="left">
        <t-form-item label="技能包（zip）" name="file">
          <div class="file-picker">
            <t-button variant="outline" @click="pickFile">选择文件</t-button>
            <span class="file-picker__name">{{ uploadFile ? `${uploadFile.name}（${formatSize(uploadFile.size)}）` : '未选择' }}</span>
            <input
              ref="fileInputRef"
              type="file"
              accept=".zip,application/zip"
              style="display: none"
              @change="onFileChange"
            />
          </div>
        </t-form-item>
        <t-form-item label="名称" name="name">
          <t-input v-model="uploadForm.name" placeholder="技能名称（≤255 字符）" />
        </t-form-item>
        <t-form-item label="描述" name="description">
          <t-input v-model="uploadForm.description" placeholder="可选" />
        </t-form-item>
        <t-form-item label="技能类型" name="skillType">
          <t-select v-model="uploadForm.skillType" :options="typeOptions" />
        </t-form-item>
        <t-form-item label="SHA-256">
          <div class="sha-row">
            <t-input v-model="sha256" readonly placeholder="选择文件后自动计算" />
            <t-tooltip content="客户端声明的校验值，服务端会再次计算核对">
              <t-tag variant="light">校验</t-tag>
            </t-tooltip>
          </div>
        </t-form-item>
      </t-form>
    </t-dialog>

    <!-- 版本列表 -->
    <t-drawer v-model:visible="versionsVisible" size="560px" :header="`版本列表（${versionsSkillName}）`">
      <t-table
        :data="versions"
        :columns="versionColumns"
        row-key="versionNumber"
        max-height="60vh"
      >
        <template #contentSize="{ row }">
          <span>{{ formatSize(row.contentSize) }}</span>
        </template>
        <template #op="{ row }">
          <t-button size="small" variant="text" @click="handleDownload(row)">下载</t-button>
        </template>
      </t-table>
    </t-drawer>
  </PageSection>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import type { FormRule, PrimaryTableCol } from 'tdesign-vue-next';
import PageSection from '@/shared/components/PageSection.vue';
import {
  createSkill,
  deleteSkill,
  downloadSkillContent,
  getSkill,
  listSkills,
  publishSkillVersion,
  type SkillDetailDto,
  type SkillDto,
  type SkillMetaPayload,
} from '../api/skills';

const columns: PrimaryTableCol<SkillDto>[] = [
  { colKey: 'name', title: '名称', ellipsis: true },
  { colKey: 'versionNumber', title: '最新版本' },
  { colKey: 'skillType', title: '类型' },
  { colKey: 'storageType', title: '存储' },
  { colKey: 'contentSize', title: '大小' },
  { colKey: 'status', title: '状态' },
  { colKey: 'op', title: '操作', width: 220 },
];

const versionColumns: PrimaryTableCol<SkillDto>[] = [
  { colKey: 'versionNumber', title: '版本', width: 80 },
  { colKey: 'name', title: '名称' },
  { colKey: 'contentSize', title: '大小' },
  { colKey: 'contentSha256', title: 'SHA-256', ellipsis: true },
  { colKey: 'createdAt', title: '发布时间' },
  { colKey: 'op', title: '操作', width: 80 },
];

const typeOptions = [
  { label: '自定义', value: 1 },
  { label: '官方', value: 2 },
];

const skills = ref<SkillDto[]>([]);
const loading = ref(false);
const keyword = ref('');
const pagination = reactive({ current: 1, pageSize: 20, total: 0 });

const uploadVisible = ref(false);
const uploading = ref(false);
const uploadTarget = ref<SkillDto | null>(null);
const uploadFile = ref<File | null>(null);
const sha256 = ref('');
const fileInputRef = ref<HTMLInputElement | null>(null);

const uploadForm = reactive<SkillMetaPayload>({ name: '', description: '', skillType: 1, sha256: undefined });

const uploadRules: Record<string, FormRule[]> = {
  name: [{ required: true, message: '技能名称不能为空' }],
};

const versionsVisible = ref(false);
const versions = ref<SkillDto[]>([]);
const versionsSkillName = ref('');

/** 清空上传态。 */
function resetUpload(target: SkillDto | null): void {
  uploadTarget.value = target;
  uploadFile.value = null;
  sha256.value = '';
  uploadForm.name = '';
  uploadForm.description = '';
  uploadForm.skillType = 1;
}

function openUpload(target: SkillDto | null): void {
  resetUpload(target);
  uploadVisible.value = true;
}

function pickFile(): void {
  fileInputRef.value?.click();
}

function onFileChange(event: Event): void {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0] ?? null;
  input.value = '';
  uploadFile.value = file;
  if (file) {
    void computeSha256(file).then((digest) => {
      sha256.value = digest;
      uploadForm.sha256 = digest;
    });
  } else {
    sha256.value = '';
    uploadForm.sha256 = undefined;
  }
}

/** 计算文件 SHA-256（Web Crypto）。 */
async function computeSha256(file: File): Promise<string> {
  const buffer = await file.arrayBuffer();
  const digest = await crypto.subtle.digest('SHA-256', buffer);
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('');
}

async function handleUpload(): Promise<void> {
  if (!uploadFile.value) {
    return;
  }
  uploading.value = true;
  try {
    const meta: SkillMetaPayload = {
      name: uploadForm.name,
      description: uploadForm.description?.trim() || null,
      skillType: uploadForm.skillType,
      sha256: sha256.value || undefined,
    };
    if (uploadTarget.value) {
      await publishSkillVersion(uploadTarget.value.skillId, uploadFile.value, meta);
    } else {
      await createSkill(uploadFile.value, meta);
    }
    uploadVisible.value = false;
    await reload();
  } finally {
    uploading.value = false;
  }
}

async function reload(): Promise<void> {
  loading.value = true;
  try {
    const page = await listSkills({ keyword: keyword.value, page: pagination.current, size: pagination.pageSize });
    skills.value = page.list;
    pagination.total = page.total;
  } finally {
    loading.value = false;
  }
}

function onPageChange(pageInfo: { current: number; pageSize: number }): void {
  pagination.current = pageInfo.current;
  pagination.pageSize = pageInfo.pageSize;
  void reload();
}

async function openVersions(skill: SkillDto): Promise<void> {
  versionsSkillName.value = skill.name;
  const detail: SkillDetailDto = await getSkill(skill.skillId);
  versions.value = detail.versions;
  versionsVisible.value = true;
}

async function handleDownload(version: SkillDto): Promise<void> {
  await downloadSkillContent(version.skillId, version.versionNumber);
}

async function handleDelete(skill: SkillDto): Promise<void> {
  await deleteSkill(skill.skillId);
  await reload();
}

function formatSize(bytes: number | null | undefined): string {
  const size = bytes ?? 0;
  if (size < 1024) {
    return `${size} B`;
  }
  if (size < 1024 * 1024) {
    return `${(size / 1024).toFixed(1)} KB`;
  }
  return `${(size / 1024 / 1024).toFixed(2)} MB`;
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

.op-cell {
  display: flex;
  align-items: center;
  gap: 4px;
}

.file-picker {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
}

.file-picker__name {
  font-size: 13px;
  color: var(--td-text-color-secondary);
}

.sha-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}
</style>