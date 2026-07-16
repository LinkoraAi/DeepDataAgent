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
      <t-form-item label="预置模板" name="templateId">
        <t-select v-model="formData.templateId" placeholder="请选择模板" :disabled="isEdit">
          <t-option
            v-for="template in templates"
            :key="template.id"
            :value="template.id"
            :label="template.displayName"
          >
            <div>
              <div>{{ template.displayName }}</div>
              <div style="font-size: 12px; color: var(--td-text-color-secondary)">
                {{ template.provider }} - {{ template.description }}
              </div>
            </div>
          </t-option>
        </t-select>
      </t-form-item>

      <t-form-item label="配置名称" name="name">
        <t-input v-model="formData.name" placeholder="请输入配置名称" />
      </t-form-item>

      <t-form-item label="API Key" name="apiKey">
        <t-input
          v-model="formData.apiKey"
          type="password"
          :placeholder="isEdit ? '留空表示不修改' : '请输入 API Key'"
        />
      </t-form-item>

      <t-form-item label="温度参数" name="temperature">
        <t-slider v-model="formData.temperature" :min="0" :max="1" :step="0.1" />
        <div style="font-size: 12px; color: var(--td-text-color-secondary); margin-top: 4px">
          范围: 0 ~ 1，默认 0.1
        </div>
      </t-form-item>

      <t-form-item label="描述" name="description">
        <t-textarea v-model="formData.description" placeholder="请输入描述（可选）" :maxlength="200" />
      </t-form-item>

      <t-form-item v-if="!isEdit" label="设为默认">
        <t-switch v-model="formData.setDefault" />
      </t-form-item>
    </t-form>
  </t-dialog>
</template>

<script setup lang="ts">
import { ref, reactive, watch } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import type { FormInstanceFunctions, FormRule } from 'tdesign-vue-next';
import type { ModelTemplate, ModelConfig } from '../types';
import * as modelApi from '@/shared/api/modelApi';

const props = defineProps<{
  visible: boolean;
  templates: ModelTemplate[];
  editConfig?: ModelConfig | null;
}>();

const emit = defineEmits<{
  'update:visible': [value: boolean];
  success: [];
}>();

const formRef = ref<FormInstanceFunctions>();
const submitting = ref(false);

const isEdit = ref(false);

const formData = reactive({
  templateId: null as number | null,
  name: '',
  apiKey: '',
  temperature: 0.1,
  description: '',
  setDefault: false,
});

const formRules: Record<string, FormRule[]> = {
  templateId: [{ required: true, message: '请选择预置模板', trigger: 'change' }],
  name: [{ required: true, message: '请输入配置名称', trigger: 'blur' }],
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
};

watch(
  () => props.visible,
  (val) => {
    if (val) {
      if (props.editConfig) {
        isEdit.value = true;
        formData.templateId = props.editConfig.templateId;
        formData.name = props.editConfig.name;
        formData.apiKey = '';
        formData.temperature = props.editConfig.temperature;
        formData.description = props.editConfig.description || '';
      } else {
        isEdit.value = false;
        formData.templateId = null;
        formData.name = '';
        formData.apiKey = '';
        formData.temperature = 0.1;
        formData.description = '';
        formData.setDefault = false;
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
      await modelApi.updateConfig({
        id: props.editConfig.id,
        name: formData.name,
        apiKey: formData.apiKey || undefined,
        temperature: formData.temperature,
        description: formData.description,
      });
      MessagePlugin.success('更新成功');
    } else {
      await modelApi.addConfig({
        name: formData.name,
        templateId: formData.templateId!,
        apiKey: formData.apiKey,
        temperature: formData.temperature,
        description: formData.description,
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
