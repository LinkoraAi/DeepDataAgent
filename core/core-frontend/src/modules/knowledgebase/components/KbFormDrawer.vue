<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import FormSection from '@/shared/components/FormSection.vue';
import { errorMessage } from '@/shared/composables/useAsync';
import { KB_LANGUAGES, DEDUP_ACTIONS, DEDUP_MATCH_RULES } from '@/shared/enums/dict';
import { listEnabledProfiles, MODEL_TYPE_CHAT, MODEL_TYPE_EMBEDDING } from '@/modules/model/api';
import type { ModelProfileResponse } from '@/modules/model/api';
import { createKnowledgeBase, updateKnowledgeBase } from '../api/knowledgeBase';
import type { KnowledgeBaseWire } from '../api/knowledgeBase';
import { buildCreateRequest, buildUpdateRequest, defaultDraft, draftFromKb, validateDraft } from '../kbDraft';
import type { KbDraft } from '../kbDraft';
import ChunkStrategyForm from './ChunkStrategyForm.vue';
import EntityTypesEditor from './EntityTypesEditor.vue';

/**
 * 知识库创建 / 编辑抽屉：五分区表单（基本信息 / 模型配置 / 解析与切片 / 上传去重 / 实体类型）。
 * <p>提交前经 validateDraft 字段级前置拦截（不通过 MUST NOT 发起请求）；后端 409（重名）/400（校验）
 * 直显原因。编辑态嵌入模型置灰锁定（后端创建后不可变更、更新静默忽略）。</p>
 */
const props = defineProps<{
  /** 抽屉可见性（v-model） */
  modelValue: boolean;
  /** 编辑目标；为 null/undefined 表示新建 */
  kb?: KnowledgeBaseWire | null;
}>();

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  /** 保存成功回传最新知识库快照（供上层刷新） */
  saved: [value: KnowledgeBaseWire];
}>();

const draft = ref<KbDraft>(defaultDraft());
const errors = ref<Record<string, string>>({});
const submitting = ref(false);
const embeddingRaw = ref<ModelProfileResponse[]>([]);
const multiRaw = ref<ModelProfileResponse[]>([]);

const isEdit = computed(() => props.kb !== null && props.kb !== undefined);
const title = computed(() => (isEdit.value ? `编辑知识库：${props.kb?.name ?? ''}` : '新建知识库'));

/** 语言下拉选项 */
const languageOptions = KB_LANGUAGES;
/** 去重匹配轴选项 */
const matchRuleOptions = DEDUP_MATCH_RULES;
/** 去重冲突动作选项 */
const conflictActionOptions = DEDUP_ACTIONS;

/** 嵌入模型选项（编辑态补回当前锁定值，避免置灰后空白） */
const embeddingOptions = computed(() => toOptions(embeddingRaw.value, draft.value.embeddingProfileId));
/** 多模态模型选项 */
const multiModelOptions = computed(() => toOptions(multiRaw.value, draft.value.multiModelProfileId));

/** 模型列表转下拉选项；当前选中值缺失时补一条占位项 */
function toOptions(list: ModelProfileResponse[], currentId: string): Array<{ value: string; label: string }> {
  const options = list.map((profile) => ({ value: profile.profileId, label: profile.displayName }));
  if (currentId !== '' && !options.some((option) => option.value === currentId)) {
    options.unshift({ value: currentId, label: `当前模型（${currentId}）` });
  }
  return options;
}

/** 读取字段错误 */
function fieldError(key: string): string | undefined {
  return errors.value[key];
}

/** 加载启用中的嵌入 / 对话模型（供两级下拉） */
async function loadProfiles(): Promise<void> {
  try {
    const [embedding, chat] = await Promise.all([
      listEnabledProfiles(MODEL_TYPE_EMBEDDING),
      listEnabledProfiles(MODEL_TYPE_CHAT),
    ]);
    embeddingRaw.value = embedding;
    multiRaw.value = chat;
  } catch (e) {
    MessagePlugin.error(`模型列表加载失败：${errorMessage(e)}`);
  }
}

/** 打开抽屉时按新建/编辑初始化草稿并清空历史错误，并行加载模型选项 */
watch(
  () => props.modelValue,
  (open) => {
    if (!open) {
      return;
    }
    errors.value = {};
    submitting.value = false;
    draft.value = props.kb ? draftFromKb(props.kb) : defaultDraft();
    void loadProfiles();
  },
);

/** 关闭抽屉 */
function close(): void {
  emit('update:modelValue', false);
}

/** 提交：先前置校验拦截，再按新建/编辑装配请求；失败直显后端原因 */
async function submit(): Promise<void> {
  const validationErrors = validateDraft(draft.value);
  errors.value = validationErrors;
  if (Object.keys(validationErrors).length > 0) {
    MessagePlugin.error('请先修正表单中标红的字段');
    return;
  }
  submitting.value = true;
  try {
    const saved = isEdit.value && props.kb
      ? await updateKnowledgeBase(props.kb.id, buildUpdateRequest(draft.value))
      : await createKnowledgeBase(buildCreateRequest(draft.value));
    MessagePlugin.success(isEdit.value ? '知识库已更新' : '知识库已创建');
    emit('saved', saved);
    close();
  } catch (e) {
    // 409 名称重复 / 400 后端校验：直显后端原因（已在 http 基座归一为 ApiError.message）
    MessagePlugin.error(errorMessage(e));
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <t-drawer
    :visible="modelValue"
    :header="title"
    size="640px"
    :close-btn="true"
    :footer="false"
    @update:visible="(v: boolean) => emit('update:modelValue', v)"
  >
    <div class="kb-form">
      <FormSection title="基本信息">
        <div class="kb-field">
          <label class="kb-field__label">名称<span class="kb-field__req">*</span></label>
          <t-input
            v-model="draft.name"
            placeholder="全局唯一，1~128 个字符"
            :max-length="128"
            :status="fieldError('name') ? 'error' : 'default'"
            show-limit-number
          />
          <p v-if="fieldError('name')" class="kb-field__error">{{ fieldError('name') }}</p>
        </div>
        <div class="kb-field">
          <label class="kb-field__label">描述</label>
          <t-textarea
            v-model="draft.description"
            placeholder="简要说明该知识库用途（可选）"
            :autosize="{ minRows: 2, maxRows: 4 }"
          />
        </div>
        <div class="kb-field">
          <label class="kb-field__label">语言<span class="kb-field__req">*</span></label>
          <t-select v-model="draft.language" :options="languageOptions" />
          <p v-if="fieldError('language')" class="kb-field__error">{{ fieldError('language') }}</p>
        </div>
      </FormSection>

      <FormSection title="模型配置" hint="嵌入模型引用 ENABLED 的嵌入模型；多模态模型引用 ENABLED 的对话模型（必填）">
        <div class="kb-field">
          <label class="kb-field__label">嵌入模型<span class="kb-field__req">*</span></label>
          <t-select
            v-model="draft.embeddingProfileId"
            :options="embeddingOptions"
            :disabled="isEdit"
            placeholder="选择嵌入模型"
            :status="fieldError('embeddingProfileId') ? 'error' : 'default'"
          />
          <p v-if="isEdit" class="kb-field__hint">嵌入模型创建后不可修改。</p>
          <p v-else-if="fieldError('embeddingProfileId')" class="kb-field__error">{{ fieldError('embeddingProfileId') }}</p>
        </div>
        <div class="kb-field">
          <label class="kb-field__label">多模态模型<span class="kb-field__req">*</span></label>
          <t-select
            v-model="draft.multiModelProfileId"
            :options="multiModelOptions"
            placeholder="选择多模态（对话）模型"
            :status="fieldError('multiModelProfileId') ? 'error' : 'default'"
          />
          <p v-if="fieldError('multiModelProfileId')" class="kb-field__error">{{ fieldError('multiModelProfileId') }}</p>
        </div>
      </FormSection>

      <FormSection title="解析与切片">
        <ChunkStrategyForm
          v-model:parse-provider="draft.parseProvider"
          v-model:mineru="draft.mineru"
          v-model:chunk-mode="draft.chunkMode"
          v-model:general="draft.general"
          :mineru-api-base-url-error="fieldError('mineru.apiBaseUrl')"
          :chunk-token-num-error="fieldError('general.chunkTokenNum')"
          :overlapped-percent-error="fieldError('general.overlappedPercent')"
          :delimiter-error="fieldError('general.delimiter')"
        />
      </FormSection>

      <FormSection title="上传去重" hint="决定同名/同内容文档上传时的判重轴与冲突处置">
        <div class="kb-field">
          <label class="kb-field__label">匹配轴</label>
          <t-select v-model="draft.dedup.matchRule" :options="matchRuleOptions" />
        </div>
        <div class="kb-field">
          <label class="kb-field__label">冲突动作</label>
          <t-select
            v-model="draft.dedup.conflictAction"
            :options="conflictActionOptions"
            :disabled="draft.dedup.matchRule === 'NONE'"
            :status="fieldError('dedup.conflictAction') ? 'error' : 'default'"
            placeholder="选择冲突动作"
          />
          <p v-if="draft.dedup.matchRule === 'NONE'" class="kb-field__hint">不检测重复时，冲突动作不生效。</p>
          <p v-else-if="fieldError('dedup.conflictAction')" class="kb-field__error">{{ fieldError('dedup.conflictAction') }}</p>
        </div>
      </FormSection>

      <FormSection title="实体类型" hint="知识图谱抽取的自定义实体类型（内置 12 类不可配置）">
        <EntityTypesEditor v-model="draft.entityTypes" :error="fieldError('entityTypes')" />
      </FormSection>

      <div class="kb-form__footer">
        <t-button variant="outline" :disabled="submitting" @click="close">取消</t-button>
        <t-button theme="primary" :loading="submitting" @click="submit">
          {{ isEdit ? '保存修改' : '创建知识库' }}
        </t-button>
      </div>
    </div>
  </t-drawer>
</template>

<style scoped>
.kb-form {
  padding-bottom: 8px;
}

.kb-field {
  margin-bottom: 12px;
}

.kb-field:last-child {
  margin-bottom: 0;
}

.kb-field__label {
  display: block;
  margin-bottom: 4px;
  font-size: 13px;
  color: var(--td-text-color-primary, rgba(0, 0, 0, 0.9));
}

.kb-field__req {
  margin-left: 2px;
  color: var(--td-error-color, #d54941);
}

.kb-field__hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--td-text-color-secondary, rgba(0, 0, 0, 0.6));
}

.kb-field__error {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--td-error-color, #d54941);
}

.kb-form__footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}
</style>
