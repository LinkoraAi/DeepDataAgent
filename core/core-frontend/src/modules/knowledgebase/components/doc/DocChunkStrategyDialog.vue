<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import { LAYER } from '@/shared/constants/layers';
import type { GeneralChunkParams } from '../../kbDraft';
import ChunkModeFields from '../chunk/ChunkModeFields.vue';
import {
  allowedChunkModesForFileType,
  buildChunkStrategyJson,
  parseChunkStrategy,
  validateChunkStrategyDraft,
} from '../../mappers.documents';

/**
 * 文档级分块策略编辑弹窗（上传对话框与文档列表共用，单一编辑器真相源）。
 * <p>打开时经 parseChunkStrategy 防御回显 initialStrategy；模式下拉候选按 fileType 经后端
 * 类型矩阵过滤（allowedChunkModesForFileType），回显模式不在候选内时回落 GENERAL
 * （GENERAL 兼容全部白名单类型）。GENERAL 模式展开三参数并经 validateChunkStrategyDraft
 * 值域拦截，确认时输出 buildChunkStrategyJson 的干净策略 JSON。</p>
 * <p>mode='upload'：仅确认修改（本次上传即生效，无恢复继承按钮）；
 * mode='edit'：确认 = 保存并重新解析（重解析二次确认由宿主执行），并额外提供
 * 「恢复为继承库级策略」（emit confirm(null)）。</p>
 */
const props = withDefaults(defineProps<{
  /** 回显的策略 JSON（文档级优先，为空回落库级；均可缺省） */
  initialStrategy?: string | null;
  /** 目标文档文件类型（FileType 枚举名，决定模式候选过滤） */
  fileType: string;
  /** 弹窗形态：upload=上传前修改；edit=已存在文档修改 */
  mode?: 'upload' | 'edit';
}>(), {
  initialStrategy: null,
  mode: 'edit',
});

const emit = defineEmits<{
  /** 确认修改（strategyJson=null 表示恢复为继承库级策略，仅 edit 形态会触发） */
  confirm: [strategyJson: string | null];
}>();

/** 弹窗显隐（v-model）。 */
const visible = defineModel<boolean>({ default: false });

/** 当前所选分块模式。 */
const chunkMode = ref('GENERAL');
/** GENERAL 三参数草稿。 */
const general = ref<GeneralChunkParams>({ chunkTokenNum: 512, overlappedPercent: 0, delimiter: '\n' });
/** 字段级错误（validateChunkStrategyDraft 产物）。 */
const errors = ref<Record<string, string>>({});

/** 该文件类型允许的分块模式（类型矩阵过滤结果）。 */
const allowedModes = computed(() => allowedChunkModesForFileType(props.fileType));

/** 头部提示文案按形态分流。 */
const hintMessage = computed(() =>
  props.mode === 'edit'
    ? '确认后将保存策略并重新解析：清空该文档全部切片（含手工切片）后按新策略重建。'
    : '默认使用知识库级分块策略；修改后本次上传即按新策略解析，无需重新解析。',
);

/** 确认按钮文案。 */
const confirmLabel = computed(() => (props.mode === 'edit' ? '保存并重新解析' : '应用策略'));

/** 弹窗打开时按 initialStrategy 回显；脏 / 矩阵外模式回落 GENERAL。 */
watch(
  () => visible.value,
  (open) => {
    if (!open) {
      return;
    }
    const view = parseChunkStrategy(props.initialStrategy);
    chunkMode.value = view.chunkMode !== '' && allowedModes.value.includes(view.chunkMode)
      ? view.chunkMode
      : 'GENERAL';
    general.value = { ...view.general };
    errors.value = {};
  },
  { immediate: true },
);

/** 确认：值域校验通过才输出干净策略 JSON 并关闭；不通过标红不发事件。 */
function onConfirm(): void {
  const validation = validateChunkStrategyDraft(chunkMode.value, general.value);
  errors.value = validation;
  if (Object.keys(validation).length > 0) {
    MessagePlugin.error('请先修正策略参数中标红的字段');
    return;
  }
  emit('confirm', buildChunkStrategyJson(chunkMode.value, general.value));
  visible.value = false;
}

/** 恢复为继承库级策略（仅 edit 形态）：以 null 语义交由宿主发起不带覆盖值的重解析。 */
function onRestoreInherit(): void {
  emit('confirm', null);
  visible.value = false;
}
</script>

<template>
  <t-dialog
    v-model:visible="visible"
    :header="mode === 'edit' ? '修改分块策略' : '上传分块策略'"
    :width="560"
    :close-on-overlay-click="false"
    placement="center"
    attach="body"
    :z-index="LAYER.dialog"
  >
    <t-alert theme="info" :message="hintMessage" class="strategy-dialog__alert" />

    <ChunkModeFields
      :chunk-mode="chunkMode"
      :general="general"
      :allowed-modes="allowedModes"
      :chunk-token-num-error="errors.chunkTokenNum"
      :overlapped-percent-error="errors.overlappedPercent"
      :delimiter-error="errors.delimiter"
      @update:chunk-mode="(v: string) => (chunkMode = v)"
      @update:general="(v: GeneralChunkParams) => (general = v)"
    />

    <p v-if="errors.chunkMode" class="strategy-dialog__error">{{ errors.chunkMode }}</p>

    <template #footer>
      <div class="strategy-dialog__footer">
        <t-button
          v-if="mode === 'edit'"
          variant="outline"
          theme="default"
          @click="onRestoreInherit"
        >
          恢复为继承库级策略
        </t-button>
        <span v-else />
        <t-space>
          <t-button variant="outline" @click="visible = false">取消</t-button>
          <t-button theme="primary" @click="onConfirm">{{ confirmLabel }}</t-button>
        </t-space>
      </div>
    </template>
  </t-dialog>
</template>

<style scoped>
.strategy-dialog__alert {
  margin-bottom: 16px;
}

.strategy-dialog__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
}

.strategy-dialog__error {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--td-error-color, #d54941);
}
</style>
