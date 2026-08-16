<template>
  <PageSection title="Agent 管理" description="创建 Agent（自动生成 v1）、发布新版本、归档与删除。">
    <t-card :bordered="true">
      <div class="agent-toolbar">
        <t-input
          v-model="keyword"
          placeholder="按名称搜索"
          clearable
          class="agent-toolbar__search"
          @enter="reload"
        />
        <t-button theme="primary" @click="reload">搜索</t-button>
        <t-button theme="primary" variant="outline" @click="openCreate">创建 Agent</t-button>
      </div>

      <t-table
        :data="agents"
        :columns="columns"
        row-key="agentId"
        :loading="loading"
        :pagination="pagination"
        :hover="true"
        @page-change="onPageChange"
      >
        <template #archived="{ row }">
          <t-tag :theme="row.archived ? 'default' : 'success'" variant="light">
            {{ row.archived ? '已归档' : '运行中' }}
          </t-tag>
        </template>
        <template #latestVersion="{ row }">
          <span>{{ row.latestVersion > 0 ? `v${row.latestVersion}` : '未发布' }}</span>
        </template>
        <template #op="{ row }">
          <div class="op-cell">
            <t-button size="small" variant="text" :disabled="row.archived" @click="openPublish(row)">
              发布版本
            </t-button>
            <t-button size="small" variant="text" @click="openVersions(row)">版本列表</t-button>
            <t-popconfirm content="归档后不可创建新会话，确认归档？" @confirm="handleArchive(row)">
              <t-button size="small" variant="text" theme="warning" :disabled="row.archived">
                归档
              </t-button>
            </t-popconfirm>
            <t-popconfirm content="删除将级联清除全部版本，确认删除？" @confirm="handleDelete(row)">
              <t-button size="small" variant="text" theme="danger">删除</t-button>
            </t-popconfirm>
          </div>
        </template>
      </t-table>
    </t-card>

    <!-- 创建 / 发布版本共用表单 -->
    <t-dialog
      v-model:visible="formVisible"
      :header="formMode === 'create' ? '创建 Agent' : `发布版本（${formTarget?.name ?? ''}）`"
      width="560px"
      :confirm-btn="{ content: formMode === 'create' ? '创建' : '发布', loading: submitting }"
      :cancel-btn="{}"
      @confirm="handleSubmit"
    >
      <t-form ref="formRef" :data="form" :rules="formRules" layout="vertical" label-align="left">
        <t-form-item label="名称" name="name">
          <t-input v-model="form.name" placeholder="Agent/版本名称（≤64 字符）" />
        </t-form-item>
        <t-form-item label="模型配置" name="modelProfileId">
          <t-select
            v-model="form.modelProfileId"
            :options="profileOptions"
            placeholder="选择启用的模型配置"
            clearable
          />
        </t-form-item>
        <t-form-item label="描述" name="description">
          <t-textarea v-model="form.description" :autosize="{ minRows: 2, maxRows: 4 }" placeholder="可选" />
        </t-form-item>
        <t-form-item label="系统提示词" name="system">
          <t-textarea v-model="form.system" :autosize="{ minRows: 3, maxRows: 8 }" placeholder="运行装配 source（可选）" />
        </t-form-item>
        <t-form-item label="推理参数" name="inferenceParams">
          <t-input v-model="form.inferenceParams" placeholder='JSONB 字符串，如 {"temperature": 0.7}（可选）' />
        </t-form-item>
        <t-form-item label="挂载技能" name="skillIds">
          <t-input v-model="form.skillIds" placeholder='技能引用 JSON，如 [{"skillId":"s-1","version":1}]（可选）' />
        </t-form-item>
      </t-form>
    </t-dialog>

    <!-- 版本列表 -->
    <t-drawer v-model:visible="versionsVisible" size="560px" :header="`版本列表（${versionsAgentName}）`">
      <t-table
        :data="versions"
        :columns="versionColumns"
        row-key="versionId"
        max-height="60vh"
      >
        <template #system="{ row }">
          <span class="ellipsis">{{ row.system || '—' }}</span>
        </template>
      </t-table>
    </t-drawer>
  </PageSection>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import type { FormInstanceFunctions, FormRule, PrimaryTableCol } from 'tdesign-vue-next';
import PageSection from '@/shared/components/PageSection.vue';
import {
  archiveAgent,
  createAgent,
  deleteAgent,
  listAgentVersions,
  listAgents,
  publishAgentVersion,
  type AgentConfigPayload,
  type AgentDto,
  type AgentVersionDto,
} from '../api/agents';
import { listModelProfiles, type ModelProfileDto } from '../api/model-profiles';

/** 表格列定义。 */
const columns: PrimaryTableCol<AgentDto>[] = [
  { colKey: 'name', title: '名称', ellipsis: true },
  { colKey: 'latestVersion', title: '最新版本' },
  { colKey: 'archived', title: '状态' },
  { colKey: 'createdAt', title: '创建时间' },
  { colKey: 'op', title: '操作', width: 280 },
];

const versionColumns: PrimaryTableCol<AgentVersionDto>[] = [
  { colKey: 'versionNumber', title: '版本', width: 80 },
  { colKey: 'name', title: '名称' },
  { colKey: 'modelProfileId', title: '模型配置' },
  { colKey: 'system', title: '系统提示词' },
  { colKey: 'createdAt', title: '发布时间' },
];

const agents = ref<AgentDto[]>([]);
const loading = ref(false);
const keyword = ref('');
const pagination = reactive({ current: 1, pageSize: 20, total: 0 });

const formVisible = ref(false);
const formMode = ref<'create' | 'publish'>('create');
const formTarget = ref<AgentDto | null>(null);
const formRef = ref<FormInstanceFunctions>();
const submitting = ref(false);
const profiles = ref<ModelProfileDto[]>([]);

const versionsVisible = ref(false);
const versions = ref<AgentVersionDto[]>([]);
const versionsAgentName = ref('');

const form = reactive<AgentConfigPayload>({ name: '', modelProfileId: '', description: '', system: '', inferenceParams: '', skillIds: '' });

const formRules: Record<string, FormRule[]> = {
  name: [{ required: true, message: '名称不能为空' }],
  modelProfileId: [{ required: true, message: '请选择模型配置' }],
};

const profileOptions = computed(() =>
  profiles.value.map((profile) => ({
    label: `${profile.displayName}（${profile.modelName}）`,
    value: profile.profileId,
  })),
);

/** 加载 Agent 列表（含启用状态的模型配置下拉数据）。 */
async function reload(): Promise<void> {
  loading.value = true;
  try {
    const page = await listAgents({
      keyword: keyword.value,
      page: pagination.current,
      size: pagination.pageSize,
    });
    agents.value = page.list;
    pagination.total = page.total;
  } finally {
    loading.value = false;
  }
}

async function loadProfiles(): Promise<void> {
  const page = await listModelProfiles({ status: 'ENABLED', page: 1, size: 100 });
  profiles.value = page.list;
}

function onPageChange(pageInfo: { current: number; pageSize: number }): void {
  pagination.current = pageInfo.current;
  pagination.pageSize = pageInfo.pageSize;
  void reload();
}

function resetForm(): void {
  form.name = '';
  form.modelProfileId = '';
  form.description = '';
  form.system = '';
  form.inferenceParams = '';
  form.skillIds = '';
}

function openCreate(): void {
  formMode.value = 'create';
  formTarget.value = null;
  resetForm();
  formVisible.value = true;
}

function openPublish(agent: AgentDto): void {
  formMode.value = 'publish';
  formTarget.value = agent;
  resetForm();
  form.name = `v${agent.latestVersion + 1}`;
  formVisible.value = true;
}

async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate();
  if (valid !== true) {
    return;
  }
  submitting.value = true;
  try {
    const payload: AgentConfigPayload = {
      name: form.name,
      modelProfileId: form.modelProfileId,
      description: form.description?.trim() || null,
      system: form.system?.trim() || undefined,
      inferenceParams: form.inferenceParams?.trim() || null,
      skillIds: form.skillIds?.trim() || null,
    };
    if (formMode.value === 'create') {
      await createAgent(payload);
    } else if (formTarget.value) {
      await publishAgentVersion(formTarget.value.agentId, payload);
    }
    formVisible.value = false;
    await reload();
  } finally {
    submitting.value = false;
  }
}

async function handleArchive(agent: AgentDto): Promise<void> {
  await archiveAgent(agent.agentId);
  await reload();
}

async function handleDelete(agent: AgentDto): Promise<void> {
  await deleteAgent(agent.agentId);
  await reload();
}

async function openVersions(agent: AgentDto): Promise<void> {
  versionsAgentName.value = agent.name;
  versions.value = await listAgentVersions(agent.agentId);
  versionsVisible.value = true;
}

onMounted(async () => {
  await Promise.all([reload(), loadProfiles()]);
});
</script>

<style scoped>
.agent-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
}

.agent-toolbar__search {
  width: 240px;
}

.op-cell {
  display: flex;
  align-items: center;
  gap: 4px;
}

.ellipsis {
  display: block;
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>