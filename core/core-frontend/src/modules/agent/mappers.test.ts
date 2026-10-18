// @vitest-environment node
import { describe, expect, it } from 'vitest';
import type { AgentWire } from './api/agents';
import {
  AGENT_TOOLSET_TYPE,
  MAX_SKILL_BINDINGS,
  NAME_MAX_LENGTH,
  agentStatus,
  agentToDraft,
  buildConfigRequest,
  createEmptyAgentDraft,
  draftToRawJson,
  firstDraftError,
  hasDraftErrors,
  inferModelOrigin,
  isAgentArchived,
  modelColumnText,
  rawJsonToDraft,
  validateCustomToolName,
  validateCustomToolSchema,
  validateDraft,
} from './mappers';
import type { AgentDraft, SkillBindingDraft } from './mappers';

/** 构造一个最小合法草稿（各用例在此基础上按需覆盖）。 */
function baseDraft(overrides: Partial<AgentDraft> = {}): AgentDraft {
  return { ...createEmptyAgentDraft(), name: '数据分析智能体', modelId: 'auto', ...overrides };
}

/** 把草稿的技能绑定按数量填充（用于上限边界用例）。 */
function bindingsOf(count: number): SkillBindingDraft[] {
  return Array.from({ length: count }, (_item, index) => ({
    skillId: `skill_${index}`,
    version: '',
    displayTitle: `技能${index}`,
  }));
}

/** 构造线格式智能体对象（用例只覆盖关心的字段）。 */
function wire(overrides: Partial<AgentWire> = {}): AgentWire {
  return {
    id: 'agent_1',
    type: 'agent',
    name: '数据分析智能体',
    description: null,
    model: 'auto',
    system: null,
    tools: [],
    skills: [],
    metadata: {},
    multiagent: null,
    version: 1,
    archived_at: null,
    created_at: '2026-10-01T02:00:00Z',
    updated_at: '2026-10-02T02:00:00Z',
    ...overrides,
  };
}

describe('buildConfigRequest 模型引用两形态', () => {
  it('should_落字符串简写_when_buildConfigRequest_given_effort与窗口均空', () => {
    // given
    const draft = baseDraft({ effort: '', contextWindow: null });

    // when
    const payload = buildConfigRequest(draft);

    // then
    expect(payload.model).toBe('auto');
  });

  it('should_落对象形态_when_buildConfigRequest_given_仅填effort', () => {
    // given
    const draft = baseDraft({ effort: 'high', contextWindow: null });

    // when
    const payload = buildConfigRequest(draft);

    // then
    expect(payload.model).toEqual({ id: 'auto', effort: 'high' });
  });

  it('should_落对象形态_when_buildConfigRequest_given_仅填context_window', () => {
    // given
    const draft = baseDraft({ effort: '', contextWindow: 200000 });

    // when
    const payload = buildConfigRequest(draft);

    // then
    expect(payload.model).toEqual({ id: 'auto', context_window: 200000 });
  });

  it('should_同时落两调优键_when_buildConfigRequest_given_effort与窗口均填', () => {
    // given
    const draft = baseDraft({ effort: 'low', contextWindow: 32000 });

    // when
    const payload = buildConfigRequest(draft);

    // then
    expect(payload.model).toEqual({ id: 'auto', effort: 'low', context_window: 32000 });
  });

  it('should_抛名称必填_when_buildConfigRequest_given_名称为空白', () => {
    // given
    const draft = baseDraft({ name: '   ' });

    // when
    const build = () => buildConfigRequest(draft);

    // then
    expect(build).toThrowError(/名称/);
  });

  it('should_抛模型必选_when_buildConfigRequest_given_模型id为空', () => {
    // given
    const draft = baseDraft({ modelId: '' });

    // when & then
    expect(() => buildConfigRequest(draft)).toThrowError(/模型/);
  });

  it('should_trim名称且不提交description与system_when_buildConfigRequest_given_描述与提示词留空', () => {
    // given
    const draft = baseDraft({ name: '  报表智能体  ', description: '  ', system: '  \n ' });

    // when
    const payload = buildConfigRequest(draft);

    // then
    expect(payload.name).toBe('报表智能体');
    expect(payload).not.toHaveProperty('description');
    expect(payload).not.toHaveProperty('system');
  });
});

describe('buildConfigRequest 废止字段零输出', () => {
  it('should_无任何废止键_when_buildConfigRequest_given_全字段草稿', () => {
    // given
    const draft = baseDraft({
      description: '描述',
      system: '你是数据分析助手',
      metadataJson: '{"team":"da"}',
      enabledTools: ['Bash'],
      toolPolicies: { Bash: 'always_ask' },
    });

    // when
    const payload = buildConfigRequest(draft);
    const text = JSON.stringify(payload);

    // then
    for (const key of ['agents_md', 'agentsMd', 'mcp_servers', 'mcpServers', 'multiagent', 'vaults']) {
      expect(payload).not.toHaveProperty(key);
    }
    expect(text).not.toContain('speed');
    expect(text).not.toContain('mcp');
  });

  it('should_拒绝Raw中的agents_md_when_rawJsonToDraft_given_文本含agents_md键', () => {
    // given
    const text = JSON.stringify({ name: 'A', model: 'auto', agents_md: '指令' });

    // when
    const parse = () => rawJsonToDraft(text);

    // then
    expect(parse).toThrowError(/agents_md/);
  });

  it('should_拒绝Raw中的mcp_servers与multiagent_when_rawJsonToDraft_given_含废止键', () => {
    // given & when & then
    expect(() => rawJsonToDraft(JSON.stringify({ name: 'A', model: 'auto', mcp_servers: [{ name: 's' }] })))
      .toThrowError(/mcp_servers/);
    expect(() => rawJsonToDraft(JSON.stringify({ name: 'A', model: 'auto', multiagent: { type: 'x' } })))
      .toThrowError(/multiagent/);
  });

  it('should_拒绝model.speed_when_rawJsonToDraft_given_对象形态带speed', () => {
    // given
    const text = JSON.stringify({ name: 'A', model: { id: 'auto', speed: 'high' } });

    // when & then
    expect(() => rawJsonToDraft(text)).toThrowError(/speed/);
  });
});

describe('buildConfigRequest tools 装配与回读往返', () => {
  it('should_单个内置工具集条目_when_buildConfigRequest_given_勾选两工具且一策略', () => {
    // given
    const draft = baseDraft({
      enabledTools: ['Bash', 'Read'],
      toolPolicies: { Bash: 'always_allow', Read: '' },
    });

    // when
    const payload = buildConfigRequest(draft);

    // then
    expect(payload.tools).toEqual([
      { type: AGENT_TOOLSET_TYPE, enabled_tools: ['Bash', 'Read'], configs: [{ name: 'Bash', permission_policy: 'always_allow' }] },
    ]);
  });

  it('should_不落tools_when_buildConfigRequest_given_无勾选无策略且无custom', () => {
    // given
    const draft = baseDraft({ enabledTools: [], toolPolicies: {} });

    // when
    const payload = buildConfigRequest(draft);

    // then
    expect(payload).not.toHaveProperty('tools');
  });

  it('should_内置条目先于custom_when_buildConfigRequest_given_同时有勾选与custom工具', () => {
    // given
    const draft = baseDraft({
      enabledTools: ['Grep'],
      customTools: [{ name: 'query_orders', description: '查订单', inputSchemaJson: '{"type":"object"}' }],
    });

    // when
    const payload = buildConfigRequest(draft);
    const tools = payload.tools ?? [];

    // then
    expect(tools).toHaveLength(2);
    expect(tools[0]).toEqual({ type: AGENT_TOOLSET_TYPE, enabled_tools: ['Grep'] });
    expect(tools[1]).toEqual({
      type: 'custom',
      name: 'query_orders',
      description: '查订单',
      input_schema: { type: 'object' },
    });
  });

  it('should_丢弃重复与空白工具名_when_buildConfigRequest_given_enabledTools含重复项', () => {
    // given
    const draft = baseDraft({ enabledTools: ['Bash', ' Bash ', 'Bash', ''] });

    // when
    const payload = buildConfigRequest(draft);

    // then
    expect((payload.tools ?? [])[0]).toEqual({ type: AGENT_TOOLSET_TYPE, enabled_tools: ['Bash'] });
  });

  it('should_往返一致_when_agentToDraft_then_buildConfigRequest_given_白名单与策略快照', () => {
    // given
    const original = wire({
      model: { id: 'ultimate', effort: 'medium', context_window: 64000 },
      system: '指令',
      tools: [
        {
          type: AGENT_TOOLSET_TYPE,
          enabled_tools: ['Bash', 'Write'],
          configs: [{ name: 'Write', permission_policy: 'always_deny' }],
        },
        { type: 'custom', name: 'send_mail', description: '发邮件', input_schema: { type: 'object', properties: {} } },
      ],
      skills: [{ type: 'custom', skill_id: 'skill_a' }, { type: 'custom', skill_id: 'skill_b', version: '1759178010641129' }],
      metadata: { biz: 'demo' },
    });

    // when
    const payload = buildConfigRequest(agentToDraft(original));

    // then
    expect(payload.name).toBe('数据分析智能体');
    expect(payload.model).toEqual({ id: 'ultimate', effort: 'medium', context_window: 64000 });
    expect(payload.system).toBe('指令');
    expect(payload.tools).toEqual(original.tools);
    expect(payload.skills).toEqual(original.skills);
    expect(payload.metadata).toEqual({ biz: 'demo' });
  });

  it('should_扣除隐藏工具_when_agentToDraft_given_disallowed与enabled_false', () => {
    // given
    const original = wire({
      tools: [
        {
          type: AGENT_TOOLSET_TYPE,
          enabled_tools: ['Bash', 'Read', 'Write'],
          disallowed_tools: ['Read'],
          configs: [
            { name: 'Write', enabled: false },
            { name: 'Bash', permission_policy: 'always_ask' },
          ],
        },
      ],
    });

    // when
    const draft = agentToDraft(original);

    // then
    expect(draft.enabledTools).toEqual(['Bash']);
    expect(draft.toolPolicies).toEqual({ Bash: 'always_ask' });
  });

  it('should_复活工具_when_agentToDraft_given_configs_enabled_true叠加', () => {
    // given
    const original = wire({
      tools: [{ type: AGENT_TOOLSET_TYPE, disallowed_tools: ['Bash'], configs: [{ name: 'Bash', enabled: true }] }],
    });

    // when
    const draft = agentToDraft(original);

    // then
    expect(draft.enabledTools).toEqual(['Bash']);
  });

  it('should_回空串版本_when_agentToDraft_given_技能版本为latest', () => {
    // given
    const original = wire({ skills: [{ type: 'custom', skill_id: 'skill_a', version: 'latest' }] });

    // when
    const payload = buildConfigRequest(agentToDraft(original));

    // then
    expect(payload.skills).toEqual([{ type: 'custom', skill_id: 'skill_a' }]);
  });

  it('should_忽略非法策略词汇_when_agentToDraft_given_permission_policy未知值', () => {
    // given
    const original = wire({
      tools: [{ type: AGENT_TOOLSET_TYPE, enabled_tools: ['Bash'], configs: [{ name: 'Bash', permission_policy: 'sometimes' }] }],
    });

    // when
    const draft = agentToDraft(original);

    // then
    expect(draft.enabledTools).toEqual(['Bash']);
    expect(draft.toolPolicies).toEqual({});
  });
});

describe('custom 工具校验', () => {
  it('should_通过_when_validateCustomToolName_given_普通下划线名', () => {
    // given & when & then
    expect(validateCustomToolName('query_orders')).toBe('');
  });

  it('should_拒绝内置重名_when_validateCustomToolName_given_Bash', () => {
    // given & when & then
    expect(validateCustomToolName('Bash')).toContain('内置工具');
  });

  it('should_拒绝保留名advisor_when_validateCustomToolName_given_大小写变体', () => {
    // given & when & then
    expect(validateCustomToolName('Advisor')).toContain('advisor');
  });

  it('should_拒绝mcp前缀与双下划线_when_validateCustomToolName_given_保留词汇', () => {
    // given & when & then
    expect(validateCustomToolName('mcp__server__tool')).toContain('mcp__');
    expect(validateCustomToolName('a__b')).toContain('__');
  });

  it('should_拒绝空名_when_validateCustomToolName_given_纯空白', () => {
    // given & when & then
    expect(validateCustomToolName('   ')).toContain('名称');
  });

  it('should_拒绝非object的schema_when_validateCustomToolSchema_given_type为array', () => {
    // given & when & then
    expect(validateCustomToolSchema('{"type":"array"}')).toContain("object");
  });

  it('should_拒绝非法JSON_when_validateCustomToolSchema_given_缺右括号', () => {
    // given & when & then
    expect(validateCustomToolSchema('{"type":"object"')).toContain('JSON');
  });

  it('should_拒绝空Schema_when_validateCustomToolSchema_given_空白文本', () => {
    // given & when & then
    expect(validateCustomToolSchema('  ')).toContain('Schema');
  });

  it('should_报三条字段错误_when_validateDraft_given_custom工具全缺字段', () => {
    // given
    const draft = baseDraft({ customTools: [{ name: '', description: '', inputSchemaJson: '' }] });

    // when
    const errors = validateDraft(draft);

    // then
    expect(errors.customTools[0]?.name).toBeTruthy();
    expect(errors.customTools[0]?.description).toBeTruthy();
    expect(errors.customTools[0]?.inputSchema).toBeTruthy();
    expect(hasDraftErrors(errors)).toBe(true);
  });

  it('should_报元数据非法_when_validateDraft_given_metadata为数组', () => {
    // given
    const draft = baseDraft({ metadataJson: '[1,2]' });

    // when
    const errors = validateDraft(draft);

    // then
    expect(errors.metadataJson).toContain('对象');
  });

  it('should_无错误_when_validateDraft_given_合法全字段草稿', () => {
    // given
    const draft = baseDraft({
      description: '描述',
      system: '指令',
      metadataJson: '{"k":"v"}',
      enabledTools: ['Bash'],
      toolPolicies: { Bash: 'always_ask' },
      customTools: [{ name: 'q', description: 'd', inputSchemaJson: '{"type":"object"}' }],
      skillBindings: [{ skillId: 'skill_1', version: '', displayTitle: '技能一' }],
    });

    // when
    const errors = validateDraft(draft);

    // then
    expect(hasDraftErrors(errors)).toBe(false);
    expect(errors.customTools).toEqual({});
  });

  it('should_报白名单越界_when_validateDraft_given_enabledTools含非内置名', () => {
    // given
    const draft = baseDraft({ enabledTools: ['Bash', 'BrowserUse'] });

    // when
    const errors = validateDraft(draft);

    // then
    expect(errors.tools).toContain('BrowserUse');
    expect(() => buildConfigRequest(draft)).toThrowError(/内置工具/);
  });

  it('should_报名称超长_when_validateDraft_given_名称超过256字符', () => {
    // given
    const draft = baseDraft({ name: 'a'.repeat(NAME_MAX_LENGTH + 1) });

    // when & then
    expect(validateDraft(draft).name).toContain('256');
  });
});

describe('技能绑定上限', () => {
  it('should_通过_when_validateDraft_given_绑定数刚好20', () => {
    // given
    const draft = baseDraft({ skillBindings: bindingsOf(MAX_SKILL_BINDINGS) });

    // when
    const errors = validateDraft(draft);

    // then
    expect(errors.skillBindings).toBeUndefined();
    expect(hasDraftErrors(errors)).toBe(false);
  });

  it('should_报上限_when_validateDraft_given_绑定数21', () => {
    // given
    const draft = baseDraft({ skillBindings: bindingsOf(MAX_SKILL_BINDINGS + 1) });

    // when
    const errors = validateDraft(draft);

    // then
    expect(errors.skillBindings).toContain('20');
    expect(() => buildConfigRequest(draft)).toThrowError(/20/);
  });

  it('should_丢弃空白skillId_when_buildConfigRequest_given_含空绑定条目', () => {
    // given
    const draft = baseDraft({
      skillBindings: [
        { skillId: '  ', version: '', displayTitle: '' },
        { skillId: ' skill_a ', version: '  ', displayTitle: '' },
      ],
    });

    // when
    const payload = buildConfigRequest(draft);

    // then
    expect(payload.skills).toEqual([{ type: 'custom', skill_id: 'skill_a' }]);
  });
});

describe('Raw 模式 JSON 往返', () => {
  it('should_输出格式化载荷_when_draftToRawJson_given_结构化草稿', () => {
    // given
    const draft = baseDraft({ enabledTools: ['Bash'], toolPolicies: { Bash: 'always_allow' } });

    // when
    const text = draftToRawJson(draft);

    // then
    expect(text).toContain('"type": "agent_toolset_20260401"');
    expect(JSON.parse(text)).toEqual({
      name: '数据分析智能体',
      model: 'auto',
      tools: [{ type: AGENT_TOOLSET_TYPE, enabled_tools: ['Bash'], configs: [{ name: 'Bash', permission_policy: 'always_allow' }] }],
    });
  });

  it('should_回填结构化字段_when_rawJsonToDraft_given_完整配置文本', () => {
    // given
    const text = JSON.stringify({
      name: 'Raw 智能体',
      description: '说明',
      system: '指令',
      model: { id: 'auto', effort: 'max' },
      tools: [
        { type: AGENT_TOOLSET_TYPE, enabled_tools: ['WebSearch'] },
        { type: 'custom', name: 'lookup', description: '查询', input_schema: { type: 'object' } },
      ],
      skills: [{ type: 'custom', skill_id: 'skill_x', version: '1759178010641129' }],
      metadata: { k: 'v' },
    });

    // when
    const draft = rawJsonToDraft(text);

    // then
    expect(draft.mode).toBe('raw');
    expect(draft.name).toBe('Raw 智能体');
    expect(draft.modelId).toBe('auto');
    expect(draft.effort).toBe('max');
    expect(draft.enabledTools).toEqual(['WebSearch']);
    expect(draft.customTools).toHaveLength(1);
    expect(draft.skillBindings[0]?.version).toBe('1759178010641129');
    expect(JSON.parse(draft.metadataJson)).toEqual({ k: 'v' });
  });

  it('should_往返等价_when_rawJsonToDraft_then_buildConfigRequest_given_原始载荷', () => {
    // given
    const payload = buildConfigRequest(baseDraft({
      system: '指令',
      enabledTools: ['Bash', 'Read'],
      toolPolicies: { Read: 'always_ask' },
      customTools: [{ name: 'q', description: 'd', inputSchemaJson: '{"type":"object","properties":{}}' }],
      skillBindings: [{ skillId: 'skill_1', version: '1759', displayTitle: '' }],
      metadataJson: '{"a":1}',
    }));

    // when
    const rebuilt = buildConfigRequest(rawJsonToDraft(JSON.stringify(payload)));

    // then
    expect(rebuilt).toEqual(payload);
  });

  it('should_抛非法JSON_when_rawJsonToDraft_given_文本不可解析', () => {
    // given & when & then
    expect(() => rawJsonToDraft('{oops')).toThrowError(/JSON/);
  });

  it('should_抛非对象_when_rawJsonToDraft_given_数组形态', () => {
    // given & when & then
    expect(() => rawJsonToDraft('[1,2]')).toThrowError(/对象/);
  });

  it('should_抛类型词汇非法_when_rawJsonToDraft_given_browser工具集条目', () => {
    // given
    const text = JSON.stringify({ name: 'A', model: 'auto', tools: [{ type: 'browser_toolset_20260714' }] });

    // when & then
    expect(() => rawJsonToDraft(text)).toThrowError(/type/);
  });

  it('should_抛首条错误_when_firstDraftError_given_名称缺失优先', () => {
    // given
    const errors = validateDraft(baseDraft({ name: '', modelId: '' }));

    // when & then
    expect(firstDraftError(errors)).toBe('请输入智能体名称');
  });
});

describe('列表展示辅助', () => {
  it('should_以archived_at判定状态_when_agentStatus_given_时间戳与null', () => {
    // given & when & then
    expect(agentStatus(wire({ archived_at: '2026-10-03T02:00:00Z' }))).toBe('archived');
    expect(isAgentArchived(wire({ archived_at: '2026-10-03T02:00:00Z' }))).toBe(true);
    expect(agentStatus(wire({ archived_at: null }))).toBe('active');
    expect(isAgentArchived(wire({ archived_at: null }))).toBe(false);
  });

  it('should_取目录id_when_modelColumnText_given_两形态与空值', () => {
    // given & when & then
    expect(modelColumnText('auto')).toBe('auto');
    expect(modelColumnText({ id: 'ultimate', effort: 'low' })).toBe('ultimate');
    expect(modelColumnText(null)).toBe('—');
  });
});

describe('模型来源双模式与 origin 判定', () => {
  it('should_默认直选态_when_createEmptyAgentDraft_given_新建空草稿', () => {
    // given & when & then
    expect(createEmptyAgentDraft().modelOrigin).toBe('profile');
  });

  it('should_判目录态_when_inferModelOrigin_given_模型id命中目录名单', () => {
    // given & when & then
    expect(inferModelOrigin('ultimate', ['mp-1'], ['auto', 'ultimate'])).toBe('catalog');
  });

  it('should_判直选态_when_inferModelOrigin_given_模型id命中配置名单', () => {
    // given & when & then
    expect(inferModelOrigin('mp-qwen38-flash', ['mp-qwen38-flash'], ['auto'])).toBe('profile');
  });

  it('should_判直选态_when_inferModelOrigin_given_两名单皆不命中', () => {
    // given & when & then（皆不命中归直选态原样展示，允许重选，不报错）
    expect(inferModelOrigin('ghost-id', ['mp-1'], ['auto'])).toBe('profile');
  });

  it('should_判直选态_when_inferModelOrigin_given_模型id为空白', () => {
    // given & when & then
    expect(inferModelOrigin('  ', ['mp-1'], ['auto'])).toBe('profile');
  });

  it('should_落字符串简写_when_buildConfigRequest_given_目录态无调优', () => {
    // given
    const draft = baseDraft({ modelOrigin: 'catalog', modelId: 'auto', effort: '', contextWindow: null });

    // when
    const payload = buildConfigRequest(draft);

    // then
    expect(payload.model).toBe('auto');
  });

  it('should_落对象形态_when_buildConfigRequest_given_目录态带effort与窗口', () => {
    // given
    const draft = baseDraft({ modelOrigin: 'catalog', modelId: 'ultimate', effort: 'high', contextWindow: 200000 });

    // when
    const payload = buildConfigRequest(draft);

    // then
    expect(payload.model).toEqual({ id: 'ultimate', effort: 'high', context_window: 200000 });
  });

  it('should_提示选档位_when_validateDraft_given_目录态模型为空', () => {
    // given & when
    const errors = validateDraft(baseDraft({ modelOrigin: 'catalog', modelId: '' }));

    // then（文案与直选态区分）
    expect(errors.modelId).toBe('请选择模型档位');
  });

  it('should_拦截不在清单档位_when_validateDraft_given_目录态档位不在目录', () => {
    // given & when
    const errors = validateDraft(baseDraft({ modelOrigin: 'catalog', modelId: 'ghost' }), ['auto', 'ultimate']);

    // then
    expect(errors.modelId).toContain('不在模型目录清单');
  });

  it('should_放行在清单档位_when_validateDraft_given_目录态档位命中目录', () => {
    // given & when
    const errors = validateDraft(baseDraft({ modelOrigin: 'catalog', modelId: 'auto' }), ['auto']);

    // then
    expect(errors.modelId).toBeUndefined();
  });

  it('should_不软校验_when_validateDraft_given_目录名单未加载', () => {
    // given & when（catalogIds 缺省 = 不做档位存在性软校验，后端为最终权威）
    const errors = validateDraft(baseDraft({ modelOrigin: 'catalog', modelId: 'ghost' }));

    // then
    expect(errors.modelId).toBeUndefined();
  });

  it('should_回填目录态与调优_when_rawJsonToDraft_given_对象形态文本', () => {
    // given
    const text = JSON.stringify({
      name: '目录智能体',
      model: { id: 'ultimate', effort: 'high', context_window: 200000 },
    });

    // when
    const draft = rawJsonToDraft(text, { profileIds: ['mp-1'], catalogIds: ['auto', 'ultimate'] });

    // then
    expect(draft.modelOrigin).toBe('catalog');
    expect(draft.modelId).toBe('ultimate');
    expect(draft.effort).toBe('high');
    expect(draft.contextWindow).toBe(200000);
  });

  it('should_判直选态_when_agentToDraft_given_profileId与两名单候选', () => {
    // given & when
    const draft = agentToDraft(wire({ model: 'mp-qwen38-flash' }),
      { profileIds: ['mp-qwen38-flash'], catalogIds: ['auto'] });

    // then
    expect(draft.modelOrigin).toBe('profile');
  });

  it('should_保持默认origin_when_agentToDraft_given_未传候选名单', () => {
    // given & when
    const draft = agentToDraft(wire({ model: 'auto' }));

    // then（无候选时不判定，由调用方名单就绪后校准）
    expect(draft.modelOrigin).toBe('profile');
    expect(draft.modelId).toBe('auto');
  });
});
