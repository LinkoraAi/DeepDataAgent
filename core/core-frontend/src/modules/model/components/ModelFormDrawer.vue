<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import FormSection from '@/shared/components/FormSection.vue';
import { errorMessage, useAsync } from '@/shared/composables/useAsync';
import { API_FORMATS, MODEL_TYPES } from '@/shared/enums/dict';
import { MODEL_TYPE_EMBEDDING, createModelProfile, updateModelProfile } from '../api';
import type { ModelProfilePayload, ModelProfileResponse } from '../api';
import {
  buildModelProfilePayload,
  createEmptyModelForm,
  modelProfileFormFromResponse,
  validateModelForm,
} from '../mappers';
import type { ModelFormErrors, ModelFormState } from '../mappers';

/**
 * 模型配置创建/编辑抽屉。
 * 表单校验与载荷装配全部委托给 mappers 纯函数（凭证三态：留空不改 / 显式清空 / 新值替换）；
 * 后端 400/409 原因直显并保留抽屉，便于就地修正。
 */
const props = defineProps<{
  /** 抽屉可见性（v-model:visible） */
  visible: boolean;
  /** 编辑目标；null 表示新建 */
  profile: ModelProfileResponse | null;
}>();

const emit = defineEmits<{
  'update:visible': [visible: boolean];
  'saved': [profile: ModelProfileResponse];
}>();

const form = reactive<ModelFormState>(createEmptyModelForm());
const submitted = ref(false);
const task = useAsync<ModelProfileResponse>();

/** 提交中状态（顶层 ref，模板自动解包） */
const submitting = task.loading;

const isEdit = computed(() => Boolean(props.profile));
const errors = computed<ModelFormErrors>(() => validateModelForm({ ...form }));

/** API 格式下拉（大写枚举名，与后端 ApiFormat.valueOf 同口径） */
const apiFormatOptions = API_FORMATS.map((value) => ({ label: value, value }));

/** 模型类型单选 */
const modelTypeOptions = MODEL_TYPES.map((item) => ({ label: item.label, value: item.value }));

/** 是否嵌入类型（决定向量维度是否必填与可见） */
const isEmbedding = computed(() => form.modelType === MODEL_TYPE_EMBEDDING);

/** 凭证输入占位（编辑态明确"留空不修改"语义） */
const credentialPlaceholder = computed(() => {
  if (!isEdit.value) {
    return '可留空，表示该端点无需鉴权';
  }
  return props.profile?.credentialConfigured ? '留空表示不修改（当前已配置凭证）' : '留空表示不修改（当前无凭证）';
});

watch(
  () => props.visible,
  (open) => {
    if (!open) {
      return;
    }
    submitted.value = false;
    task.error.value = null;
    Object.assign(form, props.profile ? modelProfileFormFromResponse(props.profile) : createEmptyModelForm());
  },
);

/** 取字段错误（仅在首次提交尝试后展示，避免进入即满屏红）。 */
function errorOf(field: keyof ModelFormErrors): string | undefined {
  return submitted.value ? errors.value[field] : undefined;
}

/** 字段状态（TDesign form-item status）。 */
function statusOf(field: keyof ModelFormErrors): 'error' | undefined {
  return errorOf(field) ? 'error' : undefined;
}

function close(): void {
  emit('update:visible', false);
}

async function onSave(): Promise<void> {
  submitted.value = true;
  const state: ModelFormState = { ...form };
  const validation = validateModelForm(state);
  const messages = Object.values(validation);
  if (messages.length) {
    MessagePlugin.warning(messages[0]);
    return;
  }

  let payload: ModelProfilePayload;
  try {
    payload = buildModelProfilePayload(state, isEdit.value);
  } catch (e) {
    MessagePlugin.error(e instanceof Error ? e.message : '表单校验未通过');
    return;
  }

  const profileId = props.profile?.profileId;
  const saved = await task.run(() =>
    profileId ? updateModelProfile(profileId, payload) : createModelProfile(payload),
  );
  if (task.error.value) {
    MessagePlugin.error(errorMessage(task.error.value));
    return;
  }
  if (saved) {
    MessagePlugin.success(isEdit.value ? '模型配置已更新' : '模型配置已创建');
    emit('saved', saved);
    close();
  }
}
</script>

<template>
  <t-drawer
    :visible="visible"
    :header="isEdit ? '编辑模型配置' : '新建模型配置'"
    size="640px"
    placement="right"
    :close-btn="true"
    :confirm-on-close="false"
    @close="close"
  >
    <t-form layout="vertical" :data="form">
      <FormSection title="基本信息" hint="显示名称全局唯一；重复时后端返回冲突原因">
        <t-form-item label="显示名称" name="displayName" :status="statusOf('displayName')" :tips="errorOf('displayName')">
          <t-input v-model="form.displayName" :maxlength="32" show-limit-number placeholder="中文或英文字母开头，≤32 字符" />
        </t-form-item>
        <t-form-item label="描述" name="description" :status="statusOf('description')" :tips="errorOf('description')">
          <t-textarea v-model="form.description" :maxlength="500" placeholder="用途说明（可选，≤500 字符）" />
        </t-form-item>
        <t-form-item label="模型类型" name="modelType" :status="statusOf('modelType')" :tips="errorOf('modelType')">
          <t-radio-group v-model="form.modelType" :options="modelTypeOptions" />
        </t-form-item>
        <t-form-item
          v-if="isEmbedding"
          label="向量维度"
          name="vectorDimension"
          :status="statusOf('vectorDimension')"
          :tips="errorOf('vectorDimension') || '嵌入模型必填，需与知识库切片维度一致'"
        >
          <t-input-number
            v-model="form.vectorDimension"
            :min="1"
            :decimal-places="0"
            theme="column"
            placeholder="如 1024"
          />
        </t-form-item>
      </FormSection>

      <FormSection title="接入配置" hint="凭证经独立密钥加密落库，界面与接口均不回显">
        <t-form-item label="API 格式" name="apiFormat" :status="statusOf('apiFormat')" :tips="errorOf('apiFormat')">
          <t-select v-model="form.apiFormat" :options="apiFormatOptions" placeholder="选择协议格式（大写枚举名）" />
        </t-form-item>
        <t-form-item
          label="API 端点 URL"
          name="apiEndpointUrl"
          :status="statusOf('apiEndpointUrl')"
          :tips="errorOf('apiEndpointUrl')"
        >
          <t-input v-model="form.apiEndpointUrl" :maxlength="512" placeholder="https://api.example.com/v1" />
        </t-form-item>
        <t-form-item label="模型名称" name="modelName" :status="statusOf('modelName')" :tips="errorOf('modelName')">
          <t-input v-model="form.modelName" :maxlength="128" placeholder="调用时传给端点的模型标识" />
        </t-form-item>
        <t-form-item label="凭证" name="credential" :status="statusOf('credential')" :tips="errorOf('credential')">
          <div class="model-drawer__credential">
            <t-input
              v-model="form.credential"
              type="password"
              :placeholder="credentialPlaceholder"
              :disabled="form.clearCredential"
            />
            <t-checkbox v-if="isEdit" v-model="form.clearCredential">清空凭证</t-checkbox>
          </div>
        </t-form-item>
        <t-form-item label="模型系列" name="modelSeries" :status="statusOf('modelSeries')" :tips="errorOf('modelSeries')">
          <t-input v-model="form.modelSeries" :maxlength="64" placeholder="如 gpt-4o（可选）" />
        </t-form-item>
      </FormSection>

      <FormSection title="能力参数" hint="留空表示不限制；工具调用轮次留空沿用后端默认">
        <t-form-item
          label="输入上下文窗口"
          name="contextWindowInput"
          :status="statusOf('contextWindowInput')"
          :tips="errorOf('contextWindowInput')"
        >
          <t-input-number
            v-model="form.contextWindowInput"
            :min="0"
            :decimal-places="0"
            theme="column"
            placeholder="token 数（≥0）"
          />
        </t-form-item>
        <t-form-item
          label="输出上下文窗口"
          name="contextWindowOutput"
          :status="statusOf('contextWindowOutput')"
          :tips="errorOf('contextWindowOutput')"
        >
          <t-input-number
            v-model="form.contextWindowOutput"
            :min="0"
            :decimal-places="0"
            theme="column"
            placeholder="token 数（≥0）"
          />
        </t-form-item>
        <t-form-item
          label="工具调用轮次"
          name="toolCallRounds"
          :status="statusOf('toolCallRounds')"
          :tips="errorOf('toolCallRounds')"
        >
          <t-input-number v-model="form.toolCallRounds" :min="1" :decimal-places="0" theme="column" placeholder="正整数" />
        </t-form-item>
      </FormSection>
    </t-form>

    <template #footer>
      <div class="model-drawer__footer">
        <t-button theme="default" variant="base" @click="close">取消</t-button>
        <t-button theme="primary" :loading="submitting" @click="onSave">{{ isEdit ? '保存' : '创建' }}</t-button>
      </div>
    </template>
  </t-drawer>
</template>

<style scoped>
.model-drawer__credential {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
}

.model-drawer__footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
