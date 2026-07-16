<template>
  <div class="input-panel">
    <div class="input-panel__container" :class="{ 'input-panel__container--disabled': !configReady }">
      <!-- 中间：文本输入区 -->
      <t-textarea
        ref="textareaRef"
        v-model="question"
        class="input-panel__textarea"
        :placeholder="inputPlaceholder"
        :disabled="!configReady || isAnalyzing"
        :autosize="{ minRows: 1, maxRows: 8 }"
        :bordered="false"
      />

      <!-- 底部：操作行 -->
      <div class="input-panel__bottom-row">
        <div class="input-panel__actions">
          <button
            type="button"
            tabindex="-1"
            class="input-panel__action-btn"
            :class="{ 'input-panel__action-btn--active': webSearchEnabled }"
            @mousedown.prevent
            @click="webSearchEnabled = !webSearchEnabled"
          >
            <t-icon name="link" size="16px" />
            <span>联网搜索</span>
          </button>
        </div>
        <button
          v-if="isAnalyzing"
          type="button"
          tabindex="-1"
          class="input-panel__stop-btn"
          @mousedown.prevent
          @click="handleStop"
        >
          <t-icon name="stop-circle" size="16px" />
        </button>
        <button
          v-else
          type="button"
          tabindex="-1"
          class="input-panel__send-btn"
          :disabled="!canSend"
          @mousedown.prevent
          @click="handleSubmit"
        >
          <t-icon name="arrow-up" size="18px" />
        </button>
      </div>
    </div>

    <!-- 免责声明 -->
    <div class="input-panel__disclaimer">
      请知悉：Deep Data Agent进行数据分析时，可能将您的数据发送至模型服务用于推理。
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue';
import { Textarea as TTextarea } from 'tdesign-vue-next';
import { useDatasourceStore } from '../stores/datasource';
import { useModelStore } from '@/modules/model/stores/model';
import { useUiStore } from '@/shared/stores/ui';

interface Props {
  isAnalyzing: boolean;
  canSubmit: boolean;
}

const props = defineProps<Props>();

const emit = defineEmits<{
  (e: 'submit', question: string, enableWebSearch: boolean): void;
  (e: 'stop'): void;
}>();

const datasourceStore = useDatasourceStore();
const modelStore = useModelStore();
const uiStore = useUiStore();

const question = ref('');
const webSearchEnabled = ref(false);
const textareaRef = ref<InstanceType<typeof TTextarea> | null>(null);

/** 配置是否就绪 */
const configReady = computed(() => {
  return Boolean(datasourceStore.currentDatasourceId && modelStore.selectedConfigId);
});

/** 是否有输入内容 */
const hasInput = computed(() => question.value.trim().length > 0);

/** 发送按钮是否可点击：配置就绪 + 非分析中 + 有输入 */
const canSend = computed(() => props.canSubmit && hasInput.value);

/** 输入框占位文本 */
const inputPlaceholder = computed(() => {
  if (!configReady.value) {
    return '请先选择数据源和模型...';
  }
  return '请输入您的问题...';
});

/** 提交问题 */
function handleSubmit() {
  if (question.value.trim() && props.canSubmit) {
    emit('submit', question.value.trim(), webSearchEnabled.value);
    question.value = '';
  }
}

/** 停止分析 */
function handleStop() {
  emit('stop');
}

/**
 * 原生 keydown 事件处理（使用 capture 阶段拦截，在 TDesign 内部处理之前触发）
 * Enter: 发送消息
 * Shift+Enter: 换行
 * Escape: 停止分析
 */
function handleNativeKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    e.stopPropagation();
    handleSubmit();
  }
  if (e.key === 'Escape' && props.isAnalyzing) {
    e.preventDefault();
    e.stopPropagation();
    handleStop();
  }
}

/** 挂载后在原生 textarea 上绑定 capture 阶段的事件监听 */
onMounted(() => {
  nextTick(() => {
    const textarea = textareaRef.value?.$el?.querySelector('textarea');
    if (textarea) {
      textarea.addEventListener('keydown', handleNativeKeydown, true);
    }
  });
});

onBeforeUnmount(() => {
  const textarea = textareaRef.value?.$el?.querySelector('textarea');
  if (textarea) {
    textarea.removeEventListener('keydown', handleNativeKeydown, true);
  }
});

/**
 * 监听 uiStore.pendingSuggestion，将提示语填入输入框
 */
watch(() => uiStore.pendingSuggestion, (newSuggestion) => {
  if (newSuggestion) {
    question.value = newSuggestion;
    uiStore.clearSuggestion();
  }
});
</script>

<style scoped lang="less">
.input-panel {
  padding: 0 16px 16px;
  background: transparent;

  &__container {
    border: 1px solid rgba(15, 23, 42, 0.1);
    border-radius: 16px;
    background: #ffffff;
    padding: 16px;
    transition: border-color 0.15s, box-shadow 0.15s;
    max-width: 768px;
    margin: 0 auto;

    &:focus-within {
      border-color: #0052d9;
      box-shadow: 0 0 0 3px rgba(0, 82, 217, 0.08);
    }

    &--disabled {
      background: #f8fafc;
    }
  }

  &__upload-area {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 12px;
    border: 1px dashed rgba(15, 23, 42, 0.15);
    border-radius: 12px;
    background: rgba(0, 82, 217, 0.02);
    margin-bottom: 12px;
    cursor: pointer;
    transition: all 0.15s;

    &:hover {
      border-color: #0052d9;
      background: rgba(0, 82, 217, 0.04);
    }
  }

  &__upload-icon {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 36px;
    height: 36px;
    border-radius: 8px;
    background: rgba(0, 82, 217, 0.08);
    color: #0052d9;
    flex-shrink: 0;
  }

  &__upload-content {
    flex: 1;
  }

  &__upload-title {
    font-size: 14px;
    font-weight: 500;
    color: #0f172a;
    margin-bottom: 2px;
  }

  &__upload-desc {
    font-size: 12px;
    color: #94a3b8;
  }

  &__hint-text {
    font-size: 13px;
    color: #64748b;
    margin-bottom: 12px;
  }

  &__textarea {
    :deep(.t-textarea) {
      border: none !important;
      box-shadow: none !important;
      background: transparent !important;
    }

    :deep(.t-textarea__inner) {
      padding: 4px 0 !important;
      font-size: 14px;
      line-height: 1.6;
      resize: none;
      background: transparent !important;
      border: none !important;
      box-shadow: none !important;
    }
  }

  &__bottom-row {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-top: 12px;
  }

  &__actions {
    display: flex;
    gap: 8px;
  }

  &__action-btn {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 6px 12px;
    border: 1px solid rgba(15, 23, 42, 0.1);
    border-radius: 20px;
    background: #ffffff;
    cursor: pointer;
    font-size: 13px;
    color: #334155;
    transition: all 0.2s ease;

    &:hover {
      border-color: #0052d9;
      color: #0052d9;
      background: rgba(0, 82, 217, 0.03);
    }

    &--active {
      border-color: #0052d9;
      color: #0052d9;
      background: rgba(0, 82, 217, 0.08);

      &:hover {
        background: rgba(0, 82, 217, 0.12);
      }
    }
  }

  &__send-btn {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 36px;
    height: 36px;
    border: none;
    border-radius: 50%;
    background: #0052d9;
    color: #ffffff;
    cursor: pointer;
    transition: all 0.15s;

    &:hover:not(:disabled) {
      background: #0040b0;
      transform: scale(1.05);
    }

    &:active:not(:disabled) {
      transform: scale(0.95);
    }

    &:disabled {
      background: #e2e8f0;
      color: #cbd5e1;
      cursor: not-allowed;
    }
  }

  &__stop-btn {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 36px;
    height: 36px;
    border: none;
    border-radius: 50%;
    background: #ef4444;
    color: #ffffff;
    cursor: pointer;
    transition: all 0.15s;

    &:hover {
      background: #dc2626;
      transform: scale(1.05);
    }

    &:active {
      transform: scale(0.95);
    }
  }

  &__disclaimer {
    text-align: center;
    font-size: 12px;
    color: #94a3b8;
    margin-top: 12px;
    max-width: 768px;
    margin-left: auto;
    margin-right: auto;
  }
}
</style>
