<script setup lang="ts">
import { computed, nextTick, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import EmptyGuide from '@/shared/components/EmptyGuide.vue';
import { errorMessage } from '@/shared/composables/useAsync';
import { retrieveAnswer } from '../../api/retrieval';
import type { QueryImage } from '../../api/retrieval';
import {
  MAX_QUERY_IMAGE_BYTES,
  MAX_QUERY_IMAGES,
  QUERY_IMAGE_CONTENT_TYPES,
  stripDataUrlPrefix,
  validateQueryImage,
} from '../../retrievalDraft';
import type { QueryImageDraft } from '../../retrievalDraft';
import { renderAnswerMarkdown } from '../../markdown';

/**
 * 知识问答面板（知识库详情「知识问答」页签，单库问答）。
 * <p>同步请求形态：发送后等待答案端点返回，期间禁用输入与发送按钮并提供「生成中」气泡占位。
 * 可附最多 3 张内联图片（通路 A 附图转译），前端按后端同源口径校验：类型限
 * image/jpeg|png|gif|webp、单张解码后 ≤4MB，提交前经 FileReader 转 base64 并剥除 data: 前缀。
 * 附件零持久化——随请求发出即清空，刷新页面不保留历史。</p>
 * <p>答案形态响应只有 answer 一项：为 null 表示答案生成降级，界面 MUST 展示明确降级文案而非空白；
 * 请求失败经 MessagePlugin 直显后端原因。</p>
 */
const props = defineProps<{
  /** 知识库ID */
  kbId: number;
}>();

/** 单条会话消息（仅内存态）。 */
interface QaMessage {
  /** 本地自增键 */
  id: number;
  /** 角色：用户提问 / 助手回答 */
  role: 'user' | 'assistant';
  /** 正文（降级态为固定提示文案） */
  text: string;
  /** 助手答案的 markdown 渲染结果（仅在答案到达时算一次，避免每次重渲染重复解析；用户/降级消息为空串） */
  html: string;
  /** 用户消息附图缩略预览（data URL，仅本地展示） */
  previews: QaPreview[];
  /** 助手消息是否仍在等待答案 */
  pending: boolean;
  /** 助手消息是否为答案生成降级 */
  degraded: boolean;
}

/** 附图缩略预览。 */
interface QaPreview {
  /** 文件名（alt 文案） */
  name: string;
  /** 预览 URL（data URL） */
  url: string;
}

/** 待发送附图（草稿 + 本地预览地址）。 */
interface QaAttachment extends QueryImageDraft {
  /** 缩略图预览地址（data URL） */
  previewUrl: string;
}

/** 答案生成降级提示（后端 answer=null 的唯一空值形态）。 */
const DEGRADED_ANSWER_TEXT = '本次未能生成答案（模型降级）';

/** 会话消息列表。 */
const messages = ref<QaMessage[]>([]);
/** 输入框文本。 */
const inputText = ref('');
/** 待发送附图。 */
const attachments = ref<QaAttachment[]>([]);
/** 发送中（等待同步答案）。 */
const sending = ref(false);
/** 附件读取中。 */
const reading = ref(false);
/** 文件选择器引用。 */
const fileInputRef = ref<HTMLInputElement | null>(null);
/** 消息列表容器（用于滚动到底）。 */
const listRef = ref<HTMLElement | null>(null);

let idSeed = 0;

/** 分配本地消息键。 */
function nextId(): number {
  idSeed += 1;
  return idSeed;
}

/** 是否可发送：空闲态且问题非空。 */
const canSend = computed(() => !sending.value && !reading.value && inputText.value.trim() !== '');

/** 附件限制说明（与纯函数层常量同源，避免文案与校验脱节）。 */
const attachmentHint = computed(() => `附图最多 ${MAX_QUERY_IMAGES} 张，单张不超过 ${Math.floor(
  MAX_QUERY_IMAGE_BYTES / 1024 / 1024,
)}MB，仅支持 ${QUERY_IMAGE_CONTENT_TYPES.join('、')}；附件不持久化，发送与刷新后均不保留`);

/** 滚动到消息列表底部。 */
async function scrollToListEnd(): Promise<void> {
  await nextTick();
  const element = listRef.value;
  if (element !== null) {
    element.scrollTop = element.scrollHeight;
  }
}

/** FileReader 读为 data URL（Promise 化，便于逐文件串行处理）。 */
function readFileAsDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(typeof reader.result === 'string' ? reader.result : '');
    reader.onerror = () => reject(reader.error ?? new Error('文件读取失败'));
    reader.readAsDataURL(file);
  });
}

/** 逐文件读取并挂载附件：任一不合格仅提示原因、不入队、不发请求。 */
async function addFiles(files: File[]): Promise<void> {
  reading.value = true;
  try {
    for (const file of files) {
      let dataUrl = '';
      try {
        dataUrl = await readFileAsDataUrl(file);
      } catch (e) {
        MessagePlugin.error(`附图「${file.name}」读取失败：${errorMessage(e)}`);
        continue;
      }
      const candidate: QaAttachment = {
        name: file.name,
        contentType: file.type,
        data: stripDataUrlPrefix(dataUrl),
        previewUrl: dataUrl,
      };
      const reason = validateQueryImage(candidate, attachments.value.length);
      if (reason !== null) {
        MessagePlugin.warning(reason);
        continue;
      }
      attachments.value.push(candidate);
    }
  } finally {
    reading.value = false;
  }
}

/** 打开系统文件选择器。 */
function openPicker(): void {
  fileInputRef.value?.click();
}

/** 文件选择回调（清空 input 值，便于重复选择同一文件）。 */
async function onFileChange(event: Event): Promise<void> {
  const target = event.target as HTMLInputElement;
  const files = Array.from(target.files ?? []);
  target.value = '';
  if (files.length > 0) {
    await addFiles(files);
  }
}

/** 移除待发送附图。 */
function removeAttachment(index: number): void {
  attachments.value.splice(index, 1);
}

/** 发送问题：调用答案端点并同步等待，降级与失败均有明确反馈。 */
async function send(): Promise<void> {
  if (sending.value) {
    return;
  }
  const question = inputText.value.trim();
  if (question === '') {
    MessagePlugin.warning('请输入问题');
    return;
  }
  const images: QueryImage[] = attachments.value.map((item) => ({
    contentType: item.contentType,
    data: item.data,
  }));
  const previews: QaPreview[] = attachments.value.map((item) => ({ name: item.name, url: item.previewUrl }));
  messages.value.push({
    id: nextId(), role: 'user', text: question, html: '', previews, pending: false, degraded: false,
  });
  attachments.value = [];
  inputText.value = '';
  messages.value.push({
    id: nextId(), role: 'assistant', text: '', html: '', previews: [], pending: true, degraded: false,
  });
  const replyIndex = messages.value.length - 1;
  sending.value = true;
  await scrollToListEnd();
  try {
    const response = await retrieveAnswer({
      kbId: props.kbId,
      query: question,
      images: images.length > 0 ? images : undefined,
    });
    const reply = messages.value[replyIndex];
    reply.degraded = response.answer === null;
    reply.text = response.answer ?? DEGRADED_ANSWER_TEXT;
    // 答案到达即一次性渲染 markdown（降级文案为我方固定提示，按纯文本展示）
    reply.html = response.answer === null ? '' : renderAnswerMarkdown(response.answer);
    reply.pending = false;
  } catch (e) {
    // 失败不保留占位气泡，原因经 MessagePlugin 直显
    messages.value.splice(replyIndex, 1);
    MessagePlugin.error(`问答失败：${errorMessage(e)}`);
  } finally {
    sending.value = false;
    await scrollToListEnd();
  }
}
</script>

<template>
  <div class="qa-pane">
    <div ref="listRef" class="qa-pane__list">
      <EmptyGuide v-if="messages.length === 0" description="向当前知识库提问：系统检索该库切片并同步生成答案，可附图片一起提问" />
      <div
        v-for="message in messages"
        :key="message.id"
        class="qa-row"
        :class="message.role === 'user' ? 'qa-row--user' : 'qa-row--assistant'"
      >
        <div class="qa-bubble" :class="{ 'qa-bubble--degraded': message.degraded }">
          <p v-if="message.pending" class="qa-bubble__pending">正在检索并生成答案……</p>
          <!-- 助手答案按 markdown 渲染；用户提问与降级文案保持纯文本插值（不参与 HTML 渲染） -->
          <div
            v-else-if="message.role === 'assistant' && !message.degraded"
            class="qa-bubble__md"
            v-html="message.html"
          />
          <p v-else class="qa-bubble__text">{{ message.text }}</p>
          <div v-if="message.previews.length > 0" class="qa-bubble__images">
            <img
              v-for="preview in message.previews"
              :key="preview.url"
              :src="preview.url"
              :alt="preview.name"
              class="qa-bubble__img"
            />
          </div>
          <span v-if="message.role === 'assistant'" class="qa-bubble__tag">知识库</span>
          <span v-else class="qa-bubble__tag">我</span>
        </div>
      </div>
    </div>

    <div class="qa-pane__composer">
      <div v-if="attachments.length > 0" class="qa-pane__attachments">
        <div v-for="(attachment, index) in attachments" :key="attachment.previewUrl" class="qa-attachment">
          <img :src="attachment.previewUrl" :alt="attachment.name" class="qa-attachment__img" />
          <span class="qa-attachment__name">{{ attachment.name }}</span>
          <t-button variant="text" size="small" theme="danger" :disabled="sending" @click="removeAttachment(index)">
            移除
          </t-button>
        </div>
      </div>

      <t-textarea
        v-model="inputText"
        class="qa-pane__input"
        :disabled="sending"
        :autosize="{ minRows: 3, maxRows: 6 }"
        placeholder="输入问题，回车换行，点击发送（同步等待答案）"
      />

      <div class="qa-pane__actions">
        <span class="qa-pane__hint">{{ attachmentHint }}</span>
        <t-button
          variant="text"
          :loading="reading"
          :disabled="sending || attachments.length >= MAX_QUERY_IMAGES"
          @click="openPicker"
        >
          添加图片（{{ attachments.length }}/{{ MAX_QUERY_IMAGES }}）
        </t-button>
        <t-button theme="primary" :loading="sending" :disabled="!canSend" @click="send">发送</t-button>
      </div>

      <input
        ref="fileInputRef"
        type="file"
        class="qa-pane__file"
        :accept="QUERY_IMAGE_CONTENT_TYPES.join(',')"
        multiple
        @change="onFileChange"
      />
    </div>
  </div>
</template>

<style scoped>
.qa-pane {
  display: flex;
  flex-direction: column;
  height: 68vh;
  min-height: 420px;
  background: var(--td-bg-color-container, #fff);
  border: 1px solid var(--td-component-border, #e7e7e7);
  border-radius: 6px;
}

.qa-pane__list {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
}

.qa-row {
  display: flex;
  margin-bottom: 12px;
}

.qa-row--user {
  justify-content: flex-end;
}

.qa-row--assistant {
  justify-content: flex-start;
}

.qa-bubble {
  position: relative;
  max-width: 76%;
  padding: 10px 12px 16px;
  font-size: 13px;
  line-height: 1.7;
  border-radius: 8px;
  background: var(--td-bg-color-container-hover, #f3f3f3);
  color: var(--td-text-color-primary, rgba(0, 0, 0, 0.9));
}

.qa-row--user .qa-bubble {
  background: var(--td-brand-color-1, #e8f1ff);
}

.qa-bubble--degraded {
  background: var(--td-warning-color-1, #fffbed);
  color: var(--td-text-color-secondary, rgba(0, 0, 0, 0.6));
}

.qa-bubble__text {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}

/*
 * markdown 正文容器。
 * white-space 必须为 normal：渲染产物自带换行符，沿用 pre-wrap 会把源文本换行当空白保留、撑坏块间距。
 * v-html 注入的内容不受 scoped 约束，故元素样式一律经 :deep 下发。
 */
.qa-bubble__md {
  white-space: normal;
  word-break: break-word;
}

.qa-bubble__md :deep(> :first-child) {
  margin-top: 0;
}

.qa-bubble__md :deep(> :last-child) {
  margin-bottom: 0;
}

.qa-bubble__md :deep(p) {
  margin: 0 0 8px;
}

.qa-bubble__md :deep(h1),
.qa-bubble__md :deep(h2),
.qa-bubble__md :deep(h3),
.qa-bubble__md :deep(h4),
.qa-bubble__md :deep(h5),
.qa-bubble__md :deep(h6) {
  margin: 12px 0 6px;
  font-size: 13px;
  font-weight: 600;
  line-height: 1.5;
}

.qa-bubble__md :deep(h1) {
  font-size: 15px;
}

.qa-bubble__md :deep(h2) {
  font-size: 14px;
}

.qa-bubble__md :deep(ul),
.qa-bubble__md :deep(ol) {
  margin: 0 0 8px;
  padding-left: 20px;
}

.qa-bubble__md :deep(li) {
  margin: 2px 0;
}

.qa-bubble__md :deep(blockquote) {
  margin: 0 0 8px;
  padding-left: 10px;
  color: var(--td-text-color-secondary, rgba(0, 0, 0, 0.6));
  border-left: 3px solid var(--td-component-stroke, #e7e7e7);
}

.qa-bubble__md :deep(code) {
  padding: 1px 4px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  background: var(--td-bg-color-container, #fff);
  border-radius: 3px;
}

.qa-bubble__md :deep(pre) {
  margin: 0 0 8px;
  padding: 8px 10px;
  overflow-x: auto;
  background: var(--td-bg-color-container, #fff);
  border-radius: 4px;
}

.qa-bubble__md :deep(pre code) {
  padding: 0;
  background: none;
}

.qa-bubble__md :deep(table) {
  margin: 0 0 8px;
  font-size: 12px;
  border-collapse: collapse;
}

.qa-bubble__md :deep(th),
.qa-bubble__md :deep(td) {
  padding: 4px 8px;
  text-align: left;
  border: 1px solid var(--td-component-border, #e7e7e7);
}

.qa-bubble__md :deep(th) {
  font-weight: 600;
  background: var(--td-bg-color-container, #fff);
}

.qa-bubble__md :deep(a) {
  color: var(--td-brand-color);
  text-decoration: none;
}

.qa-bubble__md :deep(a:hover) {
  text-decoration: underline;
}

.qa-bubble__md :deep(img) {
  max-width: 100%;
  border-radius: 4px;
}

.qa-bubble__md :deep(hr) {
  margin: 8px 0;
  border: none;
  border-top: 1px solid var(--td-component-stroke, #e7e7e7);
}

.qa-bubble__pending {
  margin: 0;
  color: var(--td-text-color-placeholder, rgba(0, 0, 0, 0.4));
}

.qa-bubble__images {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}

.qa-bubble__img {
  max-width: 160px;
  max-height: 120px;
  object-fit: contain;
  border-radius: 4px;
  border: 1px solid var(--td-component-border, #e7e7e7);
}

.qa-bubble__tag {
  position: absolute;
  right: 10px;
  bottom: 2px;
  font-size: 11px;
  color: var(--td-text-color-placeholder, rgba(0, 0, 0, 0.4));
}

.qa-pane__composer {
  padding: 12px 16px;
  border-top: 1px solid var(--td-component-border, #e7e7e7);
}

.qa-pane__attachments {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 8px;
}

.qa-attachment {
  display: flex;
  gap: 6px;
  align-items: center;
  padding: 4px 6px;
  border: 1px solid var(--td-component-border, #e7e7e7);
  border-radius: 4px;
}

.qa-attachment__img {
  width: 40px;
  height: 40px;
  object-fit: cover;
  border-radius: 4px;
}

.qa-attachment__name {
  max-width: 160px;
  overflow: hidden;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--td-text-color-secondary, rgba(0, 0, 0, 0.6));
}

.qa-pane__input {
  width: 100%;
}

.qa-pane__actions {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: flex-end;
  margin-top: 8px;
}

.qa-pane__hint {
  flex: 1;
  font-size: 12px;
  line-height: 1.6;
  color: var(--td-text-color-secondary, rgba(0, 0, 0, 0.6));
}

.qa-pane__file {
  display: none;
}
</style>
