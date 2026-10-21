<script setup lang="ts">
import { computed, defineAsyncComponent, onScopeDispose, ref, watch } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import '@vue-office/docx/lib/index.css';
import '@vue-office/excel/lib/index.css';
import { fetchBlob, downloadFile } from '@/shared/api/blob';
import { errorMessage } from '@/shared/composables/useAsync';
import { LAYER } from '@/shared/constants/layers';
import { documentContentPath, documentDownloadPath } from '../../api/documents';
import type { DocumentWire } from '../../api/documents';

/**
 * 文档在线阅读对话框：按 fileType 分流做「真正的在线预览」。
 * <p>PDF 经鉴权 fetchBlob 取二进制转一次性 object URL 后用原生 &lt;iframe&gt; 渲染（并可「在新窗口打开」）；
 * 图片用 &lt;img&gt;；DOCX / XLSX / PPTX 交给前端渲染组件（@vue-office）直接解析原始二进制呈现版式；
 * TXT / MD / CSV / JSON / LOG 读 blob 文本以等宽 &lt;pre&gt; 展示原文；
 * 老二进制 XLS（BIFF 格式，@vue-office/excel 内置 exceljs 仅支持 xlsx）与超大文件不进渲染分支，
 * 统一提示下载。关闭即 revoke object URL。</p>
 * <p>布局约定：本弹窗经 t-dialog attach="body" 的 &lt;Teleport&gt; 渲染，scoped style 的
 * CSS v-bind() 变量无法穿越传送边界（Vue useCssVars 限制，曾致预览区塌陷为 iframe 默认 150px），
 * 因此预览高度一律使用样式表定义的普通 CSS 变量或字面量——<b>Teleport 弹窗内禁用 v-bind()</b>。</p>
 */
const props = defineProps<{
  /** 目标文档；为空表示未选中（对话框关闭态） */
  document: DocumentWire | null;
}>();

/**
 * Office 前端渲染组件按需加载：解析库（docx-preview / exceljs 等）体积较大，
 * 仅在真正打开对应文档时才拉取，避免拖累知识库详情页首屏 chunk。
 */
const VueOfficeDocx = defineAsyncComponent(() => import('@vue-office/docx').then((mod) => mod.default));
const VueOfficeExcel = defineAsyncComponent(() => import('@vue-office/excel').then((mod) => mod.default));
const VueOfficePptx = defineAsyncComponent(() => import('@vue-office/pptx').then((mod) => mod.default));

/** 对话框显隐（v-model）。 */
const visible = defineModel<boolean>({ default: false });

/**
 * 预览形态：
 * loading（加载中）、iframe（PDF 原生渲染）、img（图片）、docx/excel/pptx（前端渲染组件）、
 * text（文本原文）、error（加载/渲染失败）、unsupported（不支持或超过内联预览上限）。
 */
type PreviewKind =
  | 'loading'
  | 'iframe'
  | 'img'
  | 'docx'
  | 'excel'
  | 'pptx'
  | 'text'
  | 'error'
  | 'unsupported';

/** 图片类型（img 渲染）。 */
const IMAGE_TYPES = ['PNG', 'JPG', 'JPEG'];
/** 纯文本类型（原文 <pre> 展示，内容即原文，非提取）。 */
const TEXT_TYPES = ['TXT', 'MD', 'CSV', 'JSON', 'LOG'];
/** 走前端渲染组件的办公文档类型（DOCX / XLSX / PPTX；老二进制 XLS 不支持，走下载引导）。 */
const OFFICE_RENDER_KINDS: PreviewKind[] = ['docx', 'excel', 'pptx'];
/** 内联预览大小上限（50 MB）：超过则仅提示下载，避免大文件解析造成的卡顿或崩溃。 */
const MAX_INLINE_PREVIEW_BYTES = 50 * 1024 * 1024;

/** 当前预览形态。 */
const kind = ref<PreviewKind>('loading');
/** 二进制预览的 object URL（iframe / img / Office 组件共用）。 */
const objectUrl = ref('');
/** 文本类原文内容。 */
const textContent = ref('');
/** 加载/渲染错误文案。 */
const errorText = ref('');
/** 不支持预览时的标题。 */
const unsupportedTitle = ref('该类型不支持在线预览，请下载后查看');
/** 不支持预览时的补充说明（如超限大小）。 */
const unsupportedDesc = ref('');
/** Office 渲染组件是否已完成渲染（用于渲染中的 loading 遮罩）。 */
const officeRendered = ref(false);

/** 文件名（标题展示）。 */
const fileName = computed(() => props.document?.fileName ?? '');

/** 当前文档类型（大写，去空）。 */
const fileTypeUpper = computed(() => (props.document?.fileType ?? '').toUpperCase());

/** 是否走前端渲染组件的办公文档（决定弹窗更宽的版式）。 */
const isOfficeDoc = computed(() => {
  const upper = fileTypeUpper.value;
  return upper === 'DOCX' || upper === 'XLSX' || upper === 'PPTX';
});

/** 弹窗宽度：办公文档放宽到 1100px，其余保持 900px。 */
const dialogWidth = computed(() => (isOfficeDoc.value ? '1100px' : '900px'));

/** 是否为当前走 Office 渲染组件的形态。 */
const isOfficeKind = computed(() => OFFICE_RENDER_KINDS.includes(kind.value));

/** 是否为可在线预览的二进制类型（需 fetchBlob 取原文）；老二进制 XLS 不支持前端渲染，返回 null 走下载引导。 */
function binaryPreviewKind(fileType: string): PreviewKind | null {
  const upper = (fileType || '').toUpperCase();
  if (upper === 'PDF') {
    return 'iframe';
  }
  if (IMAGE_TYPES.includes(upper)) {
    return 'img';
  }
  if (upper === 'DOCX') {
    return 'docx';
  }
  if (upper === 'XLSX') {
    return 'excel';
  }
  if (upper === 'PPTX') {
    return 'pptx';
  }
  return null;
}

/** 人性化文件大小（用于超限提示）。 */
function humanSize(bytes: number | null): string {
  if (bytes === null || bytes === undefined) {
    return '-';
  }
  if (bytes >= 1024 * 1024) {
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  }
  if (bytes >= 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  return `${bytes} B`;
}

/** 释放上一次 object URL。 */
function revokeObjectUrl(): void {
  if (objectUrl.value) {
    URL.revokeObjectURL(objectUrl.value);
    objectUrl.value = '';
  }
}

/** 重置所有预览状态（每次重新加载前调用）。 */
function resetState(): void {
  errorText.value = '';
  textContent.value = '';
  unsupportedTitle.value = '该类型不支持在线预览，请下载后查看';
  unsupportedDesc.value = '';
  officeRendered.value = false;
  revokeObjectUrl();
}

/** 加载预览内容。 */
async function load(): Promise<void> {
  const doc = props.document;
  resetState();
  if (!doc) {
    return;
  }
  const binary = binaryPreviewKind(doc.fileType);
  if (binary) {
    // 大文件保护：超过内联预览上限时不渲染，仅提示下载（不静默失败）
    if ((doc.fileSize ?? 0) > MAX_INLINE_PREVIEW_BYTES) {
      kind.value = 'unsupported';
      unsupportedTitle.value = '文件较大，暂不支持在线预览';
      unsupportedDesc.value = `文件大小约 ${humanSize(doc.fileSize)}，超过内联预览上限 50 MB，请下载后查看。`;
      return;
    }
    kind.value = 'loading';
    try {
      const blob = await fetchBlob(documentContentPath(doc.id));
      objectUrl.value = URL.createObjectURL(blob);
      kind.value = binary;
    } catch (e) {
      kind.value = 'error';
      errorText.value = errorMessage(e);
    }
    return;
  }
  // 老二进制 XLS：@vue-office/excel 内置 exceljs 仅支持 xlsx，BIFF 格式必解析失败，直接引导下载
  if (fileTypeUpper.value === 'XLS') {
    kind.value = 'unsupported';
    unsupportedTitle.value = '该格式不支持在线预览';
    unsupportedDesc.value = '老版 Excel（.xls）暂不支持在线预览，请下载后使用本地 Office 查看。';
    return;
  }
  if (TEXT_TYPES.includes(fileTypeUpper.value)) {
    kind.value = 'loading';
    try {
      const blob = await fetchBlob(documentContentPath(doc.id));
      textContent.value = await blob.text();
      kind.value = 'text';
    } catch (e) {
      kind.value = 'error';
      errorText.value = errorMessage(e);
    }
    return;
  }
  // 其余类型（含老二进制 DOC / PPT）不支持在线预览
  kind.value = 'unsupported';
}

/** Office 渲染完成回调：关闭渲染中遮罩。 */
function onOfficeRendered(): void {
  officeRendered.value = true;
}

/** Office 渲染失败回调：转入错误态并可重试。 */
function onOfficeError(err: unknown): void {
  officeRendered.value = false;
  kind.value = 'error';
  errorText.value = errorMessage(err);
}

/** 在新窗口打开当前 PDF（原生阅读器体验）。 */
function openInNewTab(): void {
  if (!objectUrl.value) {
    return;
  }
  window.open(objectUrl.value, '_blank', 'noopener');
}

/** 下载原文（attachment 端点，落盘名以文件名兜底）。 */
async function onDownload(): Promise<void> {
  const doc = props.document;
  if (!doc) {
    return;
  }
  try {
    await downloadFile(documentDownloadPath(doc.id), doc.fileName);
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
  }
}

watch(visible, (open) => {
  if (open) {
    void load();
  } else {
    resetState();
    kind.value = 'loading';
  }
});

onScopeDispose(revokeObjectUrl);
</script>

<template>
  <t-dialog
    v-model:visible="visible"
    :header="`在线阅读：${fileName}`"
    :width="dialogWidth"
    :close-on-overlay-click="false"
    placement="center"
    attach="body"
    :z-index="LAYER.dialog"
  >
    <div class="doc-reader">
      <div v-if="kind === 'loading'" class="doc-reader__loading">
        <t-loading size="small" text="正在加载原文……" />
      </div>

      <template v-else-if="kind === 'iframe'">
        <div class="doc-reader__toolbar">
          <t-button size="small" variant="text" @click="openInNewTab">在新窗口打开</t-button>
        </div>
        <iframe :src="objectUrl" class="doc-reader__frame" title="文档预览" />
      </template>

      <div v-else-if="kind === 'img'" class="doc-reader__image">
        <img :src="objectUrl" :alt="fileName" />
      </div>

      <div v-else-if="isOfficeKind" class="doc-reader__office">
        <VueOfficeDocx
          v-if="kind === 'docx'"
          :src="objectUrl"
          @rendered="onOfficeRendered"
          @error="onOfficeError"
        />
        <VueOfficeExcel
          v-else-if="kind === 'excel'"
          :src="objectUrl"
          @rendered="onOfficeRendered"
          @error="onOfficeError"
        />
        <VueOfficePptx v-else :src="objectUrl" @rendered="onOfficeRendered" @error="onOfficeError" />
        <div v-if="!officeRendered" class="doc-reader__office-loading">
          <t-loading size="small" text="正在渲染文档……" />
        </div>
      </div>

      <pre v-else-if="kind === 'text'" class="dda-mono doc-reader__text">{{ textContent }}</pre>

      <t-alert v-else-if="kind === 'error'" theme="error" title="文档加载失败">
        {{ errorText || '请稍后重试，或下载后查看' }}
        <template #operation>
          <t-button size="small" theme="primary" variant="outline" @click="load">重试</t-button>
        </template>
      </t-alert>

      <t-alert v-else theme="warning" :title="unsupportedTitle">
        {{ unsupportedDesc || '当前格式无法在线预览，请点击下方「下载」按钮获取原文件。' }}
      </t-alert>
    </div>

    <template #footer>
      <t-space>
        <t-button variant="outline" @click="visible = false">关闭</t-button>
        <t-button theme="primary" @click="onDownload">下载</t-button>
      </t-space>
    </template>
  </t-dialog>
</template>

<style scoped>
/*
 * 预览容器：纵向 flex 固定高度，五种形态（loading/iframe/img/office/text）统一填充并内部滚动，
 * 保证加载态与预览态同高、无跳动。本弹窗经 <Teleport> 渲染——禁用 scoped style 的 v-bind()，
 * 变量必须由样式表选择器定义（随 DOM 继承进入传送内容），见组件 Javadoc 约定。
 * 高度预算 = 弹窗 body 上限(80vh - 32px) − header/footer/body padding/PDF 工具条 ≈ 232px。
 */
.doc-reader {
  --dda-preview-h: max(420px, calc(80vh - 232px));
  display: flex;
  flex-direction: column;
  height: var(--dda-preview-h);
}

.doc-reader__loading {
  display: flex;
  flex: 1 1 auto;
  align-items: center;
  justify-content: center;
  min-height: 0;
}

.doc-reader__toolbar {
  display: flex;
  flex: none;
  justify-content: flex-end;
  margin-bottom: 6px;
}

.doc-reader__frame {
  width: 100%;
  height: auto;
  flex: 1 1 auto;
  min-height: 0;
  border: 1px solid var(--td-component-border, #e7e7e7);
  border-radius: 4px;
}

.doc-reader__image {
  display: flex;
  flex: 1 1 auto;
  justify-content: center;
  min-height: 0;
  overflow: auto;
}

.doc-reader__image img {
  max-width: 100%;
}

.doc-reader__office {
  position: relative;
  flex: 1 1 auto;
  min-height: 0;
  overflow: auto;
  border: 1px solid var(--td-component-border, #e7e7e7);
  border-radius: 4px;
}

.doc-reader__office-loading {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--td-bg-color-container, #fff);
}

.doc-reader__text {
  flex: 1 1 auto;
  min-height: 0;
  margin: 0;
  padding: 12px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
  background: var(--td-bg-color-secondarycontainer, #f3f3f3);
  border-radius: 4px;
}
</style>