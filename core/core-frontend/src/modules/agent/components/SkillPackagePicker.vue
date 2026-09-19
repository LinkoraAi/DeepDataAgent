<template>
  <div class="picker">
    <div class="picker__actions">
      <t-button size="small" variant="outline" @click="pickZip">选择 zip 包</t-button>
      <t-button size="small" variant="outline" @click="pickDir">选择技能目录</t-button>
      <t-button v-if="modelValue.length > 0" size="small" variant="text" theme="danger" @click="clear">
        清空
      </t-button>
    </div>
    <p class="picker__summary">{{ summary }}</p>
    <!-- 隐藏的原生文件输入：zip 单文件 / 目录（webkitdirectory 以属性方式挂载，避开模板类型约束） -->
    <input ref="zipInput" type="file" accept=".zip,application/zip" class="picker__input" @change="onZipChange" />
    <input ref="dirInput" type="file" multiple class="picker__input" @change="onDirChange" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';

/**
 * 技能包选择器（multipart files 数据源）。
 * <p>两种形态二选一：单个 {@code .zip} 包（≤50MB），或整个技能目录
 * （裸文件树，依赖 {@code webkitRelativePath} 携带相对路径）。</p>
 */
const props = defineProps<{ modelValue: File[] }>();
const emit = defineEmits<{ (event: 'update:modelValue', files: File[]): void }>();

const zipInput = ref<HTMLInputElement | null>(null);
const dirInput = ref<HTMLInputElement | null>(null);

/** 选择摘要：zip 单包展示文件名与体积，目录形态展示顶级目录名与文件数。 */
const summary = computed(() => {
  const files = props.modelValue;
  if (files.length === 0) {
    return '未选择技能包';
  }
  if (files.length === 1) {
    return `zip 包：${files[0].name}（${formatSize(files[0].size)}）`;
  }
  const topDir = files[0].webkitRelativePath.split('/')[0] || '技能目录';
  return `技能目录：${topDir}（共 ${files.length} 个文件）`;
});

function formatSize(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
}

/** 挂载目录选择语义（模板内声明 webkitdirectory 会触发模板类型检查报错）。 */
function applyDirectorySemantics(): void {
  dirInput.value?.setAttribute('webkitdirectory', '');
  dirInput.value?.setAttribute('directory', '');
}

function pickZip(): void {
  const input = zipInput.value;
  if (!input) {
    return;
  }
  input.value = '';
  input.click();
}

function pickDir(): void {
  const input = dirInput.value;
  if (!input) {
    return;
  }
  applyDirectorySemantics();
  input.value = '';
  input.click();
}

function onZipChange(event: Event): void {
  const input = event.target as HTMLInputElement;
  emit('update:modelValue', input.files && input.files.length > 0 ? [input.files[0]] : []);
}

function onDirChange(event: Event): void {
  const input = event.target as HTMLInputElement;
  emit('update:modelValue', input.files ? Array.from(input.files) : []);
}

function clear(): void {
  emit('update:modelValue', []);
}

onMounted(applyDirectorySemantics);
</script>

<style scoped>
.picker {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}

.picker__actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.picker__summary {
  margin: 0;
  font-size: 13px;
  color: var(--td-text-color-secondary);
  word-break: break-all;
}

.picker__input {
  display: none;
}
</style>