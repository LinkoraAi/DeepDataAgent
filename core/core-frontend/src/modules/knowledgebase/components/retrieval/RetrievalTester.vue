<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import EmptyGuide from '@/shared/components/EmptyGuide.vue';
import FormSection from '@/shared/components/FormSection.vue';
import { errorMessage } from '@/shared/composables/useAsync';
import { FUSION_TYPES, RETRIEVAL_CHANNELS, RETRIEVAL_STRATEGIES } from '@/shared/enums/dict';
import { MODEL_TYPE_RERANK, listEnabledProfiles } from '@/modules/model/api';
import type { ModelProfileResponse } from '@/modules/model/api';
import { getKnowledgeBase, updateRetrievalConfig } from '../../api/knowledgeBase';
import { retrieveChunks } from '../../api/retrieval';
import type { ChunkReference } from '../../api/retrieval';
import {
  CHANNEL_WEIGHT_CEIL,
  CHANNEL_WEIGHT_FLOOR,
  RERANK_TOP_K_MIN,
  RESULT_CHUNK_COUNT_MAX,
  RESULT_CHUNK_COUNT_MIN,
  RRF_K_MIN,
  SIMILAR_THRESHOLD_MAX,
  SIMILAR_THRESHOLD_MIN,
  buildStrategyJson,
  defaultRetrievalConfig,
  parseStrategyJson,
  validateRetrievalConfig,
} from '../../retrievalDraft';
import type { RetrievalDraft } from '../../retrievalDraft';
import ChunkMediaThumb from './ChunkMediaThumb.vue';

/**
 * 检索参数调试台（知识库详情「知识检索」页签）。
 * <p>左侧参数面板覆盖后端 RetrievalConfigValidator 的全部可写字段：策略 NAIVE/MIX、问题改写、
 * 结果数量 1~100、相似度阈值 0~1、重排（开关 + 模型 + topK）、融合（RRF 配 K；加权配三路权重，
 * 两分支互斥显示），右侧为检索执行区与命中切片卡片。</p>
 * <p>关键口径：库级策略参数由后端在检索链路内从知识库配置读取，<b>不经检索请求体下发</b>，
 * 故唯一的「搜索」动作先 PUT retrieval-config 保存配置、再调 chunks 端点检索，两步连续执行；
 * 面板值经 buildStrategyJson 归一，越界一律提交前拦截不发请求。</p>
 */
const props = defineProps<{
  /** 知识库ID */
  kbId: number;
}>();

/** 参数面板草稿（进入时按知识库配置回显，空配置/脏配置取业务默认值） */
const draft = reactive<RetrievalDraft>(defaultRetrievalConfig());
/** 字段级错误映射（键为字段路径） */
const errors = ref<Record<string, string>>({});
/** 检索问题 */
const query = ref('');
/** 命中切片 */
const chunks = ref<ChunkReference[]>([]);
/** 是否已发起过检索（区分「未检索」与「检索无命中」两种空态） */
const searched = ref(false);
/** 配置回显加载中 */
const loadingConfig = ref(false);
/** 保存检索策略中 */
const saving = ref(false);
/** 检索中 */
const retrieving = ref(false);
/** 面板错误文案（回显 / 保存 / 检索失败留痕） */
const panelError = ref('');
/** 重排模型选项（ENABLED + CHAT） */
const rerankModels = ref<ModelProfileResponse[]>([]);

/** 是否启用重排（决定是否展开模型与 topK） */
const rerankEnabled = computed(() => draft.rerankConfig.enabled);
/** 是否为 RRF 融合（决定是否展开 K 值并隐藏三路权重） */
const isRrf = computed(() => draft.fusionConfig.fusionType === 'RRF');
/** 是否有进行中的动作（保存与检索互斥，期间禁用输入与按钮） */
const busy = computed(() => saving.value || retrieving.value);
/** 当前策略说明文案 */
const strategyHint = computed(
  () => RETRIEVAL_STRATEGIES.find((item) => item.value === draft.strategyType)?.hint ?? '',
);
/** 策略单选项 */
const strategyOptions = computed(() => RETRIEVAL_STRATEGIES.map((item) => ({ value: item.value, label: item.label })));
/** 融合方式选项 */
const fusionOptions = computed(() => FUSION_TYPES.map((item) => ({ value: item.value, label: item.label })));
/** 召回通道选项（加权时的三键） */
const channelOptions = computed(() => RETRIEVAL_CHANNELS.map((item) => ({ value: item.value, label: item.label })));
/** 重排模型下拉数据源 */
const modelOptions = computed(() => rerankModels.value.map(
  (profile) => ({ value: profile.profileId, label: `${profile.displayName}（${profile.modelName}）` }),
));

/** 取字段错误文案（无错误返回空串，供模板条件渲染） */
function errorOf(key: string): string {
  return errors.value[key] ?? '';
}

/** 归一重排 topK：空值 / 非法值视为不限制（不提交该字段） */
function onRerankTopKChange(value: number | string): void {
  const text = typeof value === 'number' ? String(value) : value.trim();
  if (text === '') {
    draft.rerankConfig.topK = undefined;
    return;
  }
  const parsed = Number(text);
  draft.rerankConfig.topK = Number.isFinite(parsed) ? parsed : undefined;
}

/** 相似度滑杆回写（区间外或异常回传取下限，即不过滤） */
function onSimilarChange(value: unknown): void {
  const raw = Array.isArray(value) ? value[0] : value;
  draft.similarThreshold = typeof raw === 'number' && Number.isFinite(raw) ? raw : SIMILAR_THRESHOLD_MIN;
}

/** 重排开关回写 */
function onRerankEnabledChange(value: unknown): void {
  draft.rerankConfig.enabled = Boolean(value);
}

/** 问题改写开关回写 */
function onRewriteChange(value: unknown): void {
  draft.rewriteQuestion = Boolean(value);
}

/** 回显知识库当前检索策略（retrievalStrategy 为空串/null 时保持默认值） */
async function loadConfig(): Promise<void> {
  loadingConfig.value = true;
  try {
    const kb = await getKnowledgeBase(props.kbId);
    Object.assign(draft, parseStrategyJson(kb.retrievalStrategy));
  } catch (e) {
    panelError.value = `检索配置回显失败：${errorMessage(e)}`;
  } finally {
    loadingConfig.value = false;
  }
}

/** 加载重排模型下拉（仅启用中的重排模型，类型过滤由服务端完成） */
async function loadRerankModels(): Promise<void> {
  try {
    rerankModels.value = await listEnabledProfiles(MODEL_TYPE_RERANK);
  } catch (e) {
    MessagePlugin.error(`重排模型列表加载失败：${errorMessage(e)}`);
  }
}

/**
 * 检索门槛：问题必填，并追加参数校验并回填字段错误（搜索前必须先保存合法配置）。
 * @returns 可用于检索的问题文本；不合格返回 null
 */
function prepareQuery(): string | null {
  const trimmed = query.value.trim();
  if (trimmed === '') {
    MessagePlugin.warning('请输入检索问题');
    return null;
  }
  const validationErrors = validateRetrievalConfig(draft);
  errors.value = validationErrors;
  if (Object.keys(validationErrors).length > 0) {
    MessagePlugin.warning('请先修正检索参数错误');
    return null;
  }
  return trimmed;
}

/** 调用切片检索端点并渲染结果 */
async function runRetrieve(trimmedQuery: string): Promise<void> {
  retrieving.value = true;
  panelError.value = '';
  try {
    const response = await retrieveChunks({ kbId: props.kbId, query: trimmedQuery });
    chunks.value = response.chunks ?? [];
    searched.value = true;
  } catch (e) {
    panelError.value = `检索失败：${errorMessage(e)}`;
    MessagePlugin.error(panelError.value);
  } finally {
    retrieving.value = false;
  }
}

/** 搜索：先落库检索策略（后端据此裁剪召回与精排），保存成功后立即发起切片检索 */
async function onSearch(): Promise<void> {
  if (busy.value) {
    return;
  }
  const trimmed = prepareQuery();
  if (trimmed === null) {
    return;
  }
  saving.value = true;
  panelError.value = '';
  try {
    await updateRetrievalConfig(props.kbId, buildStrategyJson(draft));
    MessagePlugin.success('检索参数已保存');
  } catch (e) {
    panelError.value = `检索参数保存失败：${errorMessage(e)}`;
    MessagePlugin.error(panelError.value);
    return;
  } finally {
    saving.value = false;
  }
  await runRetrieve(trimmed);
}

/** 分值展示：保留两位小数 */
function formatScore(score: number): string {
  return Number.isFinite(score) ? score.toFixed(2) : '-';
}

onMounted(() => {
  void loadConfig();
  void loadRerankModels();
});
</script>

<template>
  <div class="retrieval-tester">
    <div class="retrieval-tester__panel">
      <FormSection title="召回策略" :hint="strategyHint">
        <div class="retrieval-field">
          <label class="retrieval-field__label">检索策略</label>
          <t-radio-group v-model="draft.strategyType" :options="strategyOptions" />
          <p v-if="errorOf('strategyType')" class="retrieval-field__error">{{ errorOf('strategyType') }}</p>
        </div>
      </FormSection>

      <FormSection title="重排" hint="开启后按重排模型重新打分，必须选择模型">
        <div class="retrieval-field retrieval-field--row">
          <label class="retrieval-field__label">启用重排</label>
          <t-switch :value="draft.rerankConfig.enabled" @change="onRerankEnabledChange" />
        </div>
        <template v-if="rerankEnabled">
          <div class="retrieval-field">
            <label class="retrieval-field__label">重排模型</label>
            <t-select
              v-model="draft.rerankConfig.modelProfileId"
              :options="modelOptions"
              placeholder="请选择启用中的对话模型"
              :status="errorOf('rerankConfig.modelProfileId') ? 'error' : 'default'"
            />
            <p v-if="errorOf('rerankConfig.modelProfileId')" class="retrieval-field__error">
              {{ errorOf('rerankConfig.modelProfileId') }}
            </p>
          </div>
          <div class="retrieval-field">
            <label class="retrieval-field__label">重排保留条数 topK（可选）</label>
            <t-input-number
              class="retrieval-field__number"
              :value="draft.rerankConfig.topK"
              :min="RERANK_TOP_K_MIN"
              theme="column"
              placeholder="留空表示不限制"
              @update:value="onRerankTopKChange"
            />
            <p v-if="errorOf('rerankConfig.topK')" class="retrieval-field__error">{{ errorOf('rerankConfig.topK') }}</p>
          </div>
        </template>
      </FormSection>

      <FormSection title="召回" hint="阈值越高结果越少；数量决定重排后的返回条数">
        <div class="retrieval-field retrieval-field--row">
          <label class="retrieval-field__label">问题改写</label>
          <t-switch :value="draft.rewriteQuestion" @change="onRewriteChange" />
          <span class="retrieval-field__suffix">{{ draft.rewriteQuestion ? '检索前先改写问题' : '按原问题检索' }}</span>
        </div>
        <div class="retrieval-field">
          <label class="retrieval-field__label">
            结果返回数量（{{ RESULT_CHUNK_COUNT_MIN }}~{{ RESULT_CHUNK_COUNT_MAX }}）
          </label>
          <t-input-number
            v-model="draft.resultChunkCount"
            class="retrieval-field__number"
            :min="RESULT_CHUNK_COUNT_MIN"
            :max="RESULT_CHUNK_COUNT_MAX"
            theme="column"
            :status="errorOf('resultChunkCount') ? 'error' : 'default'"
          />
          <p v-if="errorOf('resultChunkCount')" class="retrieval-field__error">{{ errorOf('resultChunkCount') }}</p>
        </div>
        <div class="retrieval-field">
          <label class="retrieval-field__label">
            相似度阈值：{{ draft.similarThreshold.toFixed(2) }}（0 表示不过滤）
          </label>
          <t-slider
            :value="draft.similarThreshold"
            :min="SIMILAR_THRESHOLD_MIN"
            :max="SIMILAR_THRESHOLD_MAX"
            :step="0.05"
            :labels="false"
            @change="onSimilarChange"
          />
          <p v-if="errorOf('similarThreshold')" class="retrieval-field__error">{{ errorOf('similarThreshold') }}</p>
        </div>
      </FormSection>

      <FormSection title="多路融合" hint="RRF 配 K 值；加权求和配三路权重（互斥）">
        <div class="retrieval-field">
          <label class="retrieval-field__label">融合方式</label>
          <t-select v-model="draft.fusionConfig.fusionType" :options="fusionOptions" />
          <p v-if="errorOf('fusionConfig.fusionType')" class="retrieval-field__error">
            {{ errorOf('fusionConfig.fusionType') }}
          </p>
        </div>
        <div v-if="isRrf" class="retrieval-field">
          <label class="retrieval-field__label">RRF 常数 K（大于 {{ RRF_K_MIN }}，默认 60）</label>
          <t-input-number v-model="draft.fusionConfig.rrfK" class="retrieval-field__number" :min="RRF_K_MIN" theme="column" />
          <p v-if="errorOf('fusionConfig.rrfK')" class="retrieval-field__error">{{ errorOf('fusionConfig.rrfK') }}</p>
        </div>
        <template v-else>
          <div v-for="channel in channelOptions" :key="channel.value" class="retrieval-field">
            <label class="retrieval-field__label">{{ channel.label }}通道权重（0~1，不含 0 与 1）</label>
            <t-input-number
              v-model="draft.fusionConfig.channelDenseWeight[channel.value]"
              class="retrieval-field__number"
              :min="CHANNEL_WEIGHT_FLOOR"
              :max="CHANNEL_WEIGHT_CEIL"
              :step="0.05"
              :decimal-places="2"
              theme="column"
              :status="errorOf(`fusionConfig.channelDenseWeight.${channel.value}`) ? 'error' : 'default'"
            />
            <p v-if="errorOf(`fusionConfig.channelDenseWeight.${channel.value}`)" class="retrieval-field__error">
              {{ errorOf(`fusionConfig.channelDenseWeight.${channel.value}`) }}
            </p>
          </div>
        </template>
      </FormSection>
    </div>

    <div class="retrieval-tester__main">
      <div class="retrieval-tester__toolbar">
        <div class="retrieval-tester__search">
          <t-input
            v-model="query"
            class="retrieval-tester__query"
            placeholder="输入检索问题，搜索时自动保存检索参数"
            size="large"
            :disabled="busy"
            @enter="onSearch"
          >
            <template #suffix>
              <t-button
                class="retrieval-tester__search-btn"
                theme="primary"
                size="small"
                :loading="busy"
                @click="onSearch"
              >
                <template #icon><t-icon name="search" /></template>
                搜索
              </t-button>
            </template>
          </t-input>
        </div>
      </div>

      <p class="retrieval-tester__tip">
        提示：点击搜索（或回车）会先保存左侧检索参数——结果数量、相似度阈值、重排与融合等库级配置，
        保存成功后立即按新配置召回切片。
      </p>

      <div v-if="panelError" class="retrieval-tester__error">{{ panelError }}</div>

      <div class="retrieval-tester__results">
        <div class="retrieval-tester__results-head">
          <span>命中切片</span>
          <t-tag v-if="searched" size="small" variant="light">{{ chunks.length }} 条</t-tag>
        </div>
        <t-loading :loading="retrieving || loadingConfig" size="small">
          <ol v-if="chunks.length > 0" class="retrieval-results">
            <li v-for="(chunk, index) in chunks" :key="chunk.chunkId" class="retrieval-card">
              <header class="retrieval-card__head">
                <span class="retrieval-card__index">#{{ index + 1 }}</span>
                <t-tag size="small" theme="primary" variant="light">score {{ formatScore(chunk.score) }}</t-tag>
                <span class="retrieval-card__source">{{ chunk.sourceFile || '未知来源文件' }}</span>
              </header>
              <p class="retrieval-card__content">{{ chunk.chunkContent || '（切片正文不可用，可能已被清理）' }}</p>
              <ChunkMediaThumb v-if="chunk.modalFile" :path="chunk.modalFile" />
            </li>
          </ol>
          <EmptyGuide
            v-else-if="searched"
            description="未命中任何切片：可降低相似度阈值、改用 MIX 策略，或确认该知识库已完成文档解析"
          />
          <EmptyGuide v-else description="输入问题后点击搜索，系统会先保存左侧检索参数，再按新配置召回切片" />
        </t-loading>
      </div>
    </div>
  </div>
</template>

<style scoped>
.retrieval-tester {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.retrieval-tester__panel {
  flex: 0 0 340px;
}

.retrieval-tester__main {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
}

.retrieval-tester__toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
}

/* 搜索框：按钮以内置 suffix 插槽内嵌右侧，与输入框同体布局，避免浮层遮挡点击 */
.retrieval-tester__search {
  flex: 1;
}

.retrieval-tester__query {
  width: 100%;
}

.retrieval-tester__search-btn {
  margin-right: 4px;
  border-radius: 8px;
}

.retrieval-tester__tip {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--td-text-color-secondary, rgba(0, 0, 0, 0.6));
}

.retrieval-tester__error {
  margin-top: 8px;
  padding: 8px 12px;
  font-size: 13px;
  color: var(--td-error-color, #d54941);
  background: var(--td-error-color-1, #fdecee);
  border-radius: 4px;
}

.retrieval-tester__results {
  margin-top: 12px;
  padding: 12px;
  background: var(--td-bg-color-container, #fff);
  border: 1px solid var(--td-component-border, #e7e7e7);
  border-radius: 6px;
}

.retrieval-tester__results-head {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--td-text-color-primary, rgba(0, 0, 0, 0.9));
}

.retrieval-results {
  margin: 0;
  padding: 0;
  list-style: none;
}

.retrieval-card {
  margin-bottom: 10px;
  padding: 10px 12px;
  border: 1px solid var(--td-component-border, #e7e7e7);
  border-radius: 6px;
}

.retrieval-card__head {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 6px;
}

.retrieval-card__index {
  font-size: 12px;
  color: var(--td-text-color-secondary, rgba(0, 0, 0, 0.6));
}

.retrieval-card__source {
  overflow: hidden;
  font-size: 12px;
  color: var(--td-text-color-secondary, rgba(0, 0, 0, 0.6));
  text-overflow: ellipsis;
  white-space: nowrap;
}

.retrieval-card__content {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  word-break: break-word;
  white-space: pre-wrap;
  color: var(--td-text-color-primary, rgba(0, 0, 0, 0.9));
}

.retrieval-field {
  margin-bottom: 12px;
}

.retrieval-field--row {
  display: flex;
  gap: 8px;
  align-items: center;
}

.retrieval-field--row .retrieval-field__label {
  margin-bottom: 0;
}

.retrieval-field__label {
  display: block;
  margin-bottom: 4px;
  font-size: 13px;
  color: var(--td-text-color-primary, rgba(0, 0, 0, 0.9));
}

/* 数值输入框铺满面板列宽，避免数量 / K 值 / 权重输入框过窄难以辨认 */
.retrieval-field__number {
  width: 100%;
}

.retrieval-field__suffix {
  font-size: 12px;
  color: var(--td-text-color-secondary, rgba(0, 0, 0, 0.6));
}

.retrieval-field__error {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--td-error-color, #d54941);
}
</style>
