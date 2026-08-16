<template>
  <PageSection title="模型配置管理" description="登记模型提供方（API 格式 / 端点 / 模型名 / 凭证），供 Agent 版本引用。">
    <t-card :bordered="true">
      <div class="profile-toolbar">
        <t-input
          v-model="keyword"
          placeholder="按名称搜索"
          clearable
          class="profile-toolbar__search"
          @enter="reload"
        />
        <t-button theme="primary" @click="reload">搜索</t-button>
        <t-button theme="primary" variant="outline" @click="openDialog(null)">新建模型配置</t-button>
      </div>

      <t-table
        :data="profiles"
        :columns="columns"
        row-key="profileId"
        :loading="loading"
        :pagination="pagination"
        :hover="true"
        @page-change="onPageChange"
      >
        <template #status="{ row }">
          <t-tag :theme="row.status === 'ENABLED' ? 'success' : 'default'" variant="light">
            {{ row.status === 'ENABLED' ? '启用' : '禁用' }}
          </t-tag>
        </template>
        <template #credentialConfigured="{ row }">
          <span>{{ row.credentialConfigured ? '已配置' : '无' }}</span>
        </template>
        <template #op="{ row }">
          <div class="op-cell">
            <t-button size="small" variant="text" :disabled="row.status !== 'ENABLED'" @click="openDialog(row)">
              编辑
            </t-button>
            <t-button
              size="small"
              variant="text"
              theme="warning"
              :disabled="row.status !== 'ENABLED'"
              @click="handleDisable(row)"
            >
              禁用
            </t-button>
            <t-button size="small" variant="text" :disabled="row.status === 'ENABLED'" @click="handleEnable(row)">
              启用
            </t-button>
            <t-popconfirm content="删除被 Agent 引用的配置将失败（409），确认删除？" @confirm="handleDelete(row)">
              <t-button size="small" variant="text" theme="danger">删除</t-button>
            </t-popconfirm>
          </div>
        </template>
      </t-table>
    </t-card>

    <!-- 创建 / 编辑共用表单 -->
    <t-dialog
      v-model:visible="formVisible"
      :header="editing ? '编辑模型配置' : '新建模型配置'"
      width="620px"
      :confirm-btn="{ content: '保存', loading: submitting }"
      :cancel-btn="{}"
      @confirm="handleSubmit"
    >
      <t-form ref="formRef" :data="form" :rules="formRules" layout="vertical" label-align="left">
        <t-form-item label="显示名称" name="displayName">
          <t-input v-model="form.displayName" placeholder="≤32 字符" />
        </t-form-item>
        <t-form-item label="API 格式" name="apiFormat">
          <t-select v-model="form.apiFormat" :options="formatOptions" placeholder="选择 API 格式" />
        </t-form-item>
        <t-form-item label="模型名称" name="modelName">
          <t-input v-model="form.modelName" placeholder="如 gpt-4 / dashscope:qwen-plus" />
        </t-form-item>
        <t-form-item label="API 端点 URL" name="apiEndpointUrl">
          <t-input v-model="form.apiEndpointUrl" placeholder="https://api.example.com/v1" />
        </t-form-item>
        <t-form-item label="凭证" name="credential">
          <t-input v-model="form.credential" type="password" placeholder="编辑时留空 = 保留原凭证；空串 = 清空" />
        </t-form-item>
        <t-form-item label="描述" name="description">
          <t-input v-model="form.description" placeholder="可选" />
        </t-form-item>
        <div class="form-row">
          <t-form-item label="工具调用轮次" name="toolCallRounds" class="form-row__item">
            <t-input-number v-model="form.toolCallRounds" :min="1" :max="100" />
          </t-form-item>
          <t-form-item label="模型类型" name="modelType" class="form-row__item">
            <t-select v-model="form.modelType" :options="modelTypeOptions" />
          </t-form-item>
        </div>
        <div class="form-row">
          <t-form-item label="输入上下文窗口" name="contextWindowInput" class="form-row__item">
            <t-input-number v-model="form.contextWindowInput" :min="0" />
          </t-form-item>
          <t-form-item label="输出上下文窗口" name="contextWindowOutput" class="form-row__item">
            <t-input-number v-model="form.contextWindowOutput" :min="0" />
          </t-form-item>
        </div>
        <t-form-item v-if="form.modelType === EMBEDDING_TYPE" label="向量维度" name="vectorDimension">
          <t-input-number v-model="form.vectorDimension" :min="1" placeholder="EMBEDDING 模型必填" />
        </t-form-item>
      </t-form>
    </t-dialog>
  </PageSection>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import type { FormInstanceFunctions, FormRule, PrimaryTableCol } from 'tdesign-vue-next';
import PageSection from '@/shared/components/PageSection.vue';
import {
  API_FORMATS,
  createModelProfile,
  deleteModelProfile,
  disableModelProfile,
  enableModelProfile,
  listModelProfiles,
  updateModelProfile,
  type ModelProfileDto,
  type ModelProfilePayload,
} from '../api/model-profiles';

/** 模型类型（1=CHAT 2=EMBEDDING）。 */
const CHAT_TYPE = 1;
const EMBEDDING_TYPE = 2;

const columns: PrimaryTableCol<ModelProfileDto>[] = [
  { colKey: 'displayName', title: '名称', ellipsis: true },
  { colKey: 'apiFormat', title: 'API 格式' },
  { colKey: 'modelName', title: '模型名称', ellipsis: true },
  { colKey: 'credentialConfigured', title: '凭证' },
  { colKey: 'toolCallRounds', title: '轮次上限' },
  { colKey: 'status', title: '状态' },
  { colKey: 'createdAt', title: '创建时间' },
  { colKey: 'op', title: '操作', width: 240 },
];

const formatOptions = API_FORMATS.map((format) => ({ label: format, value: format }));
const modelTypeOptions = [
  { label: '对话（CHAT）', value: CHAT_TYPE },
  { label: '向量嵌入（EMBEDDING）', value: EMBEDDING_TYPE },
];

const profiles = ref<ModelProfileDto[]>([]);
const loading = ref(false);
const keyword = ref('');
const pagination = reactive({ current: 1, pageSize: 20, total: 0 });

const formVisible = ref(false);
const submitting = ref(false);
const editing = ref<ModelProfileDto | null>(null);
const formRef = ref<FormInstanceFunctions>();

const form = reactive<ModelProfilePayload>({
  displayName: '',
  apiFormat: 'OPENAI',
  apiEndpointUrl: '',
  modelName: '',
  credential: null,
  description: '',
  toolCallRounds: 20,
  modelType: CHAT_TYPE,
  contextWindowInput: null,
  contextWindowOutput: null,
  vectorDimension: null,
});

const formRules = computed<Record<string, FormRule[]>>(() => ({
  displayName: [{ required: true, message: '显示名称不能为空' }],
  apiFormat: [{ required: true, message: '请选择 API 格式' }],
  modelName: [{ required: true, message: '模型名称不能为空' }],
  apiEndpointUrl: [{ required: true, message: 'API 端点 URL 不能为空' }],
  ...(form.modelType === EMBEDDING_TYPE
    ? { vectorDimension: [{ required: true, message: 'EMBEDDING 模型必须配置向量维度' }] }
    : {}),
}));

/** 新建 / 编辑装载。 */
function openDialog(profile: ModelProfileDto | null): void {
  editing.value = profile;
  if (profile) {
    form.displayName = profile.displayName;
    form.apiFormat = profile.apiFormat;
    form.apiEndpointUrl = profile.apiEndpointUrl;
    form.modelName = profile.modelName;
    form.credential = null; // null 表示保留原凭证
    form.description = profile.description ?? '';
    form.toolCallRounds = profile.toolCallRounds ?? 20;
    form.modelType = profile.modelType ?? CHAT_TYPE;
    form.contextWindowInput = profile.contextWindowInput;
    form.contextWindowOutput = profile.contextWindowOutput;
    form.vectorDimension = profile.vectorDimension;
  } else {
    form.displayName = '';
    form.apiFormat = 'OPENAI';
    form.apiEndpointUrl = '';
    form.modelName = '';
    form.credential = '';
    form.description = '';
    form.toolCallRounds = 20;
    form.modelType = CHAT_TYPE;
    form.contextWindowInput = null;
    form.contextWindowOutput = null;
    form.vectorDimension = null;
  }
  formVisible.value = true;
}

async function reload(): Promise<void> {
  loading.value = true;
  try {
    const page = await listModelProfiles({
      keyword: keyword.value,
      page: pagination.current,
      size: pagination.pageSize,
    });
    profiles.value = page.list;
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

async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate();
  if (valid !== true) {
    return;
  }
  submitting.value = true;
  try {
    const payload: ModelProfilePayload = {
      displayName: form.displayName,
      apiFormat: form.apiFormat,
      apiEndpointUrl: form.apiEndpointUrl,
      modelName: form.modelName,
      credential: form.credential,
      description: form.description?.trim() || null,
      toolCallRounds: form.toolCallRounds,
      modelType: form.modelType,
      contextWindowInput: form.contextWindowInput,
      contextWindowOutput: form.contextWindowOutput,
      vectorDimension: form.modelType === EMBEDDING_TYPE ? form.vectorDimension : null,
    };
    if (editing.value) {
      await updateModelProfile(editing.value.profileId, payload);
    } else {
      await createModelProfile(payload);
    }
    formVisible.value = false;
    await reload();
  } finally {
    submitting.value = false;
  }
}

async function handleDisable(profile: ModelProfileDto): Promise<void> {
  await disableModelProfile(profile.profileId);
  await reload();
}

async function handleEnable(profile: ModelProfileDto): Promise<void> {
  await enableModelProfile(profile.profileId);
  await reload();
}

async function handleDelete(profile: ModelProfileDto): Promise<void> {
  await deleteModelProfile(profile.profileId);
  await reload();
}

onMounted(() => {
  void reload();
});
</script>

<style scoped>
.profile-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
}

.profile-toolbar__search {
  width: 240px;
}

.op-cell {
  display: flex;
  align-items: center;
  gap: 4px;
}

.form-row {
  display: flex;
  gap: 16px;
}

.form-row__item {
  flex: 1;
}
</style>