<script setup lang="ts">
import { computed } from 'vue';
import { CHUNK_MODES } from '@/shared/enums/dict';
import type { GeneralChunkParams } from '../../kbDraft';

/**
 * 分块模式与 GENERAL 参数编辑区（库级表单 / 文档级策略弹窗共用的单一真相源）。
 * <p>包含「切片模式」下拉（候选可经 allowedModes 裁剪，文档级弹窗按文件类型矩阵过滤）、
 * 模式提示文案与仅 GENERAL 展开的三参数（块大小 / 重叠率 / 分隔符）。
 * 解析引擎与 MinerU 配置属库级专属，保留在 ChunkStrategyForm 外层。</p>
 */
const props = defineProps<{
  /** 当前分块模式 */
  chunkMode: string;
  /** GENERAL 分块参数 */
  general: GeneralChunkParams;
  /** 允许的分块模式值列表（省略或空数组表示全部候选；文档级弹窗按类型矩阵传入） */
  allowedModes?: string[];
  /** 分块 token 字段错误 */
  chunkTokenNumError?: string;
  /** 重叠比例字段错误 */
  overlappedPercentError?: string;
  /** 分隔符字段错误 */
  delimiterError?: string;
}>();

const emit = defineEmits<{
  'update:chunkMode': [value: string];
  'update:general': [value: GeneralChunkParams];
}>();

/** 是否为通用模式（仅 GENERAL 展开三参数） */
const isGeneral = computed(() => props.chunkMode === 'GENERAL');

/** 当前分块模式提示文案 */
const chunkModeHint = computed(
  () => CHUNK_MODES.find((mode) => mode.value === props.chunkMode)?.hint ?? '',
);

/** 模式下拉候选（allowedModes 未给时全量；给定时按 CHUNK_MODES 声明序过滤，保证候选稳定） */
const modeOptions = computed(() => {
  const allowed = props.allowedModes;
  const usable = allowed && allowed.length > 0
    ? CHUNK_MODES.filter((mode) => allowed.includes(mode.value))
    : CHUNK_MODES;
  return usable.map((mode) => ({ value: mode.value, label: mode.label }));
});

/** 分隔符候选（值为实际字符串，标签为可读描述） */
const DELIMITER_OPTIONS = [
  { value: '\n', label: '换行符' },
  { value: '\n\n', label: '空行（双换行）' },
  { value: ' ', label: '空格' },
  { value: '。', label: '句号（。）' },
  { value: '\t', label: '制表符' },
];

/** 单补丁：合并回写 GENERAL 参数对象 */
function patchGeneral(key: keyof GeneralChunkParams, value: string | number): void {
  emit('update:general', { ...props.general, [key]: value });
}
</script>

<template>
  <div class="chunk-mode-fields">
    <div class="kb-field">
      <label class="kb-field__label">切片模式</label>
      <t-select
        :value="chunkMode"
        :options="modeOptions"
        @update:value="(v: string) => emit('update:chunkMode', v)"
      />
      <p class="kb-field__hint">{{ chunkModeHint }}</p>
    </div>

    <div v-if="isGeneral" class="kb-general">
      <div class="kb-field kb-field--col">
        <label class="kb-field__label">块大小（token）</label>
        <t-input-number
          :value="general.chunkTokenNum"
          :min="1"
          :max="2048"
          :status="chunkTokenNumError ? 'error' : 'default'"
          theme="column"
          @update:value="(v: number) => patchGeneral('chunkTokenNum', v)"
        />
        <p v-if="chunkTokenNumError" class="kb-field__error">{{ chunkTokenNumError }}</p>
      </div>
      <div class="kb-field kb-field--col">
        <label class="kb-field__label">重叠率（%）</label>
        <t-input-number
          :value="general.overlappedPercent"
          :min="0"
          :max="30"
          :status="overlappedPercentError ? 'error' : 'default'"
          theme="column"
          @update:value="(v: number) => patchGeneral('overlappedPercent', v)"
        />
        <p v-if="overlappedPercentError" class="kb-field__error">{{ overlappedPercentError }}</p>
      </div>
      <div class="kb-field kb-field--col">
        <label class="kb-field__label">分隔符</label>
        <t-select
          :value="general.delimiter"
          :options="DELIMITER_OPTIONS"
          :status="delimiterError ? 'error' : 'default'"
          @update:value="(v: string) => patchGeneral('delimiter', v)"
        />
        <p v-if="delimiterError" class="kb-field__error">{{ delimiterError }}</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.kb-field {
  margin-bottom: 12px;
}

.kb-field--col {
  flex: 1;
  /* 同 MinerUFields：允许列收缩到内容最小宽度以下，长标签（如「空行（双换行）」）
     经 select 内部省略号截断，不撑破 640px 抽屉 */
  min-width: 0;
}

.kb-field__label {
  display: block;
  margin-bottom: 4px;
  font-size: 13px;
  color: var(--td-text-color-primary, rgba(0, 0, 0, 0.9));
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

.kb-general {
  display: flex;
  gap: 12px;
}
</style>
