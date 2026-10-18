<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import TwoColumnSection from '@/shared/components/TwoColumnSection.vue';
import JsonEditor from '@/shared/components/JsonEditor.vue';
import { errorMessage, useAsync } from '@/shared/composables/useAsync';
import { confirmDangerous } from '@/shared/composables/useConfirm';
import { createAgent, publishVersion } from '../api/agents';
import type { AgentConfigPayload, AgentWire } from '../api/agents';
import { listCloudModels } from '../api/cloud-models';
import type { ModelCatalogDto } from '../api/cloud-models';
import { listEnabledProfiles, MODEL_TYPE_CHAT } from '@/modules/model/api';
import type { ModelProfileResponse } from '@/modules/model/api';
import {
  agentToDraft,
  buildConfigRequest,
  createEmptyAgentDraft,
  draftToRawJson,
  firstDraftError,
  hasDraftErrors,
  inferModelOrigin,
  rawJsonToDraft,
  validateDraft,
} from '../mappers';
import type { AgentDraft, AgentDraftErrors, AgentDraftMode, ModelOrigin } from '../mappers';
import CustomToolEditor from './CustomToolEditor.vue';
import SkillBindingPicker from './SkillBindingPicker.vue';
import ToolPolicyEditor from './ToolPolicyEditor.vue';

/**
 * 智能体配置抽屉（创建与发布新版本复用同一表单，全量提交）。
 * 渲染 / Raw 双模式：Raw 直接编辑整份将提交配置 JSON，提交前经 rawJsonToDraft 校验并归一
 * （废止字段出现即拒绝，保证载荷零输出）。渲染模式覆盖名称/描述/系统提示词/模型（双来源）/
 * 内置工具权限策略/自定义工具/技能绑定/元数据。</p>
 * <p>模型字段双模式：默认「模型配置直选」（本租户启用中的对话模型，model_profile_id 直接绑定，
 * 发布期固化进版本快照）；可切「模型目录档位」（auto/ultimate 等抽象档位 + 按档位能力集过滤的
 * effort/上下文窗口调优，运行时经内部供应商映射解析到模型配置）。回显按两名单集合匹配自动判定来源。</p>
 * <p>模式语义：create → POST /agents（生成版本 1）；publish → POST /agents/{id}/versions
 * （版本号由服务端台账递增）。提交前 MUST 告知"将生成新版本"。</p>
 */
const props = defineProps<{
  /** 抽屉可见性（v-model:visible） */
  visible: boolean;
  /** create=新建智能体；publish=发布新版本 */
  mode: 'create' | 'publish';
  /** 目标智能体（publish 模式必填，用于取 ID 与当前版本号；新建模式为 null） */
  agent: AgentWire | null;
  /** 发版底稿快照（版本页"以此版本为底发新版"传入；缺省用 agent 当前生效配置） */
  base?: AgentWire | null;
}>();

const emit = defineEmits<{
  'update:visible': [visible: boolean];
  'saved': [agent: AgentWire];
}>();

/** 表单草稿（渲染模式字段直绑）。 */
const draft = reactive<AgentDraft>(createEmptyAgentDraft());
/** Raw 模式整份配置 JSON 文本。 */
const rawJson = ref('{}');
/** 是否已尝试提交（控制字段错误显示时机）。 */
const submitted = ref(false);
/** 可选模型配置清单（本租户启用中的对话模型）。 */
const profiles = ref<ModelProfileResponse[]>([]);
/** 模型目录档位清单（只读种子，目录态选项与调优能力集来源）。 */
const catalog = ref<ModelCatalogDto[]>([]);

const modelTask = useAsync<ModelProfileResponse[]>();
const catalogTask = useAsync<ModelCatalogDto[]>();
const submitTask = useAsync<AgentWire>();

/** 模型清单加载中（顶层 ref，模板自动解包）。 */
const modelsLoading = modelTask.loading;
/** 模型目录加载中（顶层 ref，模板自动解包）。 */
const catalogLoading = catalogTask.loading;
/** 提交中。 */
const submitting = submitTask.loading;

/** 回显来源判定候选（两名单集合匹配）。 */
const originCandidates = computed(() => ({
  profileIds: profiles.value.map((item) => item.profileId),
  catalogIds: catalog.value.map((item) => item.id),
}));

const errors = computed<AgentDraftErrors>(() => validateDraft(
  { ...draft },
  catalog.value.length ? originCandidates.value.catalogIds : undefined,
));
const baseVersion = computed(() => props.base?.version ?? props.agent?.version ?? 0);
/** 发版提示：契约不暴露 latest_version，发布号由服务端台账递增，故不预测具体号。 */
const versionNotice = computed(() => (props.mode === 'publish'
  ? `配置即版本：提交将由服务端按版本台账递增生成新快照（当前生效 v${baseVersion.value}），不覆盖历史版本；如需回退请在版本页激活旧版本。`
  : '配置即版本：创建即生成版本 v1 并置为当前生效版本。'));
/** 提交按钮文案。 */
const submitLabel = computed(() => (props.mode === 'publish' ? '发布新版本' : '创建智能体'));

/** 模型下拉选项（展示名 + 调用模型名，值 = model_profile_id 直接绑定）。 */
const modelOptions = computed(() => profiles.value.map((item) => ({
  value: item.profileId,
  label: `${item.displayName}（${item.modelName}）`,
})));

/** 目录档位下拉选项（展示名；后端仅返回启用档位，此处再过滤一次兜底）。 */
const catalogOptions = computed(() => catalog.value
  .filter((item) => item.isEnabled)
  .map((item) => ({ value: item.id, label: item.displayName })));

/** 目录态当前选中的档位条目（提供调优能力集；直选态或未选中为 null）。 */
const selectedCatalogItem = computed<ModelCatalogDto | null>(() => (
  draft.modelOrigin === 'catalog'
    ? catalog.value.find((item) => item.id === draft.modelId) ?? null
    : null
));

/** effort 下拉选项（限所选档位支持集，空 = 控件隐藏，档位缺省生效）。 */
const effortOptions = computed(() => (selectedCatalogItem.value?.efforts ?? [])
  .map((value) => ({ value, label: value })));

/** 上下文窗口下拉选项（限所选档位可用集，空 = 控件隐藏，档位缺省生效）。 */
const contextWindowOptions = computed(() => (selectedCatalogItem.value?.availableContextWindows ?? [])
  .map((value) => ({ value, label: String(value) })));

/** 载入启用中的对话模型配置（失败直显原因，下拉留空可重试）。 */
async function loadModels(): Promise<void> {
  const list = await modelTask.run(() => listEnabledProfiles(MODEL_TYPE_CHAT));
  if (modelTask.error.value) {
    MessagePlugin.error(errorMessage(modelTask.error.value));
    profiles.value = [];
    return;
  }
  profiles.value = list ?? [];
}

/** 载入模型目录档位（失败仅置空：直选态不受影响，目录态提交由后端目录校验兜底）。 */
async function loadCatalog(): Promise<void> {
  const list = await catalogTask.run(() => listCloudModels());
  catalog.value = catalogTask.error.value ? [] : (list ?? []);
}

/** 装配 Raw 文本（配置尚不完整时回落骨架，不阻断界面切换）。 */
function safeRawJson(state: AgentDraft): string {
  try {
    return draftToRawJson(state);
  } catch {
    return '{}';
  }
}

/** 打开抽屉：按模式初始化草稿（发版取底稿快照，新建取空表单）。 */
watch(
  () => props.visible,
  (open) => {
    if (!open) {
      return;
    }
    submitted.value = false;
    const source = props.mode === 'publish' ? (props.base ?? props.agent) : null;
    const next = source ? agentToDraft(source, originCandidates.value) : createEmptyAgentDraft();
    next.mode = 'simple';
    // 先以空草稿覆盖，清掉上一次编辑残留的数组/策略键，再合并本次底稿
    Object.assign(draft, createEmptyAgentDraft(), next);
    rawJson.value = safeRawJson(draft);
    if (!profiles.value.length) {
      void loadModels();
    }
    if (!catalog.value.length) {
      void loadCatalog();
    }
  },
);

// 两名单晚于底稿就绪时，对当前草稿的模型来源做一次集合匹配校准（空 modelId 不动）
watch([profiles, catalog], () => {
  if (draft.mode === 'simple' && draft.modelId) {
    const { profileIds, catalogIds } = originCandidates.value;
    draft.modelOrigin = inferModelOrigin(draft.modelId, profileIds, catalogIds);
  }
});

/** 切换渲染/Raw：进入 Raw 导出 JSON，回到渲染回填结构化字段（解析失败则留在 Raw）。 */
function onModeChange(value: AgentDraftMode): void {
  if (value === draft.mode) {
    return;
  }
  if (value === 'raw') {
    try {
      rawJson.value = draftToRawJson({ ...draft });
    } catch (e) {
      MessagePlugin.warning(e instanceof Error ? e.message : '当前配置尚不完整');
      return;
    }
    draft.mode = 'raw';
    return;
  }
  try {
    const parsed = rawJsonToDraft(rawJson.value, originCandidates.value);
    Object.assign(draft, parsed, { mode: 'simple' as AgentDraftMode });
    rawJson.value = safeRawJson(draft);
  } catch (e) {
    MessagePlugin.error(e instanceof Error ? e.message : 'Raw JSON 无法解析');
    return;
  }
}

/** 模式切换（单选回传值归一为联合类型）。 */
function onFormModeChange(value: unknown): void {
  onModeChange(String(value) === 'raw' ? 'raw' : 'simple');
}

/** 提交前解析出待装配草稿（Raw 模式以 Raw JSON 为唯一真相源）。 */
function resolveDraft(): AgentDraft | null {
  if (draft.mode !== 'raw') {
    return { ...draft };
  }
  try {
    return rawJsonToDraft(rawJson.value, originCandidates.value);
  } catch (e) {
    MessagePlugin.error(e instanceof Error ? e.message : '配置 JSON 校验未通过');
    return null;
  }
}

/** 模型来源切换：两态取值域不相交，重置引用与调优字段待用户重选。 */
function onOriginChange(value: unknown): void {
  draft.modelOrigin = value === 'catalog' ? 'catalog' : 'profile';
  draft.modelId = '';
  draft.effort = '';
  draft.contextWindow = null;
}

/** effort 选择归一（TDesign clearable 清空回传非字符串值 → 落空串 = 档位缺省）。 */
function onEffortSelect(value: unknown): void {
  draft.effort = typeof value === 'string' ? value : '';
}

/** 上下文窗口选择归一（清空回传非数值 → null = 档位缺省）。 */
function onWindowSelect(value: unknown): void {
  draft.contextWindow = typeof value === 'number' ? value : null;
}

/** 目录态档位切换：档位能力集不含当前调优值时清除残留（切换档位重置不兼容值）。 */
function onCatalogModelChange(): void {
  const item = selectedCatalogItem.value;
  if (draft.effort && !(item?.efforts ?? []).includes(draft.effort)) {
    draft.effort = '';
  }
  if (draft.contextWindow !== null && !(item?.availableContextWindows ?? []).includes(draft.contextWindow)) {
    draft.contextWindow = null;
  }
}

/** 切换模型：清掉 Raw/底稿残留的目录调优字段，避免直选模型配置携带无效档位。 */
function onModelChange(): void {
  draft.effort = '';
  draft.contextWindow = null;
}

function close(): void {
  emit('update:visible', false);
}

/** 提交：校验 → 告知将生成新版本 → 创建或发版。 */
async function onSubmit(): Promise<void> {
  submitted.value = true;
  const state = resolveDraft();
  if (!state) {
    return;
  }
  const messages = validateDraft(state);
  if (hasDraftErrors(messages)) {
    MessagePlugin.warning(firstDraftError(messages));
    return;
  }

  let payload: AgentConfigPayload;
  try {
    payload = buildConfigRequest(state);
  } catch (e) {
    MessagePlugin.error(e instanceof Error ? e.message : '配置校验未通过');
    return;
  }

  if (props.mode === 'publish') {
    const confirmed = await confirmDangerous({
      header: '发布新版本',
      body: `提交将由服务端递增发布号生成新版本快照（当前生效版本 v${baseVersion.value}）；`
        + '已运行会话不受影响，新会话按新版本装配。',
      theme: 'warning',
      confirmText: '发布',
    });
    if (!confirmed) {
      return;
    }
  }

  const agentId = props.agent?.id;
  if (props.mode === 'publish' && !agentId) {
    MessagePlugin.error('缺少智能体标识，无法发布新版本');
    return;
  }
  const saved = await submitTask.run(() =>
    (props.mode === 'publish' && agentId ? publishVersion(agentId, payload) : createAgent(payload)),
  );
  if (submitTask.error.value) {
    MessagePlugin.error(errorMessage(submitTask.error.value));
    return;
  }
  if (saved) {
    MessagePlugin.success(props.mode === 'publish' ? `已发布新版本 v${saved.version}` : `已创建智能体（v${saved.version}）`);
    emit('saved', saved);
    close();
  }
}

/** 字段错误文案（仅提交尝试后展示）。 */
function errorOf(field: Exclude<keyof AgentDraftErrors, 'customTools'>): string | undefined {
  return submitted.value ? errors.value[field] : undefined;
}

/** 字段状态。 */
function statusOf(field: Exclude<keyof AgentDraftErrors, 'customTools'>): 'error' | undefined {
  return errorOf(field) ? 'error' : undefined;
}
</script>

<template>
  <t-drawer
    :visible="visible"
    :header="mode === 'publish' ? `发布新版本（${agent?.name ?? ''}）` : '新建智能体'"
    size="860px"
    placement="right"
    :close-btn="true"
    :confirm-on-close="false"
    @close="close"
  >
    <t-alert
      theme="info"
      :message="versionNotice"
      class="agent-drawer__notice"
    />

    <div class="agent-drawer__mode">
      <t-radio-group :value="draft.mode" variant="default-filled" @change="onFormModeChange">
        <t-radio-button value="simple">渲染表单</t-radio-button>
        <t-radio-button value="raw">Raw JSON</t-radio-button>
      </t-radio-group>
      <span class="agent-drawer__mode-hint">Raw 模式直接编辑将提交的完整配置 JSON（已废止字段不可出现）</span>
    </div>

    <div v-if="draft.mode === 'raw'" class="agent-drawer__raw">
      <JsonEditor v-model="rawJson" :rows="22" validate placeholder='{"name":"…","model":"auto","system":"…"}' />
      <p class="agent-drawer__mode-hint">
        Raw 载荷字段：name / description / system / model（字符串或 {id,effort,context_window}）/ tools / skills / metadata。
      </p>
    </div>

    <t-form v-else layout="vertical" :data="draft" class="agent-form">
      <TwoColumnSection title="基础信息" description="定义智能体的用途、模型和运行方式。">
        <t-form-item label="名称" name="name" :status="statusOf('name')" :tips="errorOf('name')">
          <t-input v-model="draft.name" placeholder="如：数据分析助手" maxlength="256" clearable />
        </t-form-item>
        <t-form-item label="模型" name="modelId" :status="statusOf('modelId')" :tips="errorOf('modelId')">
          <div class="agent-form__model">
            <t-radio-group
              :value="draft.modelOrigin"
              variant="default-filled"
              size="small"
              @change="onOriginChange"
            >
              <t-radio-button value="profile">模型配置直选</t-radio-button>
              <t-radio-button value="catalog">模型目录档位</t-radio-button>
            </t-radio-group>
            <t-select
              v-if="draft.modelOrigin === 'profile'"
              v-model="draft.modelId"
              :options="modelOptions"
              :loading="modelsLoading"
              filterable
              placeholder="选择模型配置（启用中的对话模型）"
              @change="onModelChange"
            />
            <template v-else>
              <t-select
                v-model="draft.modelId"
                :options="catalogOptions"
                :loading="catalogLoading"
                filterable
                placeholder="选择模型档位（运行时经供应商映射解析）"
                @change="onCatalogModelChange"
              />
              <div v-if="effortOptions.length || contextWindowOptions.length" class="agent-form__model-tuning">
                <t-select
                  v-if="effortOptions.length"
                  :value="draft.effort || undefined"
                  :options="effortOptions"
                  clearable
                  placeholder="effort（缺省 = 档位默认）"
                  @change="onEffortSelect"
                />
                <t-select
                  v-if="contextWindowOptions.length"
                  :value="draft.contextWindow ?? undefined"
                  :options="contextWindowOptions"
                  clearable
                  placeholder="上下文窗口（缺省 = 档位默认）"
                  @change="onWindowSelect"
                />
              </div>
            </template>
          </div>
        </t-form-item>
        <t-form-item label="描述" name="description" :status="statusOf('description')" :tips="errorOf('description')">
          <t-textarea v-model="draft.description" placeholder="能力定位说明（可选）" :autosize="{ minRows: 2, maxRows: 4 }" />
        </t-form-item>
        <t-form-item label="系统提示词" name="system" :status="statusOf('system')" :tips="errorOf('system')">
          <t-textarea
            v-model="draft.system"
            placeholder="指令唯一载体（agents_md 已废止，此处即版本快照的 system）"
            :autosize="{ minRows: 8, maxRows: 24 }"
          />
          <template #help>
            <span class="agent-drawer__counter">{{ draft.system.length }} / 100000 字符</span>
          </template>
        </t-form-item>
      </TwoColumnSection>

      <TwoColumnSection title="技能绑定" description="仅可绑定本系统自定义技能，最多 20 个；可钉定具体版本或跟随最新版">
        <SkillBindingPicker v-model="draft.skillBindings" />
      </TwoColumnSection>

      <TwoColumnSection
        title="内置工具与权限策略"
        description="勾选写入 enabled_tools；一个都不勾 = 不提交白名单（平台全量基座）。策略为 always_ask 的调用在会话中触发人工审批"
      >
        <ToolPolicyEditor v-model:enabled-tools="draft.enabledTools" v-model:policies="draft.toolPolicies" />
      </TwoColumnSection>

      <TwoColumnSection title="自定义工具" description="名称不得与内置工具重名、不得含 __ 或 mcp__ 前缀；Schema 的 type 必须为 object">
        <CustomToolEditor v-model="draft.customTools" :errors="submitted ? errors.customTools : {}" />
      </TwoColumnSection>

      <TwoColumnSection title="元数据" description="业务自定义键值对象（JSON 文本，可选）">
        <JsonEditor v-model="draft.metadataJson" :rows="4" validate placeholder='{"team": "data"}' />
        <p v-if="errorOf('metadataJson')" class="agent-drawer__error">{{ errorOf('metadataJson') }}</p>
      </TwoColumnSection>
    </t-form>

    <template #footer>
      <div class="agent-drawer__footer">
        <t-button theme="default" variant="base" @click="close">取消</t-button>
        <t-button theme="primary" :loading="submitting" @click="onSubmit">{{ submitLabel }}</t-button>
      </div>
    </template>
  </t-drawer>
</template>

<style scoped>
.agent-drawer__notice {
  margin-bottom: 12px;
}

.agent-drawer__mode {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.agent-drawer__mode-hint {
  font-size: 12px;
  color: var(--td-text-color-secondary, rgba(0, 0, 0, 0.6));
}

.agent-drawer__raw {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

/* 模型双模式：来源切换 + 引用选择 + 调优行纵向堆叠 */
.agent-form__model {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}

.agent-form__model-tuning {
  display: flex;
  gap: 8px;
}

.agent-form__model-tuning > * {
  flex: 1;
}

/* 双栏分区表单：字段 label 上置（小号加粗），间距交由 TwoColumnSection 统一控制 */
.agent-form :deep(.t-form__label) {
  padding-bottom: 4px;
  font-size: 12px;
  font-weight: 600;
  color: var(--td-text-color-primary);
}

.agent-form :deep(.t-form__item) {
  margin-bottom: 0;
}

.agent-drawer__counter {
  font-size: 12px;
  color: var(--td-text-color-secondary, rgba(0, 0, 0, 0.6));
}

.agent-drawer__error {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--td-error-color, #d54941);
}

.agent-drawer__footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
