<script setup lang="ts">
import { ref } from 'vue';

/**
 * 拖拽上传区（对齐 Cloud Agents 控制台的"拖拽到此处或点击浏览"形态）。
 * 支持点击选择与拖拽投放，命中即回传文件列表；不做任何业务校验，交由调用方处置。
 */
const props = withDefaults(
  defineProps<{
    /** 主标题（如「拖拽文件到此处或点击浏览」） */
    title: string;
    /** 次级说明行（如「最大文件大小：500MB」） */
    subtitle?: string;
    /** 末级支持范围说明（如「支持文本、图片、PDF、Office 文档…」） */
    hint?: string;
    /** 原生 accept 串（如 '.zip'） */
    accept?: string;
    /** 是否多选 */
    multiple?: boolean;
    /** 禁用（如上传中） */
    disabled?: boolean;
  }>(),
  { multiple: false, disabled: false },
);

const emit = defineEmits<{ (e: 'selected', files: File[]): void }>();

const inputRef = ref<HTMLInputElement | null>(null);
/** 拖拽悬停态（高亮描边）。 */
const dragging = ref(false);

/** 打开系统文件选择器。 */
function openPicker(): void {
  if (!props.disabled) {
    inputRef.value?.click();
  }
}

/** input change：回传后清空 value，允许重复选择同一文件。 */
function onFileChange(event: Event): void {
  const input = event.target as HTMLInputElement;
  const files = input.files ? Array.from(input.files) : [];
  input.value = '';
  if (files.length) {
    emit('selected', files);
  }
}

/** 拖拽释放：取 dataTransfer 中的文件。 */
function onDrop(event: DragEvent): void {
  dragging.value = false;
  if (props.disabled) {
    return;
  }
  const files = event.dataTransfer ? Array.from(event.dataTransfer.files) : [];
  if (files.length) {
    emit('selected', files);
  }
}
</script>

<template>
  <div
    class="dda-dropzone"
    :class="{ 'dda-dropzone--active': dragging, 'dda-dropzone--disabled': disabled }"
    role="button"
    :tabindex="disabled ? -1 : 0"
    :aria-disabled="disabled"
    @click="openPicker"
    @keydown.enter.prevent="openPicker"
    @keydown.space.prevent="openPicker"
    @dragover.prevent="dragging = true"
    @dragleave.prevent="dragging = false"
    @drop.prevent="onDrop"
  >
    <input
      ref="inputRef"
      type="file"
      class="dda-dropzone__input"
      :accept="accept"
      :multiple="multiple"
      :disabled="disabled"
      @change="onFileChange"
    />
    <span class="dda-dropzone__icon">
      <t-icon name="upload" size="22px" />
    </span>
    <p class="dda-dropzone__title">{{ title }}</p>
    <p v-if="subtitle" class="dda-dropzone__subtitle">{{ subtitle }}</p>
    <p v-if="hint" class="dda-dropzone__hint">{{ hint }}</p>
  </div>
</template>

<style scoped>
.dda-dropzone {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 40px 24px;
  text-align: center;
  cursor: pointer;
  background: var(--td-bg-color-container);
  border: 1px dashed var(--td-success-color);
  border-radius: var(--td-radius-large);
  transition: background 0.15s ease, border-color 0.15s ease;
}

.dda-dropzone:hover,
.dda-dropzone--active {
  background: var(--td-success-color-1);
  border-color: var(--td-success-color);
}

.dda-dropzone--disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.dda-dropzone__input {
  display: none;
}

.dda-dropzone__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 46px;
  height: 46px;
  margin-bottom: 8px;
  color: var(--td-text-color-primary);
  background: var(--td-bg-color-component);
  border-radius: 12px;
}

.dda-dropzone__title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--td-text-color-primary);
}

.dda-dropzone__subtitle {
  margin: 0;
  font-size: 13px;
  color: var(--td-text-color-secondary);
}

.dda-dropzone__hint {
  margin: 0;
  font-size: 12px;
  color: var(--td-text-color-placeholder);
}
</style>