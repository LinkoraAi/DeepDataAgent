<template>
  <PageSection title="Agent 管理" description="创建 Agent（同事务生成 v1）、发布新版本、归档与删除。">
    <t-card :bordered="true">
      <div class="agent-toolbar">
        <t-input
          v-model="keyword"
          placeholder="按名称搜索"
          clearable
          class="agent-toolbar__search"
          @enter="search"
        />
        <t-button theme="primary" @click="search">搜索</t-button>
        <t-button theme="primary" variant="outline" @click="openCreate">创建 Agent</t-button>
      </div>

      <t-table
        :data="agents"
        :columns="columns"
        row-key="id"
        :loading="loading"
        :hover="true"
      >
        <template #archived_at="{ row }">
          <t-tag :theme="row.archived_at ? 'default' : 'success'" variant="light">
            {{ row.archived_at ? '已归档' : '运行中' }}
          </t-tag>
        </template>
        <template #version="{ row }">
          <span>{{ row.version > 0 ? `v${row.version}` : '未发布' }}</span>
        </template>
        <template #op="{ row }">
          <div class="op-cell">
            <t-button size="small" variant="text" :disabled="!!row.archived_at" @click="openPublish(row)">
              发布版本
            </t-button>
            <t-button size="small" variant="text" @click="openVersions(row)">版本列表</t-button>
            <t-popconfirm content="归档后不可创建新会话，确认归档？" @confirm="handleArchive(row)">
              <t-button size="small" variant="text" theme="warning" :disabled="!!row.archived_at">
                归档
              </t-button>
            </t-popconfirm>
            <t-popconfirm content="删除将级联清除全部版本，确认删除？" @confirm="handleDelete(row)">
              <t-button size="small" variant="text" theme="danger">删除</t-button>
            </t-popconfirm>
          </div>
        </template>
      </t-table>
      <div v-if="hasMore" class="agent-loadmore">
        <t-button variant="text" theme="primary" :loading="loading" @click="loadMore">加载更多</t-button>
      </div>
    </t-card>

    <!-- 创建 / 发布版本共用表单 -->
    <t-dialog
      v-model:visible="formVisible"
      :header="formMode === 'create' ? '创建 Agent' : `发布版本（${formTarget?.name ?? ''}）`"
      width="880px"
      :confirm-btn="{ content: formMode === 'create' ? '创建' : '发布', loading: submitting }"
      :cancel-btn="{}"
      @confirm="handleSubmit"
    >
      <t-alert
        v-if="formMode === 'publish'"
        theme="info"
        message="发布为全量替换：未填写的配置项将被清空并固化进新版本快照。"
        class="form-alert"
      />
      <t-form ref="formRef" :data="form" :rules="formRules" layout="vertical" label-align="left">
        <t-form-item label="名称" name="name">
          <t-input v-model="form.name" :maxlength="256" placeholder="Agent/版本名称（≤256 字符）" />
        </t-form-item>
        <t-form-item label="模型" name="modelId" :help="modelHelp">
          <div class="model-fields">
            <t-select
              v-model="form.modelId"
              :options="modelOptions"
              placeholder="选择模型目录中的模型"
              filterable
              @change="onModelChange"
            />
            <t-select
              v-model="form.modelEffort"
              :options="effortOptions"
              placeholder="effort（可选）"
              :disabled="!effortOptions.length"
              clearable
              class="model-fields__sub"
            />
            <t-select
              v-model="form.modelContextWindow"
              :options="windowOptions"
              placeholder="上下文窗口（可选）"
              :disabled="!windowOptions.length"
              clearable
              class="model-fields__sub"
            />
          </div>
        </t-form-item>
        <t-form-item label="描述" name="description">
          <t-textarea v-model="form.description" :maxlength="2048" :autosize="{ minRows: 2, maxRows: 4 }" placeholder="可选" />
        </t-form-item>
        <t-form-item label="系统提示词（system）" name="system">
          <t-textarea v-model="form.system" :autosize="{ minRows: 3, maxRows: 8 }" placeholder="岗位说明书：角色与行为约束（可选，≤100000 字符）" />
        </t-form-item>

        <t-form-item label="工具配方（tools，≤128 项）">
          <div class="recipe-block">
            <div v-for="(tool, index) in form.tools" :key="tool.key" class="recipe-card">
              <div class="recipe-card__head">
                <t-tag theme="primary" variant="light">{{ toolTypeLabel(tool.type) }}</t-tag>
                <t-button size="small" variant="text" theme="danger" @click="removeTool(index)">移除</t-button>
              </div>

              <!-- 内置工具集：白/黑名单 + 逐工具配置 -->
              <template v-if="tool.type === AGENT_TOOLSET_TYPE">
                <t-form-item label="enabled_tools（留空或全清 = 默认内置工具集，非清空）">
                  <t-select
                    v-model="tool.enabledTools"
                    :options="builtinOptions"
                    multiple
                    clearable
                    filterable
                    placeholder="留空 = 默认内置工具集；选择则为严格白名单"
                  />
                </t-form-item>
                <t-form-item label="disallowed_tools（隐藏 / 拒绝）">
                  <t-select
                    v-model="tool.disallowedTools"
                    :options="builtinOptions"
                    multiple
                    clearable
                    filterable
                    placeholder="与白名单互斥，不得同一工具同时出现"
                  />
                </t-form-item>
                <div class="config-block">
                  <div v-for="(config, configIndex) in tool.configs" :key="configIndex" class="config-row">
                    <t-select
                      v-model="config.name"
                      :options="builtinOptions"
                      placeholder="工具名"
                      class="config-row__name"
                    />
                    <t-select
                      v-model="config.permissionPolicy"
                      :options="policyOptions"
                      placeholder="permission_policy"
                      clearable
                      class="config-row__policy"
                    />
                    <t-select
                      v-model="config.enabled"
                      :options="enabledOptions"
                      placeholder="enabled"
                      clearable
                      class="config-row__enabled"
                    />
                    <t-button size="small" variant="text" theme="danger" @click="tool.configs.splice(configIndex, 1)">
                      删除
                    </t-button>
                  </div>
                  <t-button size="small" variant="text" theme="primary" @click="tool.configs.push(newToolConfig())">
                    + 逐工具配置
                  </t-button>
                </div>
              </template>

              <!-- MCP 工具集：引用已声明的 MCP 工具源 -->
              <template v-else-if="tool.type === MCP_TOOLSET_TYPE">
                <t-form-item label="mcp_server_name（必填，须匹配下方 MCP 工具源）">
                  <t-select
                    v-model="tool.mcpServerName"
                    :options="mcpServerOptions"
                    clearable
                    placeholder="选择 MCP 工具源名称"
                  />
                </t-form-item>
              </template>

              <!-- 自定义工具 -->
              <template v-else-if="tool.type === CUSTOM_TOOL_TYPE">
                <t-form-item label="工具名（不得与内置工具重名，不得为 advisor，不得以 mcp__ 开头）">
                  <t-input v-model="tool.name" placeholder="如 query_metrics" />
                </t-form-item>
                <t-form-item label="描述（必填）">
                  <t-input v-model="tool.description" placeholder="工具用途说明" />
                </t-form-item>
                <t-form-item label="input_schema（JSON Schema，type 须为 object）">
                  <t-textarea
                    v-model="tool.inputSchemaText"
                    :autosize="{ minRows: 3, maxRows: 8 }"
                    placeholder='如 {"type":"object","properties":{"sql":{"type":"string"}}}'
                  />
                </t-form-item>
              </template>
            </div>

            <div class="recipe-actions">
              <t-button size="small" variant="outline" @click="addTool(AGENT_TOOLSET_TYPE)">+ 内置工具集</t-button>
              <t-button size="small" variant="outline" @click="addTool(MCP_TOOLSET_TYPE)">+ MCP 工具集</t-button>
              <t-button size="small" variant="outline" @click="addTool(CUSTOM_TOOL_TYPE)">+ 自定义工具</t-button>
              <t-tooltip content="浏览器工具集（browser_toolset_20260714）本期不实现，提交即 400">
                <t-button size="small" variant="outline" disabled>浏览器工具集</t-button>
              </t-tooltip>
            </div>
          </div>
        </t-form-item>

        <t-form-item label="MCP 工具源（mcp_servers，≤20 项）">
          <div class="recipe-block">
            <div v-for="(server, index) in form.mcpServers" :key="server.key" class="config-row">
              <t-input v-model="server.name" placeholder="名称（供 mcp_toolset 引用）" class="config-row__name" />
              <t-input v-model="server.url" placeholder="https://…（仅支持 url 类型）" class="config-row__url" />
              <t-button size="small" variant="text" theme="danger" @click="form.mcpServers.splice(index, 1)">删除</t-button>
            </div>
            <t-button size="small" variant="text" theme="primary" @click="addMcpServer">+ MCP 工具源</t-button>
          </div>
        </t-form-item>

        <t-form-item label="挂载技能（skills，≤20 项）">
          <div class="recipe-block">
            <div v-for="(binding, index) in form.skills" :key="binding.key" class="config-row">
              <t-select
                v-model="binding.type"
                :options="skillTypeOptions"
                class="config-row__type"
              />
              <t-select
                v-model="binding.skillId"
                :options="skillOptions"
                placeholder="选择技能"
                filterable
                class="config-row__skill"
                @change="onBindingSkillChange(binding)"
              />
              <t-select
                v-model="binding.version"
                :options="versionOptionsOf(binding)"
                placeholder="版本"
                :disabled="!binding.skillId"
                class="config-row__version"
              />
              <t-button size="small" variant="text" theme="danger" @click="form.skills.splice(index, 1)">删除</t-button>
            </div>
            <p class="field-hint">版本留空为动态版（沙箱准备期解析当时最新版本）；选择具体版本则钉版，源技能发版不影响本绑定。</p>
            <t-button size="small" variant="text" theme="primary" @click="addSkillBinding">+ 技能绑定</t-button>
          </div>
        </t-form-item>

        <t-form-item label="业务元数据（metadata）" name="metadataJson">
          <t-textarea
            v-model="form.metadataJson"
            :autosize="{ minRows: 2, maxRows: 6 }"
            placeholder='键值对象 JSON，如 {"team":"data"}（可选）'
          />
        </t-form-item>
      </t-form>
    </t-dialog>

    <!-- 版本列表 -->
    <t-drawer v-model:visible="versionsVisible" size="560px" :header="`版本列表（${versionsAgentName}）`">
      <t-table
        :data="versions"
        :columns="versionColumns"
        row-key="version"
        max-height="60vh"
      >
        <template #model="{ row }">
          <span class="ellipsis">{{ formatModelRef(row.model) }}</span>
        </template>
        <template #system="{ row }">
          <span class="ellipsis">{{ row.system || '—' }}</span>
        </template>
      </t-table>
    </t-drawer>
  </PageSection>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import type { FormInstanceFunctions, FormRule, PrimaryTableCol } from 'tdesign-vue-next';
import PageSection from '@/shared/components/PageSection.vue';
import {
  AGENT_TOOLSET_TYPE,
  BROWSER_TOOLSET_TYPE,
  BUILTIN_TOOL_NAMES,
  CUSTOM_TOOL_TYPE,
  MCP_TOOLSET_TYPE,
  archiveAgent,
  createAgent,
  deleteAgent,
  getAgent,
  listAgentVersions,
  listAgents,
  publishAgentVersion,
  type AgentConfigPayload,
  type AgentDto,
  type AgentToolConfigRecipe,
  type AgentToolRecipe,
  type CloudModelRef,
  type McpServerRecipe,
  type ModelRef,
} from '../api/agents';
import {
  SKILL_BINDING_LATEST,
  SKILL_SOURCE_OPTIONS,
  listSkillVersions,
  listSkills,
  type SkillBinding,
  type SkillDto,
  type SkillVersionDto,
} from '../api/skills';
import { listCloudModels, type ModelCatalogDto, type ModelEffort } from '../api/cloud-models';

/** 工具配方表单行（按 type 取用相应槽位；key 为本地渲染键，不提交）。 */
interface ToolEntry {
  key: number;
  type: string;
  enabledTools: string[];
  disallowedTools: string[];
  configs: ToolConfigEntry[];
  mcpServerName: string;
  name: string;
  description: string;
  /** input_schema JSON 文本（custom 工具） */
  inputSchemaText: string;
}

/** 逐工具配置表单行。 */
interface ToolConfigEntry {
  name: string;
  /** null = 未设置（沿用默认启用） */
  enabled: boolean | null;
  /** 空串 = 未设置（平台默认策略） */
  permissionPolicy: string;
}

/** MCP 工具源表单行。 */
interface McpServerEntry {
  key: number;
  name: string;
  url: string;
}

/** 技能绑定表单行（version 空串 = 动态版）。 */
interface SkillBindingEntry {
  key: number;
  type: 'catalog' | 'custom';
  skillId: string;
  version: string;
}

/** 配置表单模型（结构化字段 + 模型引用编辑槽位；提交时装配为 AgentConfigPayload）。 */
interface AgentFormModel {
  name: string;
  description: string;
  /** 系统提示词（对外契约字段名 system） */
  system: string;
  /** 模型目录 id（字符串简写形态的选择值） */
  modelId: string;
  /** effort 档位（可选；与上下文窗口任一非空时按对象形态提交） */
  modelEffort: string;
  /** 上下文窗口（可选） */
  modelContextWindow: string | number;
  tools: ToolEntry[];
  mcpServers: McpServerEntry[];
  skills: SkillBindingEntry[];
  /** 元数据 JSON 文本（提交时解析为键值对象） */
  metadataJson: string;
}

/** 表格列定义。 */
const columns: PrimaryTableCol<AgentDto>[] = [
  { colKey: 'name', title: '名称', ellipsis: true },
  { colKey: 'version', title: '当前版本' },
  { colKey: 'archived_at', title: '状态' },
  { colKey: 'created_at', title: '创建时间' },
  { colKey: 'op', title: '操作', width: 280 },
];

const versionColumns: PrimaryTableCol<AgentDto>[] = [
  { colKey: 'version', title: '版本', width: 80 },
  { colKey: 'name', title: '名称' },
  { colKey: 'model', title: '模型' },
  { colKey: 'system', title: '系统提示词' },
  { colKey: 'created_at', title: '发布时间' },
];

/** 内置工具名下拉项（白 / 黑名单仅可引用这 11 个名字）。 */
const builtinOptions = BUILTIN_TOOL_NAMES.map((name) => ({ label: name, value: name }));

/** 权限策略下拉项。 */
const policyOptions = [
  { label: 'always_allow（恒允许）', value: 'always_allow' },
  { label: 'always_ask（每次询问，触发确认）', value: 'always_ask' },
  { label: 'always_deny（恒拒绝）', value: 'always_deny' },
];

/** 逐工具启用位下拉项（未选择 = 沿用默认启用）。 */
const enabledOptions = [
  { label: '启用', value: true },
  { label: '禁用（隐藏并拒绝调用）', value: false },
];

/** 技能来源下拉项。 */
const skillTypeOptions = SKILL_SOURCE_OPTIONS.map((item) => ({ label: item.label, value: item.value }));

/** 本地行键计数器（表单渲染用，不进入请求体）。 */
let entrySeq = 0;
function nextKey(): number {
  entrySeq += 1;
  return entrySeq;
}

const agents = ref<AgentDto[]>([]);
const loading = ref(false);
const keyword = ref('');
/** Cursor 分页状态（不提供 total）：hasMore 控制「加载更多」，lastId 为向后翻页游标。 */
const hasMore = ref(false);
const lastId = ref<string | null>(null);

const formVisible = ref(false);
const formMode = ref<'create' | 'publish'>('create');
const formTarget = ref<AgentDto | null>(null);
const formRef = ref<FormInstanceFunctions>();
const submitting = ref(false);
const models = ref<ModelCatalogDto[]>([]);
/** 技能目录（绑定下拉数据源）。 */
const skillCatalog = ref<SkillDto[]>([]);
/** 技能版本缓存（skill_id → 版本列表，按需加载）。 */
const skillVersions = ref<Record<string, SkillVersionDto[]>>({});

const versionsVisible = ref(false);
const versions = ref<AgentDto[]>([]);
const versionsAgentName = ref('');

const form = reactive<AgentFormModel>({
  name: '',
  description: '',
  system: '',
  modelId: '',
  modelEffort: '',
  modelContextWindow: '',
  tools: [],
  mcpServers: [],
  skills: [],
  metadataJson: '',
});

/** 当前选中的目录模型条目（决定 effort / 窗口可选项）。 */
const selectedModel = computed(
  () => models.value.find((item) => item.id === form.modelId) ?? null,
);

/** 模型下拉：仅启用条目，展示名 + 目录 id。 */
const modelOptions = computed(() =>
  models.value
    .filter((item) => item.isEnabled)
    .map((item) => ({ label: `${item.displayName}（${item.id}）`, value: item.id })),
);

const effortOptions = computed(() =>
  (selectedModel.value?.efforts ?? []).map((effort) => ({ label: `effort: ${effort}`, value: effort })),
);

const windowOptions = computed(() =>
  (selectedModel.value?.availableContextWindows ?? []).map((window) => ({
    label: `窗口: ${window}`,
    value: window,
  })),
);

/** 技能下拉：展示名 + 技能 ID。 */
const skillOptions = computed(() =>
  skillCatalog.value.map((item) => ({ label: `${item.display_title}（${item.id}）`, value: item.id })),
);

/** MCP 工具源名称下拉（供 mcp_toolset 引用）。 */
const mcpServerOptions = computed(() =>
  form.mcpServers
    .filter((server) => server.name.trim())
    .map((server) => ({ label: server.name.trim(), value: server.name.trim() })),
);

/** 模型字段常驻提示：展示目录条目的能力位与约束。 */
const modelHelp = computed(() => {
  const item = selectedModel.value;
  if (!item) {
    return '模型引用须存在于模型目录；effort / 上下文窗口可在所支持档位内可选细化（对象形态提交）。';
  }
  const bits = [
    item.isVl ? '视觉模型' : '文本模型',
    item.maxInputTokens != null ? `最大输入 ${item.maxInputTokens} tokens` : null,
    item.maxOutputTokens != null ? `最大输出 ${item.maxOutputTokens} tokens` : null,
  ].filter(Boolean);
  return `${item.displayName}：${bits.join(' / ') || '目录未声明窗口上限'}`;
});

/** 工具类型中文标签（含 browser 出界标注）。 */
function toolTypeLabel(type: string): string {
  switch (type) {
    case AGENT_TOOLSET_TYPE:
      return '内置工具集';
    case MCP_TOOLSET_TYPE:
      return 'MCP 工具集';
    case CUSTOM_TOOL_TYPE:
      return '自定义工具';
    case BROWSER_TOOLSET_TYPE:
      return '浏览器工具集（本期不支持）';
    default:
      return type;
  }
}

/** 切换模型后清掉新模型不支持的 effort / 窗口选择。 */
function onModelChange(): void {
  const item = selectedModel.value;
  if (form.modelEffort && !(item?.efforts ?? []).includes(form.modelEffort)) {
    form.modelEffort = '';
  }
  const windows = item?.availableContextWindows ?? [];
  if (form.modelContextWindow !== '' && !windows.includes(Number(form.modelContextWindow))) {
    form.modelContextWindow = '';
  }
}

/** JSON 对象校验（metadata：非空时须为合法键值对象）。 */
function validateJsonObject(value: unknown): { result: boolean; message: string } {
  const text = String(value ?? '').trim();
  if (!text) {
    return { result: true, message: '' };
  }
  try {
    const parsed = JSON.parse(text) as unknown;
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
      return { result: false, message: '必须是 JSON 对象' };
    }
    return { result: true, message: '' };
  } catch {
    return { result: false, message: '不是合法的 JSON' };
  }
}

const formRules: Record<string, FormRule[]> = {
  name: [{ required: true, message: '名称不能为空' }],
  modelId: [{ required: true, message: '请选择模型' }],
  metadataJson: [{ validator: validateJsonObject, trigger: 'blur' }],
};

/** 新建逐工具配置行。 */
function newToolConfig(): ToolConfigEntry {
  return { name: '', enabled: null, permissionPolicy: '' };
}

/** 新建工具配方行（按类型初始化槽位）。 */
function addTool(type: string): void {
  form.tools.push({
    key: nextKey(),
    type,
    enabledTools: [],
    disallowedTools: [],
    configs: [],
    mcpServerName: '',
    name: '',
    description: '',
    inputSchemaText: '',
  });
}

function removeTool(index: number): void {
  form.tools.splice(index, 1);
}

function addMcpServer(): void {
  form.mcpServers.push({ key: nextKey(), name: '', url: '' });
}

function addSkillBinding(): void {
  form.skills.push({ key: nextKey(), type: 'custom', skillId: '', version: '' });
}

/** 切换绑定的技能后清空版本选择，并预载该技能版本列表。 */
function onBindingSkillChange(binding: SkillBindingEntry): void {
  binding.version = '';
  if (binding.skillId) {
    void ensureSkillVersions(binding.skillId);
  }
}

/** 按需加载技能版本（同技能只拉一次）。 */
async function ensureSkillVersions(skillId: string): Promise<void> {
  if (skillVersions.value[skillId]) {
    return;
  }
  try {
    const page = await listSkillVersions(skillId, { limit: 100 });
    skillVersions.value = { ...skillVersions.value, [skillId]: page.data };
  } catch (e) {
    MessagePlugin.warning(`技能版本加载失败: ${(e as Error).message}`);
  }
}

/** 版本下拉项：动态版 + 已加载版本（钉版值不在列表中时补入，避免回填丢失）。 */
function versionOptionsOf(binding: SkillBindingEntry): { label: string; value: string }[] {
  const options = [{ label: '动态版（latest）', value: '' }];
  if (!binding.skillId) {
    return options;
  }
  const versions = skillVersions.value[binding.skillId] ?? [];
  for (const version of versions) {
    options.push({ label: formatVersion(version.version), value: version.version });
  }
  const pinned = binding.version;
  if (pinned && !versions.some((version) => version.version === pinned)) {
    options.push({ label: formatVersion(pinned), value: pinned });
  }
  return options;
}

/** 版本键展示：epoch 微秒 → 本地时间 + 原值。 */
function formatVersion(version: string): string {
  const micros = Number(version);
  if (!Number.isFinite(micros) || micros <= 0) {
    return version;
  }
  return `${new Date(micros / 1000).toLocaleString()} · ${version}`;
}

/** 版本快照模型引用展示：字符串简写原样，对象形态拼展示档位。 */
function formatModelRef(model: ModelRef): string {
  if (typeof model === 'string') {
    return model;
  }
  const bits = [model.effort ? `effort=${model.effort}` : null, model.context_window ? `窗口=${model.context_window}` : null]
    .filter(Boolean)
    .join('，');
  return bits ? `${model.id}（${bits}）` : model.id;
}

/** 装配模型引用：effort / 窗口任一非空 → 对象形态，否则字符串简写。 */
function buildModelRef(): ModelRef {
  if (!form.modelEffort && form.modelContextWindow === '') {
    return form.modelId;
  }
  const ref: CloudModelRef = { id: form.modelId };
  if (form.modelEffort) {
    ref.effort = form.modelEffort as ModelEffort;
  }
  if (form.modelContextWindow !== '') {
    ref.context_window = Number(form.modelContextWindow);
  }
  return ref;
}

/** 解析 custom 工具的 input_schema 文本（须为 type=object 的 JSON 对象，否则 null）。 */
function parseInputSchema(text: string): Record<string, unknown> | null {
  const trimmed = text.trim();
  if (!trimmed) {
    return null;
  }
  try {
    const parsed = JSON.parse(trimmed) as unknown;
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
      return null;
    }
    const schema = parsed as Record<string, unknown>;
    return schema.type === 'object' ? schema : null;
  } catch {
    return null;
  }
}

/** 解析表单 JSON 文本为键值对象（空 = null；形态已由 formRules 校验）。 */
function parseJsonObject(text: string): Record<string, unknown> | null {
  const trimmed = text?.trim();
  return trimmed ? (JSON.parse(trimmed) as Record<string, unknown>) : null;
}

/** 提交前工具配方校验（返回错误消息，null 表示通过）。 */
function validateTools(): string | null {
  for (const tool of form.tools) {
    if (tool.type === AGENT_TOOLSET_TYPE) {
      const overlap = tool.enabledTools.filter((name) => tool.disallowedTools.includes(name));
      if (overlap.length > 0) {
        return `enabled_tools 与 disallowed_tools 不得同时包含：${overlap.join('、')}`;
      }
      if (tool.configs.some((config) => !config.name)) {
        return '逐工具配置必须选择工具名';
      }
    } else if (tool.type === MCP_TOOLSET_TYPE) {
      if (!tool.mcpServerName) {
        return 'MCP 工具集必须选择 mcp_server_name';
      }
      if (!form.mcpServers.some((server) => server.name.trim() === tool.mcpServerName)) {
        return `MCP 工具集引用的「${tool.mcpServerName}」不在 MCP 工具源列表中`;
      }
    } else if (tool.type === CUSTOM_TOOL_TYPE) {
      if (!tool.name.trim()) {
        return '自定义工具名不能为空';
      }
      if (!tool.description.trim()) {
        return '自定义工具描述不能为空';
      }
      if (!parseInputSchema(tool.inputSchemaText)) {
        return `自定义工具「${tool.name.trim()}」的 input_schema 须为 type 为 object 的 JSON 对象`;
      }
    }
  }
  if (form.tools.length > 128) {
    return '工具配方不能超过 128 项';
  }
  return null;
}

/** 提交前 MCP 工具源校验（返回错误消息，null 表示通过）。 */
function validateMcpServers(): string | null {
  if (form.mcpServers.length > 20) {
    return 'MCP 工具源不能超过 20 项';
  }
  const names: string[] = [];
  for (const server of form.mcpServers) {
    const name = server.name.trim();
    if (!name || !server.url.trim()) {
      return 'MCP 工具源的名称与 URL 均为必填';
    }
    if (names.includes(name)) {
      return `MCP 工具源名称重复：${name}`;
    }
    names.push(name);
  }
  return null;
}

/** 提交前技能绑定校验（返回错误消息，null 表示通过）。 */
function validateSkills(): string | null {
  if (form.skills.length > 20) {
    return '技能绑定不能超过 20 项';
  }
  if (form.skills.some((binding) => !binding.skillId)) {
    return '技能绑定必须选择技能';
  }
  return null;
}

/** 装配工具配方（按类型仅提交相关键；空容器省略）。 */
function buildTools(): AgentToolRecipe[] {
  return form.tools.map((tool) => {
    if (tool.type === AGENT_TOOLSET_TYPE) {
      const recipe: AgentToolRecipe = { type: tool.type };
      if (tool.enabledTools.length > 0) {
        recipe.enabled_tools = [...tool.enabledTools];
      }
      if (tool.disallowedTools.length > 0) {
        recipe.disallowed_tools = [...tool.disallowedTools];
      }
      const configs: AgentToolConfigRecipe[] = tool.configs
        .filter((config) => config.name)
        .map((config) => {
          const item: AgentToolConfigRecipe = { name: config.name };
          if (config.enabled !== null) {
            item.enabled = config.enabled;
          }
          if (config.permissionPolicy) {
            item.permission_policy = config.permissionPolicy;
          }
          return item;
        });
      if (configs.length > 0) {
        recipe.configs = configs;
      }
      return recipe;
    }
    if (tool.type === MCP_TOOLSET_TYPE) {
      return { type: tool.type, mcp_server_name: tool.mcpServerName };
    }
    return {
      type: tool.type,
      name: tool.name.trim(),
      description: tool.description.trim(),
      input_schema: parseInputSchema(tool.inputSchemaText) ?? undefined,
    };
  });
}

/** 装配 MCP 工具源（类型恒 url）。 */
function buildMcpServers(): McpServerRecipe[] {
  return form.mcpServers.map((server) => ({
    name: server.name.trim(),
    type: 'url',
    url: server.url.trim(),
  }));
}

/** 装配技能绑定（动态版省略 version；explicit "latest" 与省略等价）。 */
function buildSkills(): SkillBinding[] {
  return form.skills.map((binding) => {
    const item: SkillBinding = { type: binding.type, skill_id: binding.skillId };
    if (binding.version && binding.version !== SKILL_BINDING_LATEST) {
      item.version = binding.version;
    }
    return item;
  });
}

/** 加载 Agent 列表（Cursor 分页：append = 向后翻页追加，否则重载首页）。 */
async function reload(append = false): Promise<void> {
  loading.value = true;
  try {
    const page = await listAgents({
      keyword: keyword.value || undefined,
      limit: 20,
      afterId: append ? lastId.value ?? undefined : undefined,
    });
    agents.value = append ? [...agents.value, ...page.data] : page.data;
    hasMore.value = page.has_more;
    lastId.value = page.last_id;
  } finally {
    loading.value = false;
  }
}

/** 搜索 / 操作后回到首页重新加载。 */
function search(): void {
  void reload();
}

/** 加载更多（向后翻页）。 */
function loadMore(): void {
  void reload(true);
}

/** 加载云模型目录（表单模型下拉数据源）。 */
async function loadModels(): Promise<void> {
  models.value = await listCloudModels();
}

/** 加载技能目录（表单绑定下拉数据源）。 */
async function loadSkillCatalog(): Promise<void> {
  const page = await listSkills({ limit: 100 });
  skillCatalog.value = page.data;
}

function resetForm(): void {
  form.name = '';
  form.description = '';
  form.system = '';
  form.modelId = '';
  form.modelEffort = '';
  form.modelContextWindow = '';
  form.tools = [];
  form.mcpServers = [];
  form.skills = [];
  form.metadataJson = '';
}

function openCreate(): void {
  formMode.value = 'create';
  formTarget.value = null;
  resetForm();
  formVisible.value = true;
}

/** 拆解版本快照模型引用到表单编辑槽位（字符串简写与对象形态等价）。 */
function fillModelRef(model: ModelRef): void {
  if (typeof model === 'string') {
    form.modelId = model;
    form.modelEffort = '';
    form.modelContextWindow = '';
    return;
  }
  form.modelId = model.id;
  form.modelEffort = model.effort ?? '';
  form.modelContextWindow = model.context_window ?? '';
}

/** 版本快照工具配方 → 表单行（browser 类型必然不可发布，直接过滤）。 */
function fillTools(recipes: AgentToolRecipe[]): void {
  form.tools = (recipes ?? [])
    .filter((recipe) => recipe.type !== BROWSER_TOOLSET_TYPE)
    .map((recipe) => ({
      key: nextKey(),
      type: recipe.type,
      enabledTools: [...(recipe.enabled_tools ?? [])],
      disallowedTools: [...(recipe.disallowed_tools ?? [])],
      configs: (recipe.configs ?? []).map((config) => ({
        name: config.name,
        enabled: config.enabled ?? null,
        permissionPolicy: config.permission_policy ?? '',
      })),
      mcpServerName: recipe.mcp_server_name ?? '',
      name: recipe.name ?? '',
      description: recipe.description ?? '',
      inputSchemaText: recipe.input_schema && Object.keys(recipe.input_schema).length > 0
        ? JSON.stringify(recipe.input_schema, null, 2)
        : '',
    }));
}

/** 版本快照 MCP 工具源 → 表单行。 */
function fillMcpServers(servers: McpServerRecipe[]): void {
  form.mcpServers = (servers ?? []).map((server) => ({
    key: nextKey(),
    name: server.name ?? '',
    url: server.url ?? '',
  }));
}

/** 版本快照技能绑定 → 表单行（"latest" 归一为空串 = 动态版）。 */
function fillSkills(bindings: SkillBinding[]): void {
  form.skills = (bindings ?? []).map((binding) => ({
    key: nextKey(),
    type: binding.type === 'catalog' ? 'catalog' : 'custom',
    skillId: binding.skill_id ?? '',
    version: binding.version && binding.version !== SKILL_BINDING_LATEST ? binding.version : '',
  }));
}

/**
 * 打开发布表单：以当前生效版本配置预填全部配置项。
 * <p>发布为全量替换语义，预填可避免误清空既有配置，也让修改以
 * 「在上一版本基础上编辑」的方式进入新版本。</p>
 * <p>先取详情再开窗：预填失败时保持对话框关闭，避免在空表单上提交而清空既有配置。</p>
 */
async function openPublish(agent: AgentDto): Promise<void> {
  let detail: AgentDto;
  try {
    detail = await getAgent(agent.id);
  } catch (e) {
    MessagePlugin.error(`Agent 详情加载失败，已取消发布: ${(e as Error).message}`);
    return;
  }
  formMode.value = 'publish';
  formTarget.value = agent;
  resetForm();
  formVisible.value = true;
  form.name = `v${agent.version + 1}`;
  fillModelRef(detail.model);
  form.description = detail.description ?? '';
  form.system = detail.system ?? '';
  fillTools(detail.tools);
  fillMcpServers(detail.mcp_servers);
  fillSkills(detail.skills);
  form.metadataJson = Object.keys(detail.metadata ?? {}).length > 0 ? JSON.stringify(detail.metadata) : '';
  // 钉版绑定的版本需在版本下拉中可见：按绑定技能预载版本列表
  const pinnedSkillIds = form.skills.filter((binding) => binding.skillId).map((binding) => binding.skillId);
  await Promise.all([...new Set(pinnedSkillIds)].map((skillId) => ensureSkillVersions(skillId)));
}

async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate();
  if (valid !== true) {
    return;
  }
  const toolError = validateTools() ?? validateMcpServers() ?? validateSkills();
  if (toolError) {
    MessagePlugin.warning(toolError);
    return;
  }
  submitting.value = true;
  try {
    const payload: AgentConfigPayload = {
      name: form.name,
      model: buildModelRef(),
      description: form.description?.trim() || null,
      system: form.system?.trim() || undefined,
      tools: buildTools(),
      mcp_servers: buildMcpServers(),
      skills: buildSkills(),
      metadata: parseJsonObject(form.metadataJson),
    };
    if (formMode.value === 'create') {
      await createAgent(payload);
    } else if (formTarget.value) {
      await publishAgentVersion(formTarget.value.id, payload);
    }
    formVisible.value = false;
    await reload();
  } catch (e) {
    // 提交失败保持对话框打开，便于用户在原文上修正后重试
    MessagePlugin.error(`${formMode.value === 'create' ? '创建' : '发布'}失败: ${(e as Error).message}`);
  } finally {
    submitting.value = false;
  }
}

async function handleArchive(agent: AgentDto): Promise<void> {
  try {
    await archiveAgent(agent.id);
  } catch (e) {
    MessagePlugin.error(`归档失败: ${(e as Error).message}`);
    return;
  }
  await reload();
}

async function handleDelete(agent: AgentDto): Promise<void> {
  try {
    await deleteAgent(agent.id);
  } catch (e) {
    MessagePlugin.error(`删除失败: ${(e as Error).message}`);
    return;
  }
  await reload();
}

async function openVersions(agent: AgentDto): Promise<void> {
  versionsAgentName.value = agent.name;
  const page = await listAgentVersions(agent.id, { limit: 50 });
  versions.value = page.data;
  versionsVisible.value = true;
}

onMounted(async () => {
  await Promise.all([reload(), loadModels(), loadSkillCatalog()]);
});
</script>

<style scoped>
.agent-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
}

.agent-toolbar__search {
  width: 240px;
}

.agent-loadmore {
  display: flex;
  justify-content: center;
  margin-top: 12px;
}

.form-alert {
  margin-bottom: 16px;
}

.op-cell {
  display: flex;
  align-items: center;
  gap: 4px;
}

.model-fields {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.model-fields__sub {
  width: 160px;
}

.recipe-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}

.recipe-card {
  border: 1px solid var(--td-component-border);
  border-radius: var(--td-radius-default);
  padding: 12px;
  background: var(--td-bg-color-container);
}

.recipe-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.recipe-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.config-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.config-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.config-row__name {
  width: 200px;
}

.config-row__policy {
  width: 260px;
}

.config-row__enabled {
  width: 180px;
}

.config-row__url {
  flex: 1;
}

.config-row__type {
  width: 140px;
}

.config-row__skill {
  width: 260px;
}

.config-row__version {
  flex: 1;
}

.field-hint {
  margin: 0;
  font-size: 12px;
  color: var(--td-text-color-secondary);
}

.ellipsis {
  display: block;
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>