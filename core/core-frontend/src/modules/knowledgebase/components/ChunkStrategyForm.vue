<script setup lang="ts">
import { computed } from 'vue';
import { PARSE_PROVIDERS } from '@/shared/enums/dict';
import type { GeneralChunkParams, MineruParams } from '../kbDraft';
import { describeParseProviderTypes } from '../parseCapability';
import ChunkModeFields from './chunk/ChunkModeFields.vue';
import MinerUFields from './MinerUFields.vue';

/**
 * 解析与切片配置区（库级）：解析引擎（LOCAL/MINERU + MinerU 参数）与库级分块策略。
 * <p>「切片模式 + GENERAL 三参数」段委托 ChunkModeFields（与文档级策略弹窗共用同一编辑器真相源）；
 * 解析引擎与 MinerU 配置为库级专属，保留在本组件。非 GENERAL 模式时三参数隐藏且不提交
 * （装配层 buildRagEngineConfig 已据模式裁剪 modeConfig）。</p>
 */
const props = defineProps<{
  /** 解析引擎提供方 */
  parseProvider: string;
  /** MinerU 参数 */
  mineru: MineruParams;
  /** 分块模式 */
  chunkMode: string;
  /** GENERAL 分块参数 */
  general: GeneralChunkParams;
  /** MinerU 地址字段错误 */
  mineruApiBaseUrlError?: string;
  /** 分块 token 字段错误 */
  chunkTokenNumError?: string;
  /** 重叠比例字段错误 */
  overlappedPercentError?: string;
  /** 分隔符字段错误 */
  delimiterError?: string;
}>();

const emit = defineEmits<{
  'update:parseProvider': [value: string];
  'update:mineru': [value: MineruParams];
  'update:chunkMode': [value: string];
  'update:general': [value: GeneralChunkParams];
}>();

/** 是否使用 MinerU（展开 MinerU 参数区） */
const isMineru = computed(() => props.parseProvider === 'MINERU');

/** 当前引擎支持的文件类型提示（随引擎切换实时变化，未知引擎不展示） */
const supportedTypesHint = computed(() => describeParseProviderTypes(props.parseProvider));
</script>

<template>
  <div class="kb-chunk-form">
    <div class="kb-field">
      <label class="kb-field__label">解析引擎</label>
      <t-radio-group
        :value="parseProvider"
        :options="PARSE_PROVIDERS"
        @update:value="(v: string) => emit('update:parseProvider', v)"
      />
      <!-- 支持类型提示：与后端解析器声明集合对齐，切换引擎即时更新 -->
      <p v-if="supportedTypesHint !== ''" class="kb-field__hint">{{ supportedTypesHint }}</p>
    </div>

    <MinerUFields
      v-if="isMineru"
      :model-value="mineru"
      :api-base-url-error="mineruApiBaseUrlError"
      @update:model-value="(v: MineruParams) => emit('update:mineru', v)"
    />

    <t-divider />

    <ChunkModeFields
      :chunk-mode="chunkMode"
      :general="general"
      :chunk-token-num-error="chunkTokenNumError"
      :overlapped-percent-error="overlappedPercentError"
      :delimiter-error="delimiterError"
      @update:chunk-mode="(v: string) => emit('update:chunkMode', v)"
      @update:general="(v: GeneralChunkParams) => emit('update:general', v)"
    />
  </div>
</template>

<style scoped>
.kb-field {
  margin-bottom: 12px;
}

.kb-field__label {
  display: block;
  margin-bottom: 4px;
  font-size: 13px;
  color: var(--td-text-color-primary, rgba(0, 0, 0, 0.9));
}

.kb-field__hint {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--td-text-color-secondary, rgba(0, 0, 0, 0.6));
}
</style>
