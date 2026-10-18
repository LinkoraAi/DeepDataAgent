<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import { sha256HexOfFile } from '@/shared/crypto/sha256';
import { errorMessage } from '@/shared/composables/useAsync';
import { FILE_TYPES } from '@/shared/enums/dict';
import { LAYER } from '@/shared/constants/layers';
import FormSection from '@/shared/components/FormSection.vue';
import { precheckUpload, uploadDocument } from '../../api/documents';
import type { DuplicateWire, UploadResultWire } from '../../api/documents';
import {
  buildMeta,
  chunkModeLabel,
  describeDocChunkStrategy,
  fileTypeFromName,
  groupPrecheckHits,
  isChunkModeCompatible,
  parseChunkStrategy,
  validateUploadFile,
} from '../../mappers.documents';
import type { PrecheckHitGroup } from '../../mappers.documents';
import DocChunkStrategyDialog from './DocChunkStrategyDialog.vue';

/**
 * 文档上传对话框（多文件串行队列 + 逐文件分块策略）。
 * <p>选择文件 → 逐文件前置校验（白名单 + ≤100MB，不通过仅提示不入队）→ 串行处理有效文件：
 * 计算 SHA-256 → 判重预检（命中则暂停等待用户「仍要上传 / 跳过该文件」决策）→ multipart 上传。</p>
 * <p>结果三态：skipped → 「已按去重策略跳过」；overwritten 非空 → 列出旧文档删除中；否则成功。
 * REJECT 冲突（后端 409）直显 message。全部处理完成后 emit('uploaded') 供列表刷新与轮询。</p>
 * <p>分块策略：每个队列项默认继承库级策略（meta 不携带 chunkStrategy，服务端落 NULL）；
 * 可经共用弹窗逐文件修改，修改后的策略随 meta 提交并在首次解析即生效（无需重解析）。
 * 库级策略与某文件类型不兼容（后端上传矩阵会 400）时，队列项内前置警示并引导修改。</p>
 */
const props = defineProps<{
  /** 目标知识库ID */
  kbId: number;
  /** 库级分块策略 JSON 原文（继承态展示与弹窗回显默认值；不可用时为 null） */
  kbChunkStrategy?: string | null;
}>();

const emit = defineEmits<{
  /** 队列全部处理完成（供上层刷新列表并启动轮询） */
  uploaded: [];
}>();

/** 对话框显隐（v-model）。 */
const visible = defineModel<boolean>({ default: false });

/** 队列项处理状态。 */
type ItemStatus = 'pending' | 'hashing' | 'precheck' | 'conflict' | 'uploading' | 'success' | 'skipped' | 'failed';

/** 队列单项（持有 File 引用与逐项状态）。 */
interface UploadItem {
  /** 稳定键 */
  key: number;
  /** 原始文件 */
  file: File;
  /** 文件名 */
  name: string;
  /** 字节大小 */
  size: number;
  /** 当前状态 */
  status: ItemStatus;
  /** 状态附注（成功/跳过/失败原因） */
  message: string;
  /** 预检计算出的内容哈希（供冲突决策后复用，避免重算） */
  contentHash?: string;
  /** 预检命中摘要（冲突态展示用） */
  hits?: DuplicateWire[];
  /** 用户修改后的文档级分块策略 JSON（缺省 = 继承库级，meta 不携带该键） */
  chunkStrategy?: string;
}

/** 输入引用与状态。 */
const inputRef = ref<HTMLInputElement | null>(null);
const items = ref<UploadItem[]>([]);
/** 策略编辑弹窗显隐与当前编辑项的稳定键。 */
const strategyVisible = ref(false);
const strategyKey = ref<number | null>(null);
/** 当前编辑策略的队列项（弹窗 initialStrategy / fileType 取值源）。 */
const strategyItem = computed(
  () => items.value.find((entry) => entry.key === strategyKey.value) ?? null,
);
/** 校验未通过、被拒绝入队的文件（name + reason）。 */
const rejected = reactive<Array<{ name: string; reason: string }>>([]);
/** 串行处理进行中。 */
const processing = ref(false);
/** 是否已全部处理完成。 */
const done = ref(false);

let keySeed = 0;

/** accept 串：白名单扩展名。 */
const acceptAttr = FILE_TYPES.map((type) => `.${type.toLowerCase()}`).join(',');

/** 是否可发起处理：未在处理中且仍有待处理项。 */
const canStart = computed(() => !processing.value && items.value.some((item) => item.status === 'pending'));

/** 冲突态命中分组（取当前唯一处于 conflict 的项）。 */
function hitGroups(hits: DuplicateWire[] | undefined): PrecheckHitGroup[] {
  return groupPrecheckHits(hits ?? []);
}

/** 状态 → 中文标签。 */
const STATUS_LABELS: Record<ItemStatus, string> = {
  pending: '待上传',
  hashing: '计算哈希',
  precheck: '判重预检',
  conflict: '命中重复',
  uploading: '上传中',
  success: '成功',
  skipped: '已跳过',
  failed: '失败',
};

/** 状态 → t-tag 主题。 */
function statusTheme(status: ItemStatus): 'default' | 'primary' | 'success' | 'warning' | 'danger' {
  switch (status) {
    case 'success':
      return 'success';
    case 'skipped':
    case 'conflict':
      return 'warning';
    case 'failed':
      return 'danger';
    case 'hashing':
    case 'precheck':
    case 'uploading':
      return 'primary';
    default:
      return 'default';
  }
}

/** 人性化大小。 */
function humanSize(bytes: number): string {
  if (bytes >= 1024 * 1024) {
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  }
  return `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

/** 打开系统文件选择器。 */
function openPicker(): void {
  inputRef.value?.click();
}

/** 加入队列：逐文件前置校验，有效文件入队为待处理，无效文件仅记入拒绝列表。 */
function enqueue(files: File[]): void {
  done.value = false;
  for (const file of files) {
    const validation = validateUploadFile(file);
    if (!validation.ok) {
      rejected.push({ name: file.name, reason: validation.reason ?? '不符合要求' });
      continue;
    }
    items.value.push({ key: (keySeed += 1), file, name: file.name, size: file.size, status: 'pending', message: '' });
  }
}

/** input[type=file] change 处理（清空 value 允许重选同名文件）。 */
function onFileChange(event: Event): void {
  const input = event.target as HTMLInputElement;
  if (input.files) {
    enqueue(Array.from(input.files));
  }
  input.value = '';
}

/** 拖拽释放处理。 */
function onDrop(event: DragEvent): void {
  if (event.dataTransfer) {
    enqueue(Array.from(event.dataTransfer.files));
  }
}

/** 终结项状态判定（不再需要处理的项）。 */
function isTerminal(status: ItemStatus): boolean {
  return status === 'success' || status === 'skipped' || status === 'failed';
}

/** 收尾：结束处理态，若有实际处理过则通知上层。 */
function finish(): void {
  processing.value = false;
  done.value = true;
  if (items.value.length > 0) {
    emit('uploaded');
  }
}

/** 依据上传响应落定单项状态（三态）。 */
function finalize(item: UploadItem, result: UploadResultWire): void {
  if (result.skipped) {
    item.status = 'skipped';
    item.message = '已按去重策略跳过';
  } else if (result.overwritten && result.overwritten.length > 0) {
    item.status = 'success';
    const names = result.overwritten.map((doc) => doc.fileName).join('、');
    item.message = `已覆盖，旧文档删除中：${names}`;
  } else {
    item.status = 'success';
    item.message = '上传成功';
  }
}

/** 队列项对应的文件类型码（扩展名映射，供类型矩阵过滤与不兼容判定）。 */
function itemFileType(item: UploadItem): string {
  return fileTypeFromName(item.name);
}

/** 队列项策略展示标签：用户已修改显示所选模式名；未修改显示「继承库级（<库级模式名>）」。 */
function itemStrategyLabel(item: UploadItem): string {
  if (item.chunkStrategy) {
    return chunkModeLabel(parseChunkStrategy(item.chunkStrategy).chunkMode);
  }
  return describeDocChunkStrategy(null, props.kbChunkStrategy);
}

/** 库级策略生效模式（未配置 / 脏数据按 GENERAL 口径，与后端 resolveMode 一致）。 */
function kbEffectiveMode(): string {
  return parseChunkStrategy(props.kbChunkStrategy).chunkMode || 'GENERAL';
}

/** 未修改策略的队列项且继承的库级模式与该文件类型不兼容 → 前置警示（后端上传矩阵会 400）。 */
function isItemIncompatible(item: UploadItem): boolean {
  if (item.chunkStrategy) {
    return false;
  }
  return !isChunkModeCompatible(kbEffectiveMode(), itemFileType(item));
}

/** 打开某队列项的策略编辑弹窗（仅未处理项允许修改）。 */
function openStrategyDialog(item: UploadItem): void {
  if (item.status !== 'pending') {
    return;
  }
  strategyKey.value = item.key;
  strategyVisible.value = true;
}

/** 策略弹窗确认回写（上传形态不会发 null；发事件时项已非 pending 等异常场景静默忽略）。 */
function onItemStrategyConfirm(strategyJson: string | null): void {
  const item = items.value.find((entry) => entry.key === strategyKey.value);
  if (!item || strategyJson === null) {
    return;
  }
  item.chunkStrategy = strategyJson;
}

/** 执行单项上传（调用 multipart；REJECT 冲突 409 直显 message 为失败原因）。 */
async function doUpload(item: UploadItem): Promise<void> {
  item.status = 'uploading';
  item.message = '';
  try {
    const result = await uploadDocument(item.file, buildMeta(props.kbId, item.file, item.chunkStrategy));
    finalize(item, result);
  } catch (e) {
    item.status = 'failed';
    item.message = errorMessage(e);
  }
  await processNext();
}

/** 处理队列：定位首个待处理项，逐阶段推进；命中重复则暂停等待用户决策。 */
async function processNext(): Promise<void> {
  const item = items.value.find((entry) => entry.status === 'pending');
  if (!item) {
    finish();
    return;
  }
  // 阶段一：浏览器端计算 SHA-256
  item.status = 'hashing';
  try {
    item.contentHash = await sha256HexOfFile(item.file);
  } catch (e) {
    item.status = 'failed';
    item.message = `哈希计算失败：${errorMessage(e)}`;
    await processNext();
    return;
  }
  // 阶段二：判重预检（只读）
  item.status = 'precheck';
  let hits: DuplicateWire[] = [];
  try {
    hits = await precheckUpload({ kbId: props.kbId, fileName: item.name, contentHash: item.contentHash });
  } catch (e) {
    item.status = 'failed';
    item.message = `预检失败：${errorMessage(e)}`;
    await processNext();
    return;
  }
  if (hits.length > 0) {
    // 命中重复：暂停串行流，展示命中面板等待用户决策
    item.hits = hits;
    item.status = 'conflict';
    return;
  }
  await doUpload(item);
}

/** 冲突决策：仍要上传（由库级策略决定结果）。 */
async function resolveConflict(item: UploadItem, force: boolean): Promise<void> {
  if (!force) {
    item.status = 'skipped';
    item.message = '已跳过（用户选择）';
    await processNext();
    return;
  }
  await doUpload(item);
}

/** 开始串行处理。 */
async function start(): Promise<void> {
  if (processing.value || !canStart.value) {
    return;
  }
  processing.value = true;
  done.value = false;
  await processNext();
}

/** 复位内部状态（关闭后清脏）。 */
function resetState(): void {
  items.value = [];
  rejected.splice(0, rejected.length);
  processing.value = false;
  done.value = false;
}

/** 队列是否全部到达终结态（用于文案提示）。 */
const allSettled = computed(
  () => items.value.length > 0 && items.value.every((item) => isTerminal(item.status)),
);

/** 关闭时复位。 */
function onClosed(): void {
  resetState();
}

/** 移除队列中尚未处理的项。 */
function removeItem(key: number): void {
  const index = items.value.findIndex((item) => item.key === key);
  if (index >= 0 && items.value[index].status === 'pending') {
    items.value.splice(index, 1);
  }
}
</script>

<template>
  <t-dialog
    v-model:visible="visible"
    header="上传文档"
    :width="720"
    :close-on-overlay-click="false"
    placement="center"
    attach="body"
    :z-index="LAYER.dialog"
    @closed="onClosed"
  >
    <FormSection
      title="上传约束"
      hint="类型限 PDF/Office/TXT/MD/CSV/PNG/JPG/JPEG，单文件不超过 100MB；同名/同内容按库级去重策略处置；分块策略默认沿用知识库配置，可逐文件修改，修改后本次上传即生效、无需重新解析"
    >
      <div class="upload-drop" @click="openPicker" @dragover.prevent @drop.prevent="onDrop">
        <input
          ref="inputRef"
          type="file"
          multiple
          class="upload-drop__input"
          :accept="acceptAttr"
          @change="onFileChange"
        />
        <p class="upload-drop__hint">点击选择或拖拽文件到此处（可多选）</p>
      </div>
    </FormSection>

    <div v-if="rejected.length > 0" class="upload-rejected">
      <p v-for="(entry, idx) in rejected" :key="`r-${idx}`" class="upload-rejected__line">
        已拒绝「{{ entry.name }}」：{{ entry.reason }}
      </p>
    </div>

    <ul v-if="items.length > 0" class="upload-queue">
      <li v-for="item in items" :key="item.key" class="upload-queue__item">
        <div class="upload-queue__main">
          <span class="upload-queue__name" :title="item.name">{{ item.name }}</span>
          <span class="upload-queue__size">{{ humanSize(item.size) }}</span>
          <t-tag :theme="statusTheme(item.status)" variant="light" size="small">{{ STATUS_LABELS[item.status] }}</t-tag>
          <t-button
            v-if="item.status === 'pending'"
            size="extra-small"
            variant="text"
            theme="danger"
            @click="removeItem(item.key)"
          >
            移除
          </t-button>
        </div>

        <!-- 分块策略行：默认继承库级，可逐文件修改（首次解析即生效） -->
        <div class="upload-queue__strategy">
          <span class="upload-queue__strategy-label">分块策略：{{ itemStrategyLabel(item) }}</span>
          <t-button
            v-if="item.status === 'pending'"
            size="extra-small"
            variant="text"
            theme="primary"
            @click="openStrategyDialog(item)"
          >
            修改
          </t-button>
          <t-tooltip
            v-if="isItemIncompatible(item)"
            content="知识库级分块策略与该文件类型不兼容，上传会被后端拒绝；请先「修改」该文件策略，或调整知识库的切片模式"
            placement="top"
          >
            <t-tag theme="warning" variant="light" size="small">策略不兼容</t-tag>
          </t-tooltip>
        </div>

        <p v-if="item.message" class="upload-queue__msg">{{ item.message }}</p>

        <!-- 判重预检命中面板：展示命中文档与命中轴，等待用户决策 -->
        <div v-if="item.status === 'conflict'" class="upload-conflict">
          <p class="upload-conflict__lead">检测到库内重复，处理方式由知识库去重策略决定：</p>
          <div v-for="(group, gi) in hitGroups(item.hits)" :key="`g-${gi}`" class="upload-conflict__group">
            <span class="upload-conflict__axis">命中轴：{{ group.axisLabel }}</span>
            <span v-for="hit in group.hits" :key="hit.id" class="upload-conflict__hit">
              {{ hit.fileName }}（#{{ hit.id }}）
            </span>
          </div>
          <t-space size="small">
            <t-button size="small" variant="outline" @click="resolveConflict(item, false)">跳过该文件</t-button>
            <t-button size="small" theme="primary" :disabled="processing" @click="resolveConflict(item, true)">仍要上传</t-button>
          </t-space>
        </div>
      </li>
    </ul>

    <p v-if="allSettled && !processing" class="upload-done">全部处理完成，列表将自动刷新。</p>

    <template #footer>
      <t-space>
        <t-button variant="outline" :disabled="processing" @click="visible = false">关闭</t-button>
        <t-button theme="primary" :loading="processing" :disabled="!canStart" @click="start">开始上传</t-button>
      </t-space>
    </template>
  </t-dialog>

  <!-- 逐文件分块策略编辑弹窗（与文档列表共用；上传形态确认即回写队列项，不发恢复继承事件） -->
  <DocChunkStrategyDialog
    v-model="strategyVisible"
    :initial-strategy="strategyItem?.chunkStrategy ?? kbChunkStrategy ?? null"
    :file-type="strategyItem ? itemFileType(strategyItem) : ''"
    mode="upload"
    @confirm="onItemStrategyConfirm"
  />
</template>

<style scoped>
.upload-drop {
  padding: 20px 16px;
  border: 1px dashed var(--td-component-border, #dcdcdc);
  border-radius: 6px;
  text-align: center;
  cursor: pointer;
  transition: border-color 0.2s;
}

.upload-drop:hover {
  border-color: var(--td-brand-color);
}

.upload-drop__input {
  display: none;
}

.upload-drop__hint {
  margin: 0;
  color: var(--td-text-color-secondary, rgba(0, 0, 0, 0.6));
}

.upload-rejected {
  margin: 8px 0;
  padding: 8px 12px;
  background: var(--td-warning-color-1, #fff7e6);
  border-radius: 4px;
}

.upload-rejected__line {
  margin: 2px 0;
  font-size: 12px;
  color: var(--td-warning-color, #e37318);
}

.upload-queue {
  margin: 12px 0 0;
  padding: 0;
  list-style: none;
}

.upload-queue__item {
  padding: 8px 0;
  border-bottom: 1px solid var(--td-component-stroke, #f0f0f0);
}

.upload-queue__main {
  display: flex;
  align-items: center;
  gap: 8px;
}

.upload-queue__name {
  max-width: 320px;
  overflow: hidden;
  font-weight: 600;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.upload-queue__size {
  font-size: 12px;
  color: var(--td-text-color-placeholder, rgba(0, 0, 0, 0.4));
}

.upload-queue__strategy {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 2px;
}

.upload-queue__strategy-label {
  font-size: 12px;
  color: var(--td-text-color-secondary, rgba(0, 0, 0, 0.6));
}

.upload-queue__msg {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--td-text-color-secondary, rgba(0, 0, 0, 0.6));
}

.upload-conflict {
  margin-top: 8px;
  padding: 8px 12px;
  background: var(--td-bg-color-secondarycontainer, #f3f3f3);
  border-radius: 4px;
}

.upload-conflict__lead {
  margin: 0 0 6px;
  font-size: 12px;
  color: var(--td-text-color-secondary, rgba(0, 0, 0, 0.6));
}

.upload-conflict__group {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 6px;
  font-size: 12px;
}

.upload-conflict__axis {
  font-weight: 600;
}

.upload-conflict__hit {
  color: var(--td-text-color-primary, rgba(0, 0, 0, 0.9));
}

.upload-done {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--td-success-color, #2ba471);
}
</style>
