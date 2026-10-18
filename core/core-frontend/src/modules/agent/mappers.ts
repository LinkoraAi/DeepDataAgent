import { BUILTIN_TOOLS } from '@/shared/enums/dict';
import type {
  AgentConfigPayload,
  AgentCustomToolWire,
  AgentSkillBindingWire,
  AgentToolConfigWire,
  AgentToolWire,
  AgentToolsetItemWire,
  AgentWire,
  ModelRefObjectWire,
  ModelRefWire,
} from './api/agents';

/**
 * 智能体配置草稿（表单侧扁平结构）⇄ 后端 AgentConfigRequest / AgentResponse 的纯装配层。
 * <p>本文件全部为无 Vue 依赖的纯函数，便于单测；提交载荷的字段集严格等于后端
 * AgentConfigRequest 的对外提交形态，已废止字段（agents_md / mcp_servers / multiagent /
 * model.speed）在本层<b>零输出</b>（既不由渲染模式产出，也不允许从 Raw JSON 透传）。</p>
 */

/** 表单模式：simple=渲染表单；raw=整份配置 JSON 直接编辑。 */
export type AgentDraftMode = 'simple' | 'raw';

/**
 * 模型来源：profile=模型配置直选（modelId 为内部 model_profile_id）；
 * catalog=模型目录档位（modelId 为目录档位 id，可带 effort/上下文窗口调优）。
 * 仅影响界面呈现与软校验口径，载荷装配两形态共用（字符串/对象由调优字段决定）。
 */
export type ModelOrigin = 'profile' | 'catalog';

/** 回显来源判定候选（两名单可得时集合匹配初始化 origin）。 */
export interface ModelOriginCandidates {
  /** 本租户可选模型配置 id 名单 */
  profileIds: readonly string[];
  /** 模型目录档位 id 名单 */
  catalogIds: readonly string[];
}

/**
 * 判定模型引用的表单来源（纯函数）：
 * 命中目录档位名单 → catalog；命中模型配置名单或皆不命中（含空值）→ profile
 * （皆不命中时界面原样展示该值，允许用户重选，不报错）。
 *
 * @param modelId    模型引用 id（trim 前原值）
 * @param profileIds 本租户可选模型配置 id 名单
 * @param catalogIds 模型目录档位 id 名单
 * @returns 表单来源
 */
export function inferModelOrigin(
  modelId: string,
  profileIds: readonly string[],
  catalogIds: readonly string[],
): ModelOrigin {
  const id = modelId.trim();
  if (id && catalogIds.includes(id)) {
    return 'catalog';
  }
  return 'profile';
}

/** 内置工具集条目 type 词汇（后端 AgentTool.TYPE_AGENT_TOOLSET）。 */
export const AGENT_TOOLSET_TYPE = 'agent_toolset_20260401';

/** 自定义工具条目 type 词汇（后端 AgentTool.TYPE_CUSTOM）。 */
export const CUSTOM_TOOL_TYPE = 'custom';

/** 技能绑定 type 词汇（管理端仅绑定本系统自建技能）。 */
export const CUSTOM_SKILL_TYPE = 'custom';

/** 名称长度上限（后端 @Size(max=256)）。 */
export const NAME_MAX_LENGTH = 256;

/** 描述长度上限（后端 @Size(max=2048)）。 */
export const DESCRIPTION_MAX_LENGTH = 2048;

/** 系统提示词长度上限（后端 @Size(max=100000)）。 */
export const SYSTEM_MAX_LENGTH = 100000;

/** 技能绑定数量上限（后端 SkillBinding.MAX_BINDINGS）。 */
export const MAX_SKILL_BINDINGS = 20;

/** 工具条目数量上限（后端 AgentTool.MAX_TOOLS；本表单最多 1 个内置工具集条目 + N 个 custom）。 */
export const MAX_TOOL_ENTRIES = 128;

/** 自定义工具保留名（后端 AgentTool.RESERVED_TOOL_NAME_ADVISOR，忽略大小写）。 */
export const RESERVED_CUSTOM_TOOL_NAME = 'advisor';

/** MCP 桥接保留前缀（后端 AgentTool.MCP_TOOL_PREFIX）。 */
export const MCP_TOOL_PREFIX = 'mcp__';

/** 运行时工具实名分隔符（后端 AgentTool.NAME_SEPARATOR）。 */
export const TOOL_NAME_SEPARATOR = '__';

/** 动态版版本词汇（后端 SkillBinding.VERSION_LATEST；等价于省略 version）。 */
export const SKILL_VERSION_LATEST = 'latest';

/** 内置工具名集合（custom 重名校验用）。 */
const BUILTIN_TOOL_NAME_SET = new Set<string>(BUILTIN_TOOLS);

/** 自定义工具草稿条目。 */
export interface CustomToolDraft {
  /** 工具名 */
  name: string;
  /** 工具描述 */
  description: string;
  /** 输入 JSON Schema 文本（须为 type=object 的 JSON 对象） */
  inputSchemaJson: string;
}

/** 技能绑定草稿条目。 */
export interface SkillBindingDraft {
  /** 技能业务 ID（skill_ 前缀） */
  skillId: string;
  /** 版本键；空串或 "latest" = 动态版（不落 version 键），其余为钉版 epoch 字符串 */
  version: string;
  /** 展示名（仅界面回显用，不进载荷；来自技能列表/详情，缺省回落 skillId） */
  displayTitle: string;
}

/**
 * 智能体配置表单草稿（渲染模式各字段扁平绑定；Raw 模式的 JSON 文本由 draftToRawJson/rawJsonToDraft 往返）。
 */
export interface AgentDraft {
  /** 表单模式（仅影响界面呈现，装配一律以结构化字段为准） */
  mode: AgentDraftMode;
  /** 名称（1-256，必填） */
  name: string;
  /** 描述（≤2048） */
  description: string;
  /** 系统提示词（≤100000） */
  system: string;
  /** 模型引用 id：直选态 = model_profile_id，目录态 = 目录档位 id（必填） */
  modelId: string;
  /** 模型来源（仅影响界面呈现与软校验，两态载荷装配共用） */
  modelOrigin: ModelOrigin;
  /** effort 档位（空串 = 目录缺省，不落对象形态的 effort 键；直选态不适用） */
  effort: string;
  /** 上下文窗口档位（null = 目录缺省） */
  contextWindow: number | null;
  /** 已勾选的内置工具（写入 enabled_tools 白名单；空数组 = 使用平台默认全量基座） */
  enabledTools: string[];
  /** 内置工具权限策略（工具名 → always_allow|always_ask|always_deny；未配置的工具不落 configs） */
  toolPolicies: Record<string, string>;
  /** 自定义工具列表 */
  customTools: CustomToolDraft[];
  /** 技能绑定列表（≤20） */
  skillBindings: SkillBindingDraft[];
  /** 业务元数据 JSON 文本（空白 = 不提交；须为合法 JSON 对象） */
  metadataJson: string;
}

/** 自定义工具条目的字段级错误。 */
export interface CustomToolErrors {
  /** 名称错误 */
  name?: string;
  /** 描述错误 */
  description?: string;
  /** 输入 Schema 错误 */
  inputSchema?: string;
}

/** 草稿校验错误集合（空对象表示通过）。 */
export interface AgentDraftErrors {
  /** 名称 */
  name?: string;
  /** 描述 */
  description?: string;
  /** 系统提示词 */
  system?: string;
  /** 模型引用 */
  modelId?: string;
  /** 元数据 JSON */
  metadataJson?: string;
  /** 技能绑定整体（数量上限） */
  skillBindings?: string;
  /** 工具条目总数上限 */
  tools?: string;
  /** 自定义工具字段错误（数组下标 → 错误） */
  customTools: Record<number, CustomToolErrors>;
}

/** 新建态空草稿。 */
export function createEmptyAgentDraft(): AgentDraft {
  return {
    mode: 'simple',
    name: '',
    description: '',
    system: '',
    modelId: '',
    modelOrigin: 'profile',
    effort: '',
    contextWindow: null,
    enabledTools: [],
    toolPolicies: {},
    customTools: [],
    skillBindings: [],
    metadataJson: '',
  };
}

/** 空白归一：trim、丢弃空白项、按原顺序去重。 */
function normalizeStrings(entries: readonly string[] | undefined): string[] {
  const out: string[] = [];
  for (const entry of entries ?? []) {
    const text = String(entry ?? '').trim();
    if (text && !out.includes(text)) {
      out.push(text);
    }
  }
  return out;
}

/** 解析元数据 JSON 文本；空白视为未配置（返回 null），非法或非标量对象抛错。 */
function parseJsonObject(text: string, fieldLabel: string): Record<string, unknown> | null {
  const trimmed = text.trim();
  if (!trimmed) {
    return null;
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(trimmed);
  } catch (e) {
    throw new Error(`${fieldLabel}不是合法 JSON：${e instanceof Error ? e.message : '解析失败'}`);
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    throw new Error(`${fieldLabel}必须是 JSON 键值对象`);
  }
  return parsed as Record<string, unknown>;
}

/** 自定义工具名称校验（口径对齐后端 AgentTool 紧凑构造器）。 */
export function validateCustomToolName(name: string): string {
  const trimmed = name.trim();
  if (!trimmed) {
    return '请填写自定义工具名称';
  }
  if (BUILTIN_TOOL_NAME_SET.has(trimmed)) {
    return `自定义工具不得与内置工具重名：${trimmed}`;
  }
  if (trimmed.toLowerCase() === RESERVED_CUSTOM_TOOL_NAME) {
    return '自定义工具不得占用保留名称 advisor';
  }
  if (trimmed.startsWith(MCP_TOOL_PREFIX)) {
    return '自定义工具名称不得以 mcp__ 开头（MCP 桥接保留前缀）';
  }
  if (trimmed.includes(TOOL_NAME_SEPARATOR)) {
    return '自定义工具名称不得包含保留分隔符 __';
  }
  return '';
}

/** 自定义工具输入 Schema 校验：合法 JSON 对象且 type 须为 object。 */
export function validateCustomToolSchema(text: string): string {
  if (!text.trim()) {
    return '请填写自定义工具的输入 JSON Schema';
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch (e) {
    return `输入 Schema 不是合法 JSON：${e instanceof Error ? e.message : '解析失败'}`;
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    return '输入 Schema 必须是 JSON 对象';
  }
  const type = (parsed as Record<string, unknown>).type;
  if (type !== 'object') {
    return "输入 Schema 的 type 必须为 'object'";
  }
  return '';
}

/** 单个自定义工具条目校验。 */
export function validateCustomTool(tool: CustomToolDraft): CustomToolErrors {
  const errors: CustomToolErrors = {};
  const nameError = validateCustomToolName(tool.name);
  if (nameError) {
    errors.name = nameError;
  }
  if (!tool.description.trim()) {
    errors.description = '请填写自定义工具描述';
  }
  const schemaError = validateCustomToolSchema(tool.inputSchemaJson);
  if (schemaError) {
    errors.inputSchema = schemaError;
  }
  return errors;
}

/** 是否无任何错误（自定义工具错误集合为空视为通过）。 */
export function hasDraftErrors(errors: AgentDraftErrors): boolean {
  if (Object.keys(errors.customTools).length > 0) {
    return true;
  }
  return [errors.name, errors.description, errors.system, errors.modelId, errors.metadataJson,
    errors.skillBindings, errors.tools].some((message) => Boolean(message));
}

/**
 * 草稿校验（纯函数，覆盖后端 AgentConfigRequest / AgentTool / SkillBinding 不变量）。
 * <p>元数据 Schema 非法等"抛错型"校验在本函数内转为字段错误，保证界面可提示。</p>
 * <p>模型字段按来源区分口径：直选态必填；目录态必填且（传入目录档位名单时）
 * 须在清单内——前端软校验提升体验，后端目录校验为最终权威。</p>
 *
 * @param draft          待校验草稿
 * @param catalogIds     目录档位 id 名单（可空 = 不做档位存在性软校验）
 */
export function validateDraft(draft: AgentDraft, catalogIds?: readonly string[]): AgentDraftErrors {
  const errors: AgentDraftErrors = { customTools: {} };
  const name = draft.name.trim();
  if (!name) {
    errors.name = '请输入智能体名称';
  } else if (name.length > NAME_MAX_LENGTH) {
    errors.name = `名称不能超过${NAME_MAX_LENGTH}个字符`;
  }
  if (draft.description.trim().length > DESCRIPTION_MAX_LENGTH) {
    errors.description = `描述不能超过${DESCRIPTION_MAX_LENGTH}个字符`;
  }
  if (draft.system.length > SYSTEM_MAX_LENGTH) {
    errors.system = `系统提示词不能超过${SYSTEM_MAX_LENGTH}个字符`;
  }
  const modelId = draft.modelId.trim();
  if (!modelId) {
    errors.modelId = draft.modelOrigin === 'catalog' ? '请选择模型档位' : '请选择模型配置';
  } else if (draft.modelOrigin === 'catalog' && catalogIds && !catalogIds.includes(modelId)) {
    errors.modelId = `档位 ${modelId} 不在模型目录清单（目录未加载或档位已禁用）`;
  }
  if (draft.skillBindings.length > MAX_SKILL_BINDINGS) {
    errors.skillBindings = `技能绑定数量不能超过${MAX_SKILL_BINDINGS}个`;
  }
  const normalizedTools = normalizeStrings(draft.enabledTools);
  if (normalizedTools.length + draft.customTools.length > MAX_TOOL_ENTRIES) {
    errors.tools = `工具条目数量不能超过${MAX_TOOL_ENTRIES}个`;
  }
  // enabled_tools 仅可引用内置白名单名（后端越界即 400，此处前置拦截，Raw 模式同样受检）
  const outside = normalizedTools.filter((tool) => !BUILTIN_TOOL_NAME_SET.has(tool));
  if (outside.length) {
    errors.tools = errors.tools ?? `enabled_tools 仅可引用内置工具名，非法：${outside.join('、')}`;
  }
  draft.customTools.forEach((tool, index) => {
    const toolErrors = validateCustomTool(tool);
    if (Object.keys(toolErrors).length > 0) {
      errors.customTools[index] = toolErrors;
    }
  });
  try {
    parseJsonObject(draft.metadataJson, '元数据');
  } catch (e) {
    errors.metadataJson = e instanceof Error ? e.message : '元数据格式非法';
  }
  return errors;
}

/** 模型引用装配：调优字段全空 → 字符串简写；否则对象形态（MUST NOT 出现 speed）。 */
function buildModelRef(draft: AgentDraft): ModelRefWire {
  const id = draft.modelId.trim();
  const effort = draft.effort.trim();
  const contextWindow = draft.contextWindow;
  if (!effort && contextWindow === null) {
    return id;
  }
  const model: ModelRefObjectWire = { id };
  if (effort) {
    model.effort = effort;
  }
  if (contextWindow !== null) {
    model.context_window = contextWindow;
  }
  return model;
}

/**
 * 内置工具集条目装配（无勾选且无策略时返回 null，表示不提交内置工具集条目）。
 * <p>enabled_tools 口径：仅当有勾选时落白名单键（空/缺省 = 平台全量基座暴露）；
 * configs 按内置工具声明序落 {name, permission_policy}，未勾选且从未出现在草稿的工具不落条目。</p>
 */
function buildToolsetEntry(draft: AgentDraft): AgentToolsetItemWire | null {
  const enabled = normalizeStrings(draft.enabledTools);
  const policyNames = new Set<string>(enabled);
  for (const tool of BUILTIN_TOOLS) {
    if ((draft.toolPolicies[tool] ?? '').trim()) {
      policyNames.add(tool);
    }
  }
  const configs: AgentToolConfigWire[] = [];
  for (const tool of BUILTIN_TOOLS) {
    const policy = (draft.toolPolicies[tool] ?? '').trim();
    if (policy && policyNames.has(tool)) {
      configs.push({ name: tool, permission_policy: policy });
    }
  }
  if (!enabled.length && !configs.length) {
    return null;
  }
  const entry: AgentToolsetItemWire = { type: AGENT_TOOLSET_TYPE };
  if (enabled.length) {
    entry.enabled_tools = enabled;
  }
  if (configs.length) {
    entry.configs = configs;
  }
  return entry;
}

/** custom 工具条目装配（Schema 文本 → 结构化对象）。 */
function buildCustomToolEntries(draft: AgentDraft): AgentCustomToolWire[] {
  const entries: AgentCustomToolWire[] = [];
  for (const tool of draft.customTools) {
    const schema = parseJsonObject(tool.inputSchemaJson, `自定义工具 ${tool.name} 的输入 Schema`);
    entries.push({
      type: CUSTOM_TOOL_TYPE,
      name: tool.name.trim(),
      description: tool.description.trim(),
      input_schema: schema ?? { type: 'object' },
    });
  }
  return entries;
}

/** 技能绑定装配：动态版（空白或 latest）不落 version 键。 */
function buildSkillBindings(draft: AgentDraft): AgentSkillBindingWire[] {
  const bindings: AgentSkillBindingWire[] = [];
  for (const binding of draft.skillBindings) {
    const skillId = binding.skillId.trim();
    if (!skillId) {
      continue;
    }
    const version = binding.version.trim();
    const item: AgentSkillBindingWire = { type: CUSTOM_SKILL_TYPE, skill_id: skillId };
    if (version && version.toLowerCase() !== SKILL_VERSION_LATEST) {
      item.version = version;
    }
    bindings.push(item);
  }
  return bindings;
}

/**
 * 草稿 → AgentConfigRequest 载荷（纯函数）。
 * <p>规则：model 按调优字段是否填写决定字符串/对象两形态；tools 先排一个内置工具集条目
 * （enabled_tools + configs），再逐条排 custom 工具；skills 仅 custom 类型（动态版省略版本键）；
 * description/system/metadata 空白按缺省不提交；废止字段零输出。</p>
 * @param draft 表单草稿
 * @returns 后端提交载荷
 * @throws Error 校验未通过时抛首条错误
 */
export function buildConfigRequest(draft: AgentDraft): AgentConfigPayload {
  const errors = validateDraft(draft);
  if (hasDraftErrors(errors)) {
    throw new Error(firstDraftError(errors));
  }
  const tools: AgentToolWire[] = [];
  const toolset = buildToolsetEntry(draft);
  if (toolset) {
    tools.push(toolset);
  }
  tools.push(...buildCustomToolEntries(draft));

  const payload: AgentConfigPayload = {
    name: draft.name.trim(),
    model: buildModelRef(draft),
  };
  if (draft.description.trim()) {
    payload.description = draft.description.trim();
  }
  if (draft.system.trim()) {
    payload.system = draft.system;
  }
  if (tools.length) {
    payload.tools = tools;
  }
  const skills = buildSkillBindings(draft);
  if (skills.length) {
    payload.skills = skills;
  }
  const metadata = parseJsonObject(draft.metadataJson, '元数据');
  if (metadata) {
    payload.metadata = metadata;
  }
  return payload;
}

/** 取首条错误文案（装配函数抛错与界面提示共用同一口径）。 */
export function firstDraftError(errors: AgentDraftErrors): string {
  for (const message of [errors.name, errors.modelId, errors.system, errors.description, errors.skillBindings,
    errors.tools, errors.metadataJson]) {
    if (message) {
      return message;
    }
  }
  for (const toolErrors of Object.values(errors.customTools)) {
    const message = toolErrors.name ?? toolErrors.description ?? toolErrors.inputSchema;
    if (message) {
      return message;
    }
  }
  return '配置校验未通过';
}

/** 模型引用 → 目录模型 id（字符串简写与对象形态统一取值）。 */
export function modelRefId(model: ModelRefWire | null | undefined): string {
  if (!model) {
    return '';
  }
  if (typeof model === 'string') {
    return model;
  }
  return model.id ?? '';
}

/** 模型引用是否为字符串简写形态（auto 口径）。 */
export function modelRefIsShorthand(model: ModelRefWire | null | undefined): boolean {
  return typeof model === 'string';
}

/** 是否为内置工具集条目（按 type 词汇判别）。 */
function isToolsetEntry(entry: AgentToolWire | null | undefined): entry is AgentToolsetItemWire {
  return Boolean(entry && entry.type === AGENT_TOOLSET_TYPE);
}

/** 是否为 custom 工具条目（type=custom 且带非空 name）。 */
function isCustomToolEntry(entry: AgentToolWire | null | undefined): entry is AgentCustomToolWire {
  return Boolean(entry && entry.type === CUSTOM_TOOL_TYPE && 'name' in entry && entry.name);
}

/** 逐工具策略合法词汇（后端 ToolConfig 三值）。 */
const POLICY_VALUES = new Set<string>(['always_allow', 'always_ask', 'always_deny']);

/**
 * 线格式对象 → 表单草稿回显（纯函数）。
 * <p>内置工具勾选集合按后端装配叠加顺序反解：各工具集条目 enabled_tools 并集
 * （缺省 = 平台全量基座，勾选集合留空，界面无勾选即"全量"语义）→ 扣除 disallowed_tools
 * 与 configs[].enabled=false → 叠加 configs[].enabled=true。权限策略取 configs 并集
 * （隐藏工具的 policy 一并剔除，表单无法表达"隐藏但带策略"）。custom 条目 Schema 回落格式化 JSON 文本；
 * 技能绑定动态版（缺省或 latest）回空串。</p>
 */
export function agentToDraft(wire: AgentWire, originCandidates?: ModelOriginCandidates): AgentDraft {
  const draft = createEmptyAgentDraft();
  draft.name = wire.name ?? '';
  draft.description = wire.description ?? '';
  draft.system = wire.system ?? '';
  draft.modelId = modelRefId(wire.model);
  if (originCandidates) {
    draft.modelOrigin = inferModelOrigin(draft.modelId, originCandidates.profileIds, originCandidates.catalogIds);
  }
  if (wire.model && typeof wire.model === 'object') {
    draft.effort = wire.model.effort ?? '';
    draft.contextWindow = typeof wire.model.context_window === 'number' ? wire.model.context_window : null;
  }

  const whitelist: string[] = [];
  const hidden = new Set<string>();
  const revived = new Set<string>();
  const policies: Record<string, string> = {};
  for (const entry of wire.tools ?? []) {
    if (!isToolsetEntry(entry)) {
      if (isCustomToolEntry(entry)) {
        draft.customTools.push({
          name: entry.name ?? '',
          description: entry.description ?? '',
          inputSchemaJson: formatJsonObject(entry.input_schema),
        });
      }
      continue;
    }
    for (const tool of normalizeStrings(entry.enabled_tools)) {
      if (!whitelist.includes(tool)) {
        whitelist.push(tool);
      }
    }
    for (const tool of normalizeStrings(entry.disallowed_tools)) {
      hidden.add(tool);
    }
    for (const config of entry.configs ?? []) {
      const name = (config?.name ?? '').trim();
      if (!name) {
        continue;
      }
      if (config.enabled === false) {
        hidden.add(name);
        delete policies[name];
        continue;
      }
      if (config.enabled === true) {
        hidden.delete(name);
        revived.add(name);
      }
      const policy = (config.permission_policy ?? '').trim();
      if (policy && POLICY_VALUES.has(policy)) {
        policies[name] = policy;
      }
    }
  }
  const enabled = [...whitelist];
  for (const tool of revived) {
    if (!enabled.includes(tool)) {
      enabled.push(tool);
    }
  }
  draft.enabledTools = enabled.filter((tool) => !hidden.has(tool));
  draft.toolPolicies = Object.fromEntries(
    Object.entries(policies).filter(([tool]) => !hidden.has(tool)),
  );

  for (const binding of wire.skills ?? []) {
    const skillId = (binding?.skill_id ?? '').trim();
    if (!skillId) {
      continue;
    }
    const version = (binding.version ?? '').trim();
    draft.skillBindings.push({
      skillId,
      version: version.toLowerCase() === SKILL_VERSION_LATEST ? '' : version,
      displayTitle: skillId,
    });
  }

  const metadata = wire.metadata;
  if (metadata && Object.keys(metadata).length > 0) {
    draft.metadataJson = JSON.stringify(metadata, null, 2);
  }
  return draft;
}

/** 结构化对象 → 格式化 JSON 文本（空对象回落骨架模板）。 */
function formatJsonObject(value: Record<string, unknown> | undefined): string {
  if (!value || Object.keys(value).length === 0) {
    return '{\n  "type": "object",\n  "properties": {}\n}';
  }
  return JSON.stringify(value, null, 2);
}

/** 新建 custom 工具条目（默认给出 object 骨架，降低手写成本）。 */
export function createEmptyCustomTool(): CustomToolDraft {
  return { name: '', description: '', inputSchemaJson: formatJsonObject(undefined) };
}

/**
 * 草稿 → Raw 模式整份配置 JSON 文本（纯函数；校验不通过抛 Error）。
 * <p>输出即"将要提交的载荷"，键集不含任何废止字段，可直接交技术用户审阅/编辑。</p>
 */
export function draftToRawJson(draft: AgentDraft): string {
  return JSON.stringify(buildConfigRequest(draft), null, 2);
}

/** Raw JSON 中出现的废止键（提交即后端 400，本层显式拒绝并给出改写指引）。 */
const DEPRECATED_RAW_KEYS = ['agents_md', 'agentsMd', 'mcp_servers', 'mcpServers', 'multiagent', 'vaults'] as const;

/** 递归拒绝 model 对象形态中的已废止 speed 字段。 */
function rejectDeprecatedModel(model: Record<string, unknown>): void {
  if ('speed' in model) {
    throw new Error('model.speed 字段已废止，请改用 model.effort');
  }
}

/**
 * Raw 模式 JSON 文本 → 表单草稿（纯函数，提交前校验入口）。
 * <p>校验：合法 JSON 对象；名称必填 ≤256；模型引用须为字符串或 {id,...} 对象；
 * tools 条目 type 仅接受 agent_toolset_20260401/custom；skills ≤20；
 * 废止字段（agents_md / mcp_servers / multiagent / vaults / model.speed）出现即抛错，
 * 保证零输出。</p>
 * @param text Raw JSON 文本
 * @param originCandidates 模型来源判定候选（可空 = 直选态默认，由调用方后续校准）
 * @returns 结构化草稿（mode=raw 由调用方按需保留）
 * @throws Error JSON 非法或含废止字段/越界配置
 */
export function rawJsonToDraft(text: string, originCandidates?: ModelOriginCandidates): AgentDraft {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch (e) {
    throw new Error(`配置 JSON 非法：${e instanceof Error ? e.message : '解析失败'}`);
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    throw new Error('配置必须是 JSON 对象');
  }
  const source = parsed as Record<string, unknown>;
  for (const key of DEPRECATED_RAW_KEYS) {
    if (key in source) {
      throw new Error(`字段 ${key} 已废止，不可提交（指令请用 system 承载）`);
    }
  }
  const wire: AgentWire = {
    id: '',
    type: 'agent',
    name: typeof source.name === 'string' ? source.name : '',
    description: typeof source.description === 'string' ? source.description : null,
    model: normalizeRawModel(source.model),
    system: typeof source.system === 'string' ? source.system : null,
    tools: normalizeRawTools(source.tools),
    skills: normalizeRawSkills(source.skills),
    metadata: (source.metadata && typeof source.metadata === 'object' && !Array.isArray(source.metadata))
      ? source.metadata as Record<string, unknown>
      : {},
    multiagent: null,
    version: 0,
    archived_at: null,
    created_at: '',
    updated_at: '',
  };
  const draft = agentToDraft(wire, originCandidates);
  draft.mode = 'raw';
  return draft;
}

/** Raw 模型引用归一（字符串简写或 {id,effort?,context_window?}；拒绝 speed）。 */
function normalizeRawModel(value: unknown): ModelRefWire | null {
  if (typeof value === 'string') {
    return value;
  }
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    const model = { ...(value as Record<string, unknown>) } as unknown as ModelRefObjectWire;
    rejectDeprecatedModel(value as Record<string, unknown>);
    const id = typeof model.id === 'string' ? model.id : '';
    const effort = typeof model.effort === 'string' ? model.effort : undefined;
    const contextWindow = typeof model.context_window === 'number' ? model.context_window : undefined;
    const out: ModelRefObjectWire = { id };
    if (effort) {
      out.effort = effort;
    }
    if (contextWindow !== undefined) {
      out.context_window = contextWindow;
    }
    return out;
  }
  return null;
}

/** Raw 工具数组归一：条目须为对象且 type 仅接受两类公开词汇（browser/mcp 提交即后端 400，此处提前拒绝）。 */
function normalizeRawTools(value: unknown): AgentToolWire[] {
  if (!Array.isArray(value)) {
    return [];
  }
  const out: AgentToolWire[] = [];
  for (const item of value) {
    if (!item || typeof item !== 'object' || Array.isArray(item)) {
      throw new Error('tools 条目必须是 JSON 对象');
    }
    const entry = item as AgentToolWire;
    if (entry.type !== AGENT_TOOLSET_TYPE && entry.type !== CUSTOM_TOOL_TYPE) {
      throw new Error(`tools 条目 type 仅支持 ${AGENT_TOOLSET_TYPE} / custom（收到：${String(entry.type)}）`);
    }
    if (entry.type === CUSTOM_TOOL_TYPE && (entry as AgentToolsetItemWire).configs) {
      throw new Error('custom 工具不支持 configs/permission_policy');
    }
    out.push(entry);
  }
  return out;
}

/** Raw 技能数组归一：条目须为对象且 type 为 catalog/custom（数量上限交由 validateDraft 提示）。 */
function normalizeRawSkills(value: unknown): AgentSkillBindingWire[] {
  if (!Array.isArray(value)) {
    return [];
  }
  const out: AgentSkillBindingWire[] = [];
  for (const item of value) {
    if (!item || typeof item !== 'object' || Array.isArray(item)) {
      throw new Error('skills 条目必须是 JSON 对象');
    }
    const entry = item as AgentSkillBindingWire;
    if (entry.type !== CUSTOM_SKILL_TYPE && entry.type !== 'catalog') {
      throw new Error(`skills 条目 type 仅支持 catalog / custom（收到：${String(entry.type)}）`);
    }
    out.push(entry);
  }
  return out;
}

/** 是否已归档（契约仅以 archived_at 表达，无 archived 布尔）。 */
export function isAgentArchived(agent: Pick<AgentWire, 'archived_at'>): boolean {
  return Boolean(agent.archived_at);
}

/** 列表状态词汇（active/archived，与后端 status 过滤同口径）。 */
export function agentStatus(agent: Pick<AgentWire, 'archived_at'>): 'active' | 'archived' {
  return isAgentArchived(agent) ? 'archived' : 'active';
}

/** 模型列展示文本（目录 id；缺失回落占位）。 */
export function modelColumnText(model: ModelRefWire | null | undefined): string {
  return modelRefId(model) || '—';
}
