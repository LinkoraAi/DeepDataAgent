<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import FormSection from '@/shared/components/FormSection.vue';
import { errorMessage, useAsync } from '@/shared/composables/useAsync';
import { ENVIRONMENT_TYPES } from '@/shared/enums/dict';
import { createEnvironment, updateEnvironment } from '../api';
import type { EnvironmentPayload, EnvironmentWire } from '../api';
import {
  MAX_SETUP_SCRIPT_BYTES,
  buildEnvironmentPayload,
  createEmptyEnvironmentForm,
  environmentFormFromWire,
  utf8ByteLength,
  validateEnvironmentForm,
} from '../mappers';
import type { EnvironmentFormErrors, EnvironmentFormState } from '../mappers';

/**
 * 运行环境创建/编辑抽屉。
 * cloud 类型提供 apt/pip/npm 三类包名标签输入与 setup 脚本；
 * self_hosted 类型隐藏包配置（提交时由装配函数剔除）。
 */
const props = defineProps<{
  /** 抽屉可见性（v-model:visible） */
  visible: boolean;
  /** 编辑目标；null 表示新建 */
  environment: EnvironmentWire | null;
}>();

const emit = defineEmits<{
  'update:visible': [visible: boolean];
  'saved': [environment: EnvironmentWire];
}>();

const form = reactive<EnvironmentFormState>(createEmptyEnvironmentForm());
const submitted = ref(false);
const task = useAsync<EnvironmentWire>();

/** 提交中状态（顶层 ref，模板自动解包） */
const submitting = task.loading;

const isEdit = computed(() => Boolean(props.environment));
const isCloud = computed(() => form.configType === 'cloud');
const errors = computed<EnvironmentFormErrors>(() => validateEnvironmentForm({ ...form }));

/** setup 脚本当前 UTF-8 字节数（上限 64KB 按字节判定） */
const scriptBytes = computed(() => utf8ByteLength(form.setupScript));

/** 脚本字节提示（超限即报错文案） */
const scriptTips = computed(() => {
  if (errors.value.setupScript) {
    return errorOf('setupScript');
  }
  return `UTF-8 ${scriptBytes.value} / ${MAX_SETUP_SCRIPT_BYTES} 字节（会话启动时以 /bin/bash -lc 执行，超时 10 分钟）`;
});

/** 环境类型单选（cloud / self_hosted） */
const typeOptions = ENVIRONMENT_TYPES.map((item) => ({ label: item.label, value: item.value }));

watch(
  () => props.visible,
  (open) => {
    if (!open) {
      return;
    }
    submitted.value = false;
    task.error.value = null;
    Object.assign(form, props.environment ? environmentFormFromWire(props.environment) : createEmptyEnvironmentForm());
  },
);

/** 取字段错误（仅在首次提交尝试后展示）。 */
function errorOf(field: keyof EnvironmentFormErrors): string | undefined {
  return submitted.value ? errors.value[field] : undefined;
}

/** 字段状态。 */
function statusOf(field: keyof EnvironmentFormErrors): 'error' | undefined {
  return errorOf(field) ? 'error' : undefined;
}

function close(): void {
  emit('update:visible', false);
}

async function onSave(): Promise<void> {
  submitted.value = true;
  const state: EnvironmentFormState = { ...form };
  const messages = Object.values(validateEnvironmentForm(state));
  if (messages.length) {
    MessagePlugin.warning(messages[0]);
    return;
  }

  let payload: EnvironmentPayload;
  try {
    payload = buildEnvironmentPayload(state);
  } catch (e) {
    MessagePlugin.error(e instanceof Error ? e.message : '表单校验未通过');
    return;
  }

  const environmentId = props.environment?.id;
  const saved = await task.run(() =>
    environmentId ? updateEnvironment(environmentId, payload) : createEnvironment(payload),
  );
  if (task.error.value) {
    MessagePlugin.error(errorMessage(task.error.value));
    return;
  }
  if (saved) {
    MessagePlugin.success(isEdit.value ? '运行环境已更新' : '运行环境已创建');
    emit('saved', saved);
    close();
  }
}
</script>

<template>
  <t-drawer
    :visible="visible"
    :header="isEdit ? '编辑运行环境' : '新建运行环境'"
    size="640px"
    placement="right"
    :close-btn="true"
    :confirm-on-close="false"
    @close="close"
  >
    <t-form layout="vertical" :data="form">
      <FormSection title="基本信息" hint="名称在当前用户内唯一；更新仅影响后续创建的会话">
        <t-form-item label="名称" name="name" :status="statusOf('name')" :tips="errorOf('name')">
          <t-input v-model="form.name" placeholder="如：数据分析环境" />
        </t-form-item>
        <t-form-item label="描述" name="description">
          <t-textarea v-model="form.description" placeholder="用途说明（可选）" />
        </t-form-item>
        <t-form-item label="环境类型" name="configType" :status="statusOf('configType')" :tips="errorOf('configType')">
          <t-radio-group v-model="form.configType" :options="typeOptions" />
        </t-form-item>
      </FormSection>

      <FormSection
        v-if="isCloud"
        title="预装依赖"
        hint="回车添加包名；pip 支持 pandas==2.1.0，npm 支持 typescript@5.0.0 或 @scope/name；仅接受 apt/pip/npm 三类"
      >
        <t-form-item label="apt（系统包）" name="apt" :status="statusOf('apt')" :tips="errorOf('apt')">
          <t-tag-input v-model="form.apt" placeholder="如 nginx、curl" />
        </t-form-item>
        <t-form-item label="pip（Python 包）" name="pip" :status="statusOf('pip')" :tips="errorOf('pip')">
          <t-tag-input v-model="form.pip" placeholder="如 pandas==2.1.0" />
        </t-form-item>
        <t-form-item label="npm（Node.js 包）" name="npm" :status="statusOf('npm')" :tips="errorOf('npm')">
          <t-tag-input v-model="form.npm" placeholder="如 typescript@5.0.0" />
        </t-form-item>
      </FormSection>

      <FormSection
        v-else
        title="预装依赖"
        hint="self_hosted 环境仅支持类型与 setup 脚本，包配置不可用（提交时自动剔除）"
      >
        <p class="dda-notice environment-drawer__notice">
          <t-icon name="info-circle" size="16px" />
          <span>自托管环境由外部执行器接管依赖准备，界面不提供 apt/pip/npm 配置。</span>
        </p>
      </FormSection>

      <FormSection title="准备脚本" hint="会话启动前在沙箱执行的 shell 脚本">
        <t-form-item label="setup 脚本" name="setupScript" :status="statusOf('setupScript')" :tips="scriptTips">
          <t-textarea
            v-model="form.setupScript"
            class="environment-drawer__script"
            placeholder="#!/bin/bash&#10;set -e&#10;pip install -r requirements.txt"
          />
        </t-form-item>
      </FormSection>
    </t-form>

    <template #footer>
      <div class="environment-drawer__footer">
        <t-button theme="default" variant="base" @click="close">取消</t-button>
        <t-button theme="primary" :loading="submitting" @click="onSave">{{ isEdit ? '保存' : '创建' }}</t-button>
      </div>
    </template>
  </t-drawer>
</template>

<style scoped>
.environment-drawer__notice {
  margin: 0;
}

.environment-drawer__script :deep(textarea) {
  min-height: 160px;
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
}

.environment-drawer__footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
