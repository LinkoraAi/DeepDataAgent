<template>
  <t-dialog
    :visible="visible"
    :header="isEdit ? '编辑模型配置' : '添加模型配置'"
    :confirm-btn="{ loading: submitting }"
    @confirm="handleSubmit"
    @close="handleClose"
    @update:visible="handleVisibleUpdate"
  >
    <t-form ref="formRef" :data="formData" :rules="formRules" label-width="100px">
      <!-- 配置方式 -->
      <t-form-item label="配置方式" name="mode">
        <t-radio-group v-model="formData.mode" :disabled="isEdit">
          <t-radio-button value="preset">模型服务商</t-radio-button>
          <t-radio-button value="custom">自定义配置</t-radio-button>
        </t-radio-group>
      </t-form-item>

      <!-- Tab 1: 模型服务商 -->
      <template v-if="formData.mode === 'preset'">
        <t-form-item label="服务商" name="providerKey">
          <t-select
            v-model="formData.providerKey"
            placeholder="请选择服务商"
            :disabled="isEdit"
            @change="handleProviderChange"
          >
            <t-option
              v-for="provider in providers"
              :key="provider.providerKey"
              :value="provider.providerKey"
              :label="provider.name"
            />
          </t-select>
        </t-form-item>

        <t-form-item label="模型 ID" name="modelKey">
          <t-input
            v-model="formData.modelKey"
            placeholder="请输入模型 ID（如 qwen3.7-max、gpt-4o）"
            :disabled="isEdit"
          />
        </t-form-item>

        <!-- 编辑模式：API Key 脱敏展示 -->
        <t-form-item v-if="isEdit" label="API Key" name="apiKey">
          <t-input
            v-model="formData.apiKey"
            :type="showApiKey ? 'text' : 'password'"
            placeholder="......"
            readonly
          >
            <template #suffix-icon>
              <t-icon
                :name="showApiKey ? 'browse-off' : 'browse'"
                style="cursor: pointer"
                @click="showApiKey = !showApiKey"
              />
            </template>
          </t-input>
        </t-form-item>

        <!-- 新增模式：API Key 可输入 -->
        <t-form-item v-else label="API Key" name="apiKey">
          <t-input
            v-model="formData.apiKey"
            type="password"
            placeholder="请输入 API Key"
          />
        </t-form-item>

        <t-form-item label="设为默认">
          <t-switch v-model="formData.setDefault" />
        </t-form-item>
      </template>

      <!-- Tab 2: 自定义配置 -->
      <template v-if="formData.mode === 'custom'">
        <t-form-item label="API 格式" name="apiFormat">
          <t-radio-group v-model="formData.apiFormat" :disabled="isEdit">
            <t-radio-button value="openai">OpenAI</t-radio-button>
            <t-radio-button value="anthropic">Anthropic</t-radio-button>
          </t-radio-group>
        </t-form-item>

        <t-form-item label="接口地址" name="baseUrl">
          <t-input
            v-model="formData.baseUrl"
            :placeholder="isEdit ? '留空表示不修改' : '如 https://api.openai.com/v1'"
            :disabled="isEdit && !formData.baseUrl"
          />
        </t-form-item>

        <t-form-item label="模型 ID" name="modelKey">
          <t-input
            v-model="formData.modelKey"
            placeholder="如 gpt-4o、claude-3-5-sonnet-20241022"
            :disabled="isEdit"
          />
        </t-form-item>

        <!-- 编辑模式：API Key 脱敏展示 -->
        <t-form-item v-if="isEdit" label="API Key" name="apiKey">
          <t-input
            v-model="formData.apiKey"
            :type="showApiKey ? 'text' : 'password'"
            placeholder="......"
            readonly
          >
            <template #suffix-icon>
              <t-icon
                :name="showApiKey ? 'browse-off' : 'browse'"
                style="cursor: pointer"
                @click="showApiKey = !showApiKey"
              />
            </template>
          </t-input>
        </t-form-item>

        <!-- 新增模式：API Key 可输入 -->
        <t-form-item v-else label="API Key" name="apiKey">
          <t-input
            v-model="formData.apiKey"
            type="password"
            placeholder="请输入 API Key"
          />
        </t-form-item>

        <t-form-item label="设为默认">
          <t-switch v-model="formData.setDefault" />
        </t-form-item>
      </template>
    </t-form>
  </t-dialog>
</template>

<script setup lang="ts">
import { ref, reactive, watch } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import type { FormInstanceFunctions, FormRule } from 'tdesign-vue-next';
import type { ModelProvider, ModelInfo, ModelConfig } from '../types';
import * as modelApi from '@/shared/api/modelApi';

const props = defineProps<{
  visible: boolean;
  editConfig?: ModelConfig | null;
}>();

const emit = defineEmits<{
  'update:visible': [value: boolean];
  success: [];
}>();

const formRef = ref<FormInstanceFunctions>();
const submitting = ref(false);

/** 是否为编辑模式 */
const isEdit = ref(false);

/** 编辑模式下是否显示 API Key 明文（实际为掩码） */
const showApiKey = ref(false);

/** 编辑模式下"设为默认"的原始值，用于判断是否变更 */
const originalIsDefault = ref(false);

/** 服务商列表 */
const providers = ref<ModelProvider[]>([]);
/** 当前服务商的模型列表 */
const currentModels = ref<ModelInfo[]>([]);

const formData = reactive({
  mode: 'preset' as 'preset' | 'custom',
  providerKey: '',
  modelKey: '',
  baseUrl: '',
  apiFormat: 'openai',
  apiKey: '',
  setDefault: false,
});

const formRules: Record<string, FormRule[]> = {
  providerKey: [
    {
      required: true,
      message: '请选择服务商',
      trigger: 'change',
      validator: (value: string) => {
        if (formData.mode === 'preset' && !isEdit.value && !value) {
          return { result: false, message: '请选择服务商' };
        }
        return { result: true, message: '' };
      },
    },
  ],
  modelKey: [
    {
      required: true,
      message: '请输入或选择模型',
      trigger: 'blur',
      validator: (value: string) => {
        if (!isEdit.value && !value) {
          return { result: false, message: '请输入或选择模型' };
        }
        return { result: true, message: '' };
      },
    },
  ],
  apiKey: [
    {
      required: true,
      message: '请输入 API Key',
      trigger: 'blur',
      validator: (value: string) => {
        if (!isEdit.value && !value) {
          return { result: false, message: '请输入 API Key' };
        }
        return { result: true, message: '' };
      },
    },
  ],
  baseUrl: [
    {
      required: true,
      message: '请输入接口地址',
      trigger: 'blur',
      validator: (value: string) => {
        if (formData.mode === 'custom' && !isEdit.value && !value) {
          return { result: false, message: '请输入接口地址' };
        }
        return { result: true, message: '' };
      },
    },
  ],
};

/** 服务商变更时联动加载模型列表 */
async function handleProviderChange(providerKey: string) {
  formData.modelKey = '';
  currentModels.value = [];
  if (providerKey) {
    try {
      currentModels.value = await modelApi.fetchModelsByProvider(providerKey);
    } catch (err) {
      console.error('Failed to load models:', err);
    }
  }
}

/** 模型下拉变更（直接使用 modelKey） */
function handleModelChange(modelKey: string) {
  formData.modelKey = modelKey;
}

watch(
  () => props.visible,
  async (val) => {
    if (val) {
      if (props.editConfig) {
        isEdit.value = true;
        showApiKey.value = false;
        formData.mode = props.editConfig.providerKey === 'custom' ? 'custom' : 'preset';
        formData.providerKey = props.editConfig.providerKey;
        formData.modelKey = props.editConfig.modelKey;
        formData.baseUrl = props.editConfig.baseUrl || '';
        formData.apiFormat = props.editConfig.apiFormat || 'openai';
        formData.apiKey = props.editConfig.apiKeyMasked || '';
        formData.setDefault = props.editConfig.isDefault;
        originalIsDefault.value = props.editConfig.isDefault;

        // 加载服务商列表（用于预设模式下显示服务商名称）
        try {
          providers.value = await modelApi.fetchProviders();
          if (formData.mode === 'preset' && formData.providerKey) {
            currentModels.value = await modelApi.fetchModelsByProvider(formData.providerKey);
          }
        } catch (err) {
          console.error('Failed to load providers:', err);
        }

        // 获取解密后的 API Key（用于编辑时显示原文）
        try {
          const editConfig = await modelApi.fetchConfigForEdit(props.editConfig.id);
          formData.apiKey = editConfig.apiKeyMasked || '';
        } catch (err) {
          console.error('Failed to fetch config for edit:', err);
        }
      } else {
        isEdit.value = false;
        showApiKey.value = false;
        formData.mode = 'preset';
        formData.providerKey = '';
        formData.modelKey = '';
        formData.baseUrl = '';
        formData.apiFormat = 'openai';
        formData.apiKey = '';
        formData.setDefault = false;
        currentModels.value = [];

        // 加载服务商列表
        try {
          providers.value = await modelApi.fetchProviders();
        } catch (err) {
          console.error('Failed to load providers:', err);
        }
      }
    }
  }
);

async function handleSubmit() {
  const valid = await formRef.value?.validate();
  if (valid !== true) {
    return;
  }

  submitting.value = true;
  try {
    if (isEdit.value && props.editConfig) {
      await modelApi.updateConfig(props.editConfig.id, {
        baseUrl: formData.baseUrl || undefined,
      });
      // 如果"设为默认"状态发生变化，调用设置默认接口
      if (formData.setDefault !== originalIsDefault.value) {
        await modelApi.setDefaultModel(props.editConfig.id);
      }
      MessagePlugin.success('更新成功');
    } else if (formData.mode === 'preset') {
      await modelApi.addConfig({
        providerKey: formData.providerKey,
        modelKey: formData.modelKey,
        apiKey: formData.apiKey,
        setDefault: formData.setDefault,
      });
      MessagePlugin.success('添加成功');
    } else {
      await modelApi.addConfig({
        providerKey: 'custom',
        modelKey: formData.modelKey,
        baseUrl: formData.baseUrl,
        apiFormat: formData.apiFormat,
        apiKey: formData.apiKey,
        setDefault: formData.setDefault,
      });
      MessagePlugin.success('添加成功');
    }
    emit('success');
    handleClose();
  } catch (err) {
    console.error('Submit failed:', err);
  } finally {
    submitting.value = false;
  }
}

function handleClose() {
  emit('update:visible', false);
  formRef.value?.reset();
}

function handleVisibleUpdate(visible: boolean) {
  emit('update:visible', visible);
}
</script>

<style scoped lang="less">
.model-form {
  &__model-select {
    display: flex;
    flex-direction: column;
    width: 100%;
  }
}
</style>
