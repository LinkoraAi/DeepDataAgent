<script setup lang="ts">
import { computed, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import DropZone from '@/shared/components/DropZone.vue';
import JsonEditor from '@/shared/components/JsonEditor.vue';
import { useAsync, errorMessage } from '@/shared/composables/useAsync';
import { LAYER } from '@/shared/constants/layers';
import { FILE_PURPOSES } from '@/shared/enums/dict';
import { uploadFile } from '../api';
import type { FileWire } from '../api';
import { validateFileForUpload } from '../mappers';

/**
 * 会话文件上传对话框（multipart：file + purpose 必填 + 可选 filename 覆盖 + 可选 metadata JSON 文本）。
 * 选文件入口为大号拖拽区（DropZone，内置隐藏 input[type=file]）；
 * 前端拦截：文本类扩展名白名单 + ≤50MB（validateFileForUpload）；
 * 后端 400（非文本/超限/元数据非法）保留对话框直显 message。
 * 真实限制文案：仅文本类文件、单文件 ≤50MB，供 Agent 在会话中读取；用户上传文件不可下载。
 */
const emit = defineEmits<{
  /** 上传成功（携带后端返回的文件对象） */
  success: [file: FileWire];
}>();

/** 对话框显隐（v-model:visible）。 */
const visible = defineModel<boolean>('visible', { required: true });

/** 用途下拉选项（取自 FILE_PURPOSES 字典；管理端上传默认 user_upload）。 */
const purposeOptions = Object.entries(FILE_PURPOSES).map(([value, item]) => ({ label: item.label, value }));

const selected = ref<File | null>(null);
const purpose = ref('user_upload');
const filenameOverride = ref('');
const metadataText = ref('');
/** 前端校验提示。 */
const pickError = ref('');
/** 后端错误提示（400 直显并保留对话框）。 */
const serverError = ref('');

const { loading, error, run } = useAsync<FileWire>();

const selectedSummary = computed(() =>
  selected.value ? `${selected.value.name}（${Math.max(1, Math.round(selected.value.size / 1024))} KB）` : '',
);

/** 应用所选文件（先跑前端白名单/体积校验）；未通过时清空已选并给出原因。 */
function applyFile(file: File | null): void {
  pickError.value = '';
  serverError.value = '';
  if (!file) {
    selected.value = null;
    return;
  }
  const result = validateFileForUpload(file);
  if (!result.ok) {
    selected.value = null;
    pickError.value = result.reason ?? '文件不符合要求';
    return;
  }
  selected.value = file;
}

/** 重置内部状态（关闭后复位）。 */
function resetState(): void {
  selected.value = null;
  purpose.value = 'user_upload';
  filenameOverride.value = '';
  metadataText.value = '';
  pickError.value = '';
  serverError.value = '';
}

/**
 * 校验 metadata JSON 文本：空 = 不携带；非空必须是合法 JSON 对象文本且 ≤8KB（对齐后端约束）。
 * @returns 校验失败原因；通过返回空串
 */
function validateMetadata(): string {
  const text = metadataText.value.trim();
  if (!text) {
    return '';
  }
  if (new TextEncoder().encode(text).length > 8 * 1024) {
    return 'metadata JSON 文本超过 8KB 限制';
  }
  try {
    const parsed: unknown = JSON.parse(text);
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
      return 'metadata 必须是 JSON 对象（形如 {"key":"value"}）';
    }
    return '';
  } catch {
    return 'metadata 不是合法 JSON';
  }
}

/** 提交上传：成功关框并广播；失败保留对话框直显后端 message。 */
async function submit(): Promise<void> {
  pickError.value = '';
  const file = selected.value;
  if (!file) {
    pickError.value = '请先选择要上传的文本类文件';
    return;
  }
  const metadataReason = validateMetadata();
  if (metadataReason) {
    pickError.value = metadataReason;
    return;
  }
  const filename = filenameOverride.value.trim();
  const metadata = metadataText.value.trim();
  const result = await run(() =>
    uploadFile(file, {
      purpose: purpose.value,
      filename: filename || undefined,
      metadata: metadata || undefined,
    }),
  );
  if (error.value || !result) {
    serverError.value = errorMessage(error.value ?? '文件上传失败');
    return;
  }
  MessagePlugin.success('文件上传成功');
  emit('success', result);
  resetState();
  visible.value = false;
}
</script>

<template>
  <t-dialog
    v-model:visible="visible"
    header="上传会话文件"
    :width="560"
    :close-on-overlay-click="false"
    placement="center"
    attach="body"
    :z-index="LAYER.dialog"
    @closed="resetState"
  >
    <DropZone
      title="拖拽文件到此处或点击浏览"
      subtitle="单文件 ≤50MB"
      hint="仅文本类文件（txt/md/csv/json/源码等）"
      :disabled="loading"
      @selected="(files: File[]) => applyFile(files[0] ?? null)"
    />

    <div class="dda-notice file-upload__notice">
      <t-icon name="info-circle" size="16px" />
      <span>
        会话文件仅供 Agent 在会话中读取：不支持图片、压缩包、Office 文档与音视频；用途为 user_upload
        的文件不提供下载入口。
      </span>
    </div>

    <div v-if="selected" class="file-upload__picked">
      <t-icon name="file-copy" size="16px" />
      <span class="file-upload__picked-name">{{ selectedSummary }}</span>
    </div>
    <p v-else class="file-upload__hint">尚未选择文件</p>
    <p v-if="pickError" class="file-upload__error">{{ pickError }}</p>

    <div class="file-upload__field">
      <label class="file-upload__label">用途（必填）</label>
      <t-select v-model="purpose" :options="purposeOptions" style="width: 240px" />
    </div>

    <div class="file-upload__field">
      <label class="file-upload__label">文件名覆盖（可选）</label>
      <t-input v-model="filenameOverride" placeholder="默认使用所选文件名；目录段由后端清理" maxlength="255" clearable />
    </div>

    <div class="file-upload__field">
      <label class="file-upload__label">元数据 metadata（可选，JSON 对象文本 ≤8KB）</label>
      <JsonEditor v-model="metadataText" :rows="4" validate placeholder='{"biz": "demo"}' />
    </div>

    <t-alert v-if="serverError" theme="error" :message="serverError" class="file-upload__alert" />

    <template #footer>
      <t-space>
        <t-button variant="outline" @click="visible = false">取消</t-button>
        <t-button theme="primary" :loading="loading" @click="submit">上传</t-button>
      </t-space>
    </template>
  </t-dialog>
</template>

<style scoped>
.file-upload__notice {
  margin-top: 12px;
}

.file-upload__picked {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-top: 12px;
  padding: 8px 12px;
  font-size: 13px;
  background: var(--td-bg-color-secondarycontainer);
  border: 1px solid var(--dda-card-border);
  border-radius: var(--td-radius-medium);
}

.file-upload__picked-name {
  overflow: hidden;
  font-weight: 600;
  color: var(--td-text-color-primary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-upload__field {
  margin-top: 12px;
}

.file-upload__label {
  display: block;
  margin-bottom: 4px;
  font-size: 13px;
}

.file-upload__hint {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--td-text-color-placeholder);
}

.file-upload__error {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--td-error-color, #d54941);
}

.file-upload__alert {
  margin-top: 12px;
}
</style>