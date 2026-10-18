<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import FormSection from '@/shared/components/FormSection.vue';
import { errorMessage, useAsync } from '@/shared/composables/useAsync';
import { useAuthenticatedMedia } from '@/shared/composables/useAuthenticatedMedia';
import { confirmDangerous } from '@/shared/composables/useConfirm';
import {
  createChunk,
  deleteChunk,
  updateChunk,
} from '../../api/chunks';
import type { ChunkWire } from '../../api/chunks';

/**
 * 切片编辑抽屉（新增 / 编辑 / 查看三态复用）。
 * <p>新增：选定文档（选手档文档）+ 必填内容 + 可选 token（序号与来源由后端分配/打标，前端不编辑）；
 * 编辑：仅可改内容与 token；查看：只读，任何关联了图片媒体（mediaFile 非空，含多模态文档中
 * TEXT/TABLE 等形态切片携带的插图）的切片均经鉴权媒体端点加载原图展示。
 * PARSED 切片不提供单条删除入口（禁用并给引导），仅 MANUAL 可删除。</p>
 */
const props = defineProps<{
  /** 目标知识库ID */
  kbId: number;
  /** 抽屉模式：create 新增 / edit 编辑 / view 查看 */
  mode: 'create' | 'edit' | 'view';
  /** 编辑/查看目标切片；create 时为空 */
  chunk?: ChunkWire | null;
  /** 新增模式可选文档列表（选手档文档） */
  documentOptions?: Array<{ value: number; label: string }>;
}>();

const emit = defineEmits<{
  /** 保存/删除成功后回传，供上层刷新列表 */
  saved: [];
}>();

/** 抽屉显隐（v-model）。 */
const visible = defineModel<boolean>({ default: false });

const form = reactive({
  documentId: null as number | null,
  chunkContent: '',
  tokens: null as number | null,
});
/** 首次提交尝试标记（控制错误显示）。 */
const submitted = ref(false);

const task = useAsync<ChunkWire>();
const submitting = task.loading;
/** 删除动作（返回 void，单独建模避免类型混用）。 */
const deleteTask = useAsync<void>();
/** 删除中状态（顶层 ref，模板自动解包）。 */
const deleting = deleteTask.loading;

/** 鉴权媒体加载（查看态，切片关联图片时使用）。 */
const { url: mediaUrl, loading: mediaLoading, load: loadMedia } = useAuthenticatedMedia();

const isEdit = computed(() => props.mode === 'edit');
const isView = computed(() => props.mode === 'view');
const isCreate = computed(() => props.mode === 'create');
/** 切片是否关联图片媒体（不限 IMAGE 形态，多模态文档中 TEXT/TABLE 等切片也可能携带插图）。 */
const hasMedia = computed(() => Boolean(props.chunk?.mediaFile));
const isParsed = computed(() => props.chunk?.sourceType === 'PARSED');

const title = computed(() => {
  if (isCreate.value) {
    return '新增切片';
  }
  if (isView.value) {
    return '查看切片';
  }
  return '编辑切片';
});

/** 内容字段错误（提交尝试后显示）。 */
const contentError = computed(() => {
  if (!submitted.value) {
    return undefined;
  }
  return form.chunkContent.trim() === '' ? '切片内容不能为空' : undefined;
});

/** 文档字段错误（新增必填）。 */
const documentError = computed(() => {
  if (!submitted.value || !isCreate.value) {
    return undefined;
  }
  return form.documentId === null ? '请选择所属文档' : undefined;
});

/** token 字段错误（可选，须为非负整数）。 */
const tokensError = computed(() => {
  if (!submitted.value || form.tokens === null) {
    return undefined;
  }
  return !Number.isInteger(form.tokens) || form.tokens < 0 ? 'token 数量须为非负整数' : undefined;
});

/** PARSED 单条删除引导文案（与后端闸门文案一致）。 */
const PARSED_DELETE_GUIDE =
  '解析产生的切片不支持单独删除（其图谱账本贡献会失效），请通过「重新解析」整篇重建或删除整个文档来处理';

/** 打开时按模式初始化表单与媒体。 */
watch(visible, (open) => {
  if (!open) {
    return;
  }
  submitted.value = false;
  task.error.value = null;
  const chunk = props.chunk;
  if (isCreate.value) {
    form.documentId = null;
    form.chunkContent = '';
    form.tokens = null;
  } else if (chunk) {
    form.documentId = chunk.documentId;
    form.chunkContent = chunk.chunkContent;
    form.tokens = chunk.tokens ?? null;
    // 查看态下任何关联图片的切片（不限 IMAGE 形态）均加载媒体原图
    if (isView.value && chunk.mediaFile) {
      void loadMedia(chunk.mediaFile);
    }
  }
});

/** 提交：校验 → 新增/编辑 → 成功广播并关闭。 */
async function submit(): Promise<void> {
  if (isView.value) {
    visible.value = false;
    return;
  }
  submitted.value = true;
  if (form.chunkContent.trim() === '') {
    MessagePlugin.error('切片内容不能为空');
    return;
  }
  if (isCreate.value && form.documentId === null) {
    MessagePlugin.error('请选择所属文档');
    return;
  }
  if (form.tokens !== null && (!Number.isInteger(form.tokens) || form.tokens < 0)) {
    MessagePlugin.error('token 数量须为非负整数');
    return;
  }
  const tokens = form.tokens ?? undefined;
  const result = await task.run(() => {
    if (isCreate.value) {
      return createChunk({
        kbId: props.kbId,
        documentId: form.documentId as number,
        chunkContent: form.chunkContent,
        tokens,
      });
    }
    return updateChunk(props.chunk?.id as number, { chunkContent: form.chunkContent, tokens });
  });
  if (task.error.value || !result) {
    MessagePlugin.error(errorMessage(task.error.value ?? '切片保存失败'));
    return;
  }
  MessagePlugin.success(isCreate.value ? '切片已新增' : '切片已更新');
  emit('saved');
  visible.value = false;
}

/** 删除切片（仅 MANUAL 可达；二次确认后物理清退并广播刷新）。 */
async function onDelete(): Promise<void> {
  const chunk = props.chunk;
  if (!chunk || chunk.sourceType !== 'MANUAL') {
    return;
  }
  const confirmed = await confirmDangerous({
    header: '删除手工切片',
    body: `确认删除该手工切片（#${chunk.sequence ?? '-'}）？该操作不可逆。`,
    theme: 'danger',
    confirmText: '删除',
  });
  if (!confirmed) {
    return;
  }
  await deleteTask.run(() => deleteChunk(chunk.id));
  if (deleteTask.error.value) {
    MessagePlugin.error(errorMessage(deleteTask.error.value));
    return;
  }
  MessagePlugin.success('切片已删除');
  emit('saved');
  visible.value = false;
}
</script>

<template>
  <t-drawer
    v-model:visible="visible"
    :header="title"
    size="560px"
    :close-btn="true"
    :footer="false"
  >
    <div class="chunk-editor">
      <FormSection v-if="isCreate" title="所属文档" hint="手工切片序号由服务端分配，恒位于该文档切片序列末尾">
        <t-select
          v-model="form.documentId"
          :options="documentOptions"
          :status="documentError ? 'error' : 'default'"
          placeholder="选择要挂载切片的文档"
          filterable
        />
        <p v-if="documentError" class="chunk-editor__error">{{ documentError }}</p>
      </FormSection>

      <FormSection v-else-if="chunk" title="所属文档">
        <p class="chunk-editor__static">{{ chunk.sourceFileName || chunk.documentId }}（#{{ chunk.sequence }}）</p>
        <p class="chunk-editor__meta">
          来源：{{ chunk.sourceType === 'MANUAL' ? '手工' : '解析' }} · 内容形态：{{ chunk.chunkContentType || 'TEXT' }}
        </p>
      </FormSection>

      <FormSection title="切片内容" :hint="isView ? '查看态只读' : '正文（必填），编辑将同步刷新派生表示'">
        <t-textarea
          v-model="form.chunkContent"
          :readonly="isView"
          :autosize="{ minRows: 8, maxRows: 20 }"
          placeholder="录入切片正文"
          :status="contentError ? 'error' : 'default'"
        />
        <p v-if="contentError" class="chunk-editor__error">{{ contentError }}</p>
      </FormSection>

      <FormSection title="token 数量" hint="可选，留空表示不指定">
        <t-input-number
          v-model="form.tokens"
          :disabled="isView"
          :min="0"
          :decimal-places="0"
          theme="column"
          :status="tokensError ? 'error' : 'default'"
          placeholder="非负整数"
        />
        <p v-if="tokensError" class="chunk-editor__error">{{ tokensError }}</p>
      </FormSection>

      <!-- 查看态：切片关联了图片媒体（含多模态文档 TEXT/TABLE 切片携带的插图）即经鉴权端点加载原图 -->
      <FormSection v-if="isView && hasMedia" title="关联图片">
        <div v-if="mediaLoading" class="chunk-editor__media-loading">
          <t-loading size="small" text="正在加载图片……" />
        </div>
        <img v-else-if="mediaUrl" :src="mediaUrl" class="chunk-editor__media" alt="切片关联图片" />
        <p v-else class="chunk-editor__media-empty">该切片无可预览的图片媒体</p>
      </FormSection>

      <div class="chunk-editor__footer">
        <t-button variant="outline" @click="visible = false">{{ isView ? '关闭' : '取消' }}</t-button>
        <template v-if="!isView">
          <t-button
            v-if="chunk"
            theme="danger"
            variant="outline"
            :loading="deleting"
            :disabled="isParsed || submitting || deleting"
            @click="onDelete"
          >
            删除切片
          </t-button>
          <t-button theme="primary" :loading="submitting" @click="submit">{{ isEdit ? '保存修改' : '新增切片' }}</t-button>
        </template>
      </div>

      <t-tooltip v-if="chunk && isParsed && !isCreate" :content="PARSED_DELETE_GUIDE" placement="top">
        <p class="chunk-editor__parsed-hint">解析产生的切片不可单独删除，请通过重新解析或删除文档处理。</p>
      </t-tooltip>
    </div>
  </t-drawer>
</template>

<style scoped>
.chunk-editor {
  padding-bottom: 8px;
}

.chunk-editor__static {
  margin: 0;
  font-weight: 600;
}

.chunk-editor__meta {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--td-text-color-secondary, rgba(0, 0, 0, 0.6));
}

.chunk-editor__error {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--td-error-color, #d54941);
}

.chunk-editor__footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}

.chunk-editor__media {
  max-width: 100%;
  border-radius: 4px;
}

.chunk-editor__media-loading {
  padding: 20px 0;
  text-align: center;
}

.chunk-editor__media-empty {
  margin: 0;
  font-size: 12px;
  color: var(--td-text-color-placeholder, rgba(0, 0, 0, 0.4));
}

.chunk-editor__parsed-hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--td-warning-color, #e37318);
  cursor: help;
}
</style>
