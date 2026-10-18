// @vitest-environment node
import { describe, expect, it } from 'vitest';
import type { TimelineItem } from './streamingReducer';
import {
  aggregateContentText,
  aggregateTokenUsage,
  collectEventTypes,
  errorTextOf,
  indexToolConfirmations,
  indexToolResults,
  isAwaitingApproval,
  isSessionReadOnly,
  messageTextOf,
  resolveToolPolicies,
  statusFromEvent,
  toolNameOf,
  toolUseIdOf,
} from './workspaceLogic';

/** 构造 buffered 时间线条目。 */
function buffered(id: string, type: string, data: Record<string, unknown> = {}): TimelineItem {
  return { id, type, streaming: false, data: { id, type, ...data } };
}

/** 构造流式占位时间线条目。 */
function streaming(id: string, type: string, text: string): TimelineItem {
  return { id, type, streaming: true, data: { text } };
}

describe('aggregateTokenUsage', () => {
  it('should_sumBoth_when_given_two_model_request_end_events', () => {
    // given
    const items = [
      buffered('evt_1', 'span.model_request_end', { model_usage: { input_tokens: 10, output_tokens: 5 } }),
      buffered('evt_2', 'span.model_request_end', { model_usage: { input_tokens: 3, output_tokens: 7 } }),
    ];

    // when
    const usage = aggregateTokenUsage(items);

    // then
    expect(usage).toEqual({ in: 13, out: 12 });
  });

  it('should_skipStreamingAndNonSpan_when_given_mixed_timeline', () => {
    // given：流式占位与非计量事件不得计入
    const items = [
      streaming('evt_a', 'agent.message', '半截'),
      buffered('evt_b', 'agent.message', { content: [{ type: 'text', text: 'x' }] }),
      buffered('evt_c', 'span.model_request_end', { model_usage: { input_tokens: 2, output_tokens: 4 } }),
    ];

    // when
    const usage = aggregateTokenUsage(items);

    // then
    expect(usage).toEqual({ in: 2, out: 4 });
  });

  it('should_treatInvalidTokensAsZero_when_given_malformed_model_usage', () => {
    // given
    const items = [
      buffered('evt_1', 'span.model_request_end', { model_usage: { input_tokens: -5, output_tokens: 'oops' } }),
      buffered('evt_2', 'span.model_request_end', { model_usage: 'not-an-object' }),
      buffered('evt_3', 'span.model_request_end', {}),
    ];

    // when
    const usage = aggregateTokenUsage(items);

    // then
    expect(usage).toEqual({ in: 0, out: 0 });
  });

  it('should_returnZero_when_given_empty_timeline', () => {
    // given
    const items: TimelineItem[] = [];

    // when
    const usage = aggregateTokenUsage(items);

    // then
    expect(usage).toEqual({ in: 0, out: 0 });
  });
});

describe('statusFromEvent', () => {
  it('should_mapKnownStatus_when_given_session_status_event', () => {
    // given / when / then
    expect(statusFromEvent('session.status_running')).toBe('running');
    expect(statusFromEvent('session.status_idle')).toBe('idle');
    expect(statusFromEvent('session.status_terminated')).toBe('terminated');
  });

  it('should_normalizeRescheduledAlias_when_given_rescheduled_event', () => {
    // given：后端事件词汇 rescheduled，对外状态词汇 rescheduling
    // when
    const mapped = statusFromEvent('session.status_rescheduled');

    // then
    expect(mapped).toBe('rescheduling');
  });

  it('should_returnNull_when_given_non_status_event', () => {
    // given / when / then
    expect(statusFromEvent('agent.message')).toBeNull();
    expect(statusFromEvent('')).toBeNull();
    expect(statusFromEvent(null)).toBeNull();
  });
});

describe('resolveToolPolicies', () => {
  it('should_collectFromToolsetConfigs_when_given_agent_snapshot', () => {
    // given
    const agent = {
      tools: [
        { type: 'agent_toolset_20260401', configs: [{ name: 'Bash', permission_policy: 'always_ask' }, { name: 'Read', permission_policy: 'always_allow' }] },
      ],
    };

    // when
    const policies = resolveToolPolicies(agent);

    // then
    expect(policies).toEqual({ Bash: 'always_ask', Read: 'always_allow' });
  });

  it('should_skipMalformedEntries_when_given_loose_snapshot', () => {
    // given：脏数据项与 custom 工具（无 configs）不参与策略解析
    const agent = {
      tools: [
        null,
        'not-an-object',
        { type: 'custom', name: 'advisor' },
        { type: 'agent_toolset_20260401', configs: [null, { name: '', permission_policy: 'always_ask' }, { name: 'Write', permission_policy: '' }] },
      ],
    };

    // when
    const policies = resolveToolPolicies(agent);

    // then
    expect(policies).toEqual({});
  });

  it('should_returnEmpty_when_given_missing_or_null_tools', () => {
    // given / when / then
    expect(resolveToolPolicies(undefined)).toEqual({});
    expect(resolveToolPolicies({ tools: 'bad' })).toEqual({});
  });
});

describe('isAwaitingApproval', () => {
  it('should_returnTrue_when_ask_policy_running_no_result_no_confirmation', () => {
    // given
    const options = { policy: 'always_ask', status: 'running' as const, hasResult: false, hasConfirmation: false };

    // when / then
    expect(isAwaitingApproval(options)).toBe(true);
  });

  it('should_returnFalse_when_resultAlreadyArrived', () => {
    // given / when / then
    expect(isAwaitingApproval({ policy: 'always_ask', status: 'running', hasResult: true, hasConfirmation: false })).toBe(false);
  });

  it('should_returnFalse_when_userAlreadyConfirmed', () => {
    // given / when / then
    expect(isAwaitingApproval({ policy: 'always_ask', status: 'running', hasResult: false, hasConfirmation: true })).toBe(false);
  });

  it('should_returnFalse_when_policyNotAskOrSessionNotRunning', () => {
    // given / when / then
    expect(isAwaitingApproval({ policy: 'always_allow', status: 'running', hasResult: false, hasConfirmation: false })).toBe(false);
    expect(isAwaitingApproval({ policy: undefined, status: 'running', hasResult: false, hasConfirmation: false })).toBe(false);
    expect(isAwaitingApproval({ policy: 'always_ask', status: 'idle', hasResult: false, hasConfirmation: false })).toBe(false);
  });
});

describe('indexToolResults', () => {
  it('should_mapByToolUseId_when_given_result_events', () => {
    // given
    const items = [
      buffered('evt_r1', 'agent.tool_result', { tool_use_id: 'tu_1', output: 'ok' }),
      buffered('evt_r2', 'agent.mcp_tool_result', { tool_use_id: 'tu_2' }),
      streaming('evt_skip', 'agent.tool_result', ''),
      buffered('evt_none', 'agent.tool_result', {}),
    ];

    // when
    const index = indexToolResults(items);

    // then
    expect(index.size).toBe(2);
    expect(index.get('tu_1')?.id).toBe('evt_r1');
    expect(index.has('tu_2')).toBe(true);
  });
});

describe('indexToolConfirmations', () => {
  it('should_mapByToolUseId_when_given_confirmation_events', () => {
    // given
    const items = [
      buffered('evt_c1', 'user.tool_confirmation', { tool_use_id: 'tu_1', result: 'allow' }),
      buffered('evt_other', 'agent.message', { tool_use_id: 'tu_9' }),
    ];

    // when
    const index = indexToolConfirmations(items);

    // then
    expect(index.size).toBe(1);
    expect(index.get('tu_1')?.data.result).toBe('allow');
    expect(index.has('tu_9')).toBe(false);
  });
});

describe('tool accessors', () => {
  it('should_readNameAndId_when_given_tool_use_event', () => {
    // given
    const item = buffered('evt_u', 'agent.tool_use', { name: 'Bash', tool_use_id: 'tu_1' });

    // when / then
    expect(toolNameOf(item)).toBe('Bash');
    expect(toolUseIdOf(item)).toBe('tu_1');
  });

  it('should_returnEmpty_when_given_missing_fields', () => {
    // given
    const item = buffered('evt_u', 'agent.tool_use', {});

    // when / then
    expect(toolNameOf(item)).toBe('');
    expect(toolUseIdOf(item)).toBe('');
  });
});

describe('message text extraction', () => {
  it('should_readTextBuffer_when_given_streaming_message', () => {
    // given
    const item = streaming('evt_a', 'agent.message', '你好世界');

    // when
    const text = messageTextOf(item);

    // then
    expect(text).toBe('你好世界');
  });

  it('should_aggregateTextBlocks_when_given_buffered_message', () => {
    // given
    const item = buffered('evt_a', 'agent.message', { content: [{ type: 'text', text: '甲' }, { type: 'image', source: { type: 'url', url: 'x' } }, { type: 'text', text: '乙' }] });

    // when
    const text = messageTextOf(item);

    // then
    expect(text).toBe('甲乙');
  });

  it('should_joinOnlyTextBlocks_when_given_content_array', () => {
    // given / when / then
    expect(aggregateContentText([{ type: 'text', text: 'a' }, { type: 'text', text: 'b' }])).toBe('ab');
    expect(aggregateContentText('not-array')).toBe('');
  });
});

describe('errorTextOf', () => {
  it('should_returnMessage_when_given_errorObject', () => {
    // given
    const item = buffered('evt_e', 'session.error', { error: { message: '工具执行失败' } });

    // when / then
    expect(errorTextOf(item)).toBe('工具执行失败');
  });

  it('should_returnFallback_when_given_missing_error', () => {
    // given
    const item = buffered('evt_e', 'session.error', {});

    // when / then
    expect(errorTextOf(item)).toBe('智能体执行失败');
  });
});

describe('collectEventTypes', () => {
  it('should_returnSortedDeduped_when_given_mixed_timeline', () => {
    // given
    const items = [
      buffered('evt_1', 'user.message'),
      buffered('evt_2', 'agent.message'),
      buffered('evt_3', 'user.message'),
      streaming('evt_4', 'span.model_request_end', ''),
      buffered('evt_5', ''),
    ];

    // when
    const types = collectEventTypes(items);

    // then
    expect(types).toEqual(['agent.message', 'span.model_request_end', 'user.message']);
  });
});

describe('isSessionReadOnly', () => {
  it('should_returnTrue_when_given_archived', () => {
    // given / when / then
    expect(isSessionReadOnly({ archived_at: '2026-01-01T00:00:00Z', status: 'idle' })).toBe(true);
  });

  it('should_returnTrue_when_given_terminated', () => {
    // given / when / then
    expect(isSessionReadOnly({ archived_at: null, status: 'terminated' })).toBe(true);
  });

  it('should_returnFalse_when_given_activeOrNull', () => {
    // given / when / then
    expect(isSessionReadOnly({ archived_at: null, status: 'running' })).toBe(false);
    expect(isSessionReadOnly(null)).toBe(false);
  });
});
