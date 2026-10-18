<script setup lang="ts">
import type { MineruParams } from '../kbDraft';

/**
 * MinerU 远程版面解析引擎参数区（仅当解析引擎选择 MINERU 时渲染）。
 * <p>apiBaseUrl 必填（缺失由 KbFormDrawer 提交前 validateDraft 拦截并展示错误），
 * 其余参数留空表示沿用后端默认（backend/parseMethod/quality 等）。</p>
 */
const props = defineProps<{
  /** MinerU 参数对象 */
  modelValue: MineruParams;
  /** apiBaseUrl 字段错误信息（提交前校验回填） */
  apiBaseUrlError?: string;
}>();

const emit = defineEmits<{ 'update:modelValue': [value: MineruParams] }>();

/** 单字段补丁：以合并方式回写整个参数对象，保持上层 v-model 绑定 */
function patch(key: keyof MineruParams, value: string): void {
  emit('update:modelValue', { ...props.modelValue, [key]: value });
}

/** MinerU 解析后端候选（与后端 MineruClient 默认值对齐；留空取默认） */
const BACKEND_OPTIONS = [
  { value: '', label: '默认（hybrid-engine）' },
  { value: 'hybrid-engine', label: 'hybrid-engine' },
  { value: 'pipeline', label: 'pipeline' },
  { value: 'vlm-engine', label: 'vlm-engine' },
];

/** 解析方法候选 */
const PARSE_METHOD_OPTIONS = [
  { value: '', label: '默认（auto）' },
  { value: 'auto', label: 'auto' },
  { value: 'txt', label: 'txt（文本层优先）' },
  { value: 'ocr', label: 'ocr（强制识别）' },
];

/** 解析质量档位候选 */
const QUALITY_OPTIONS = [
  { value: '', label: '默认' },
  { value: 'high', label: 'high' },
  { value: 'medium', label: 'medium' },
  { value: 'low', label: 'low' },
];
</script>

<template>
  <div class="kb-mineru">
    <div class="kb-field">
      <label class="kb-field__label">MinerU 服务地址<span class="kb-field__req">*</span></label>
      <t-input
        :value="modelValue.apiBaseUrl"
        placeholder="如 http://mineru.internal:8000"
        :status="apiBaseUrlError ? 'error' : 'default'"
        @update:value="(v: string) => patch('apiBaseUrl', v)"
      />
      <p v-if="apiBaseUrlError" class="kb-field__error">{{ apiBaseUrlError }}</p>
    </div>
    <div class="kb-field kb-field--row">
      <div class="kb-field kb-field--col">
        <label class="kb-field__label">解析后端</label>
        <t-select
          :value="modelValue.backend"
          :options="BACKEND_OPTIONS"
          @update:value="(v: string) => patch('backend', v)"
        />
      </div>
      <div class="kb-field kb-field--col">
        <label class="kb-field__label">解析方法</label>
        <t-select
          :value="modelValue.parseMethod"
          :options="PARSE_METHOD_OPTIONS"
          @update:value="(v: string) => patch('parseMethod', v)"
        />
      </div>
      <div class="kb-field kb-field--col">
        <label class="kb-field__label">解析质量</label>
        <t-select
          :value="modelValue.quality"
          :options="QUALITY_OPTIONS"
          @update:value="(v: string) => patch('quality', v)"
        />
      </div>
    </div>
    <div class="kb-field">
      <label class="kb-field__label">OCR 语言列表</label>
      <t-input
        :value="modelValue.language"
        placeholder="逗号分隔，如 ch_en（留空取默认）"
        @update:value="(v: string) => patch('language', v)"
      />
      <p class="kb-field__hint">OCR 多语言列表，与知识库语言相互独立、互不影响。</p>
    </div>
  </div>
</template>

<style scoped>
.kb-field {
  margin-bottom: 12px;
}

.kb-field--row {
  display: flex;
  gap: 12px;
}

.kb-field--col {
  flex: 1;
  /* flex 项默认 min-width:auto 会被长选项文本（如「默认（hybrid-engine）」）撑破抽屉，
     置 0 允许收缩，select 内部按可用宽度省略号截断 */
  min-width: 0;
  margin-bottom: 0;
}

.kb-field__label {
  display: block;
  margin-bottom: 4px;
  font-size: 13px;
  color: var(--td-text-color-primary, rgba(0, 0, 0, 0.9));
}

.kb-field__req {
  margin-left: 2px;
  color: var(--td-error-color, #d54941);
}

.kb-field__hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--td-text-color-secondary, rgba(0, 0, 0, 0.6));
}

.kb-field__error {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--td-error-color, #d54941);
}
</style>
