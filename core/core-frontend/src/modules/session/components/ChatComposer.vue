<script setup lang="ts">
import { computed, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import type { ImageAttachment } from '../stores/workspace';

/**
 * 会话输入区：多行文本 + 图片附件（≤100 张、单张 base64 ≤10MiB 前端校验）+ 发送。
 * <p>会话 running 时禁用发送（避免必然 409 忙冲突），归档 / 终止态整体禁用。
 * Enter 发送、Shift+Enter 换行。图片经 FileReader 读取为 data URL 后剥离前缀转 base64。</p>
 */

const props = defineProps<{
  /** 是否禁用（running 或只读态） */
  disabled?: boolean;
  /** 发送中 */
  sending?: boolean;
}>();

const emit = defineEmits<{
  /** 提交一条消息（文本 + 已转 base64 的图片） */
  send: [text: string, images: ImageAttachment[]];
}>();

/** 每条消息最大图片数（后端 InboundEventValidator.MAX_IMAGE_BLOCKS）。 */
const MAX_IMAGES = 100;

/** 单张 base64 正文字符上限（10 MiB，后端 MAX_BASE64_CHARS 同口径）。 */
const MAX_BASE64_CHARS = 10 * 1024 * 1024;

/** 允许的 MIME 前缀（图片）。 */
const IMAGE_MIME_PREFIX = 'image/';

/** 文本正文。 */
const text = ref('');

/** 已选图片（本地预览 URL + base64 结果 + 元信息，提交时映射为 ImageAttachment）。 */
interface PickedImage {
  /** 本地预览 URL */
  preview: string;
  /** MIME 类型 */
  mediaType: string;
  /** base64 正文（无前缀） */
  data: string;
  /** 原始文件名 */
  name: string;
}

/** 已选图片列表。 */
const images = ref<PickedImage[]>([]);

/** 可发送：非禁用、非发送中、且有文本或图片。 */
const canSend = computed(() => !props.disabled && !props.sending && (text.value.trim().length > 0 || images.value.length > 0));

/** 把单个 File 转成 PickedImage（剥 data URL 前缀取 base64），失败/超限返回 null 并提示。 */
function readImage(file: File): Promise<PickedImage | null> {
  return new Promise((resolve) => {
    if (!file.type.startsWith(IMAGE_MIME_PREFIX)) {
      MessagePlugin.warning(`已忽略非图片文件：${file.name}`);
      resolve(null);
      return;
    }
    const reader = new FileReader();
    reader.onload = () => {
      const result = typeof reader.result === 'string' ? reader.result : '';
      // data:[<mediatype>][;base64],<data> —— 剥离前缀取 base64 正文
      const commaIndex = result.indexOf(',');
      const data = commaIndex >= 0 ? result.slice(commaIndex + 1) : result;
      if (data.length > MAX_BASE64_CHARS) {
        MessagePlugin.warning(`图片 ${file.name} 超过 10MiB，已忽略`);
        resolve(null);
        return;
      }
      resolve({ preview: result, mediaType: file.type, data, name: file.name });
    };
    reader.onerror = () => {
      MessagePlugin.error(`图片 ${file.name} 读取失败`);
      resolve(null);
    };
    reader.readAsDataURL(file);
  });
}

/** 追加选择图片（超出 100 张拦截）。 */
async function onPickFiles(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement;
  const files = Array.from(input.files ?? []);
  input.value = '';
  for (const file of files) {
    if (images.value.length >= MAX_IMAGES) {
      MessagePlugin.warning(`单条消息最多 ${MAX_IMAGES} 张图片`);
      break;
    }
    const picked = await readImage(file);
    if (picked) {
      images.value.push(picked);
    }
  }
}

/** 移除一张已选图片（释放预览）。 */
function removeImage(index: number): void {
  images.value.splice(index, 1);
}

/** Enter 发送 / Shift+Enter 换行。 */
function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    submit();
  }
}

/** 提交并清空。 */
function submit(): void {
  if (!canSend.value) {
    return;
  }
  const attachments: ImageAttachment[] = images.value.map((item) => ({ mediaType: item.mediaType, data: item.data }));
  emit('send', text.value.trim(), attachments);
  text.value = '';
  images.value = [];
}

/** 中断按钮触发时清空文本保留（不自动清）。 */
defineExpose({ submit });
</script>

<template>
  <div class="composer">
    <div v-if="images.length" class="composer__thumbs">
      <div v-for="(img, index) in images" :key="index" class="composer__thumb">
        <img :src="img.preview" :alt="img.name" />
        <t-button
          class="composer__thumb-remove"
          shape="circle"
          size="small"
          variant="text"
          theme="danger"
          aria-label="移除图片"
          @click="removeImage(index)"
        >
          <t-icon name="close" />
        </t-button>
        <span class="composer__thumb-name">{{ img.name }}</span>
      </div>
    </div>

    <div class="composer__box">
      <t-textarea
        v-model="text"
        class="composer__input"
        :autosize="{ minRows: 2, maxRows: 8 }"
        :placeholder="disabled ? '会话运行中或已结束，暂不可发送（可先中断 / 取消）' : '输入消息，Enter 发送，Shift+Enter 换行'"
        :disabled="disabled"
        @keydown="onKeydown"
      />

      <div class="composer__actions">
        <label class="composer__attach">
          <input type="file" accept="image/*" multiple hidden @change="onPickFiles" />
          <t-button
            shape="square"
            variant="text"
            theme="default"
            aria-label="添加图片"
            title="添加图片"
            :disabled="disabled || images.length >= MAX_IMAGES"
          >
            <t-icon name="add" />
          </t-button>
        </label>
        <t-button
          class="composer__send"
          theme="primary"
          shape="circle"
          aria-label="发送"
          :disabled="!canSend"
          :loading="sending"
          @click="submit"
        >
          <t-icon name="send" />
        </t-button>
      </div>
    </div>

    <p class="composer__hint dda-faint">
      <span v-if="images.length">已附 {{ images.length }}/{{ MAX_IMAGES }} 张</span>
    </p>
  </div>
</template>

<style scoped>
.composer {
  flex: none;
  padding: 10px 12px 8px;
  background: var(--td-bg-color-container);
  border-top: 1px solid var(--td-component-border);
}

.composer__thumbs {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
}

.composer__thumb {
  position: relative;
  width: 88px;
  text-align: center;
}

.composer__thumb img {
  width: 88px;
  height: 66px;
  object-fit: cover;
  border: 1px solid var(--td-component-border);
  border-radius: 6px;
}

.composer__thumb-remove {
  position: absolute;
  top: -6px;
  right: -2px;
}

.composer__thumb-name {
  display: block;
  overflow: hidden;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 大圆角、无重边框的输入容器（聚焦时以品牌色描边提亮） */
.composer__box {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 10px 6px;
  background: var(--td-bg-color-secondarycontainer);
  border: 1px solid var(--td-bg-color-secondarycontainer);
  border-radius: var(--td-radius-large);
  transition: border-color 0.15s ease, background 0.15s ease;
}

.composer__box:focus-within {
  background: var(--td-bg-color-container);
  border-color: var(--td-brand-color-3);
}

.composer__input :deep(.t-textarea__inner) {
  padding: 2px;
  background: transparent;
  border: none;
  box-shadow: none;
}

.composer__actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.composer__attach {
  display: inline-flex;
  cursor: pointer;
}

.composer__hint {
  display: flex;
  justify-content: flex-start;
  min-height: 16px;
  margin: 6px 2px 0;
  font-size: 12px;
}
</style>
