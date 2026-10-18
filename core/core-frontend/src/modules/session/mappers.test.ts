// @vitest-environment node
import { describe, expect, it } from 'vitest';
import type { CreateSessionPayload } from './types';
import {
  DEFAULT_MOUNT_PATH_PREFIX,
  MAX_ENV_ENTRIES,
  MAX_MOUNTED_TOTAL_BYTES,
  buildCreatePayload,
  buildSessionListQuery,
  createEmptySessionForm,
  defaultMountPath,
  estimateResourceBytes,
  formatBytes,
  normalizeEnvVars,
  toRfc3339Bounds,
  truncateSessionId,
  validateMountPath,
  validateResourceQuota,
  validateSessionCreateForm,
} from './mappers';
import type { SessionCreateForm, SessionMountDraft } from './mappers';

/** 已废止的遗留字段集（MUST NOT 出现在创建载荷中）。 */
const LEGACY_FIELDS = ['environment', 'vaults', 'vault_ids', 'memory_store_ids', 'delta_flush_interval_ms'];

/** 查询串 → 参数表（保留同名重复键）。 */
function toParams(query: string): URLSearchParams {
  expect(query.startsWith('?')).toBe(true);
  return new URLSearchParams(query.slice(1));
}

/** 构造一个通过必填校验的最小表单。 */
function baseForm(): SessionCreateForm {
  return { ...createEmptySessionForm(), agentId: 'agent_1', environmentId: 'env_1' };
}

/** 构造挂载草稿项（大小缺省 1KB）。 */
function draft(fileId: string, sizeBytes: number | null = 1024, mountPath?: string): SessionMountDraft {
  return { fileId, sizeBytes, mountPath };
}

describe('buildSessionListQuery 查询串装配', () => {
  it('should_重复提交statuses键_when_buildSessionListQuery_given_多选两种状态', () => {
    // given & when
    const query = buildSessionListQuery({ statuses: ['idle', 'running'] });

    // then
    expect(toParams(query).getAll('statuses[]')).toEqual(['idle', 'running']);
  });

  it('should_拼接全部标量参数_when_buildSessionListQuery_given_筛选与翻页齐备', () => {
    // given & when
    const query = buildSessionListQuery(
      { agentId: 'agent_1', statuses: ['terminated'], includeArchived: true },
      { limit: 20, afterId: 'sess_9', order: 'desc' },
    );

    // then
    const params = toParams(query);
    expect(params.get('agent_id')).toBe('agent_1');
    expect(params.get('include_archived')).toBe('true');
    expect(params.get('limit')).toBe('20');
    expect(params.get('after_id')).toBe('sess_9');
    expect(params.get('order')).toBe('desc');
    expect(params.getAll('statuses[]')).toEqual(['terminated']);
  });

  it('should_省略空筛选项_when_buildSessionListQuery_given_仅翻页参数', () => {
    // given & when
    const query = buildSessionListQuery({ agentId: '  ', statuses: ['', 'idle'], timeRange: [] }, { limit: 5 });

    // then
    const params = toParams(query);
    expect(params.has('agent_id')).toBe(false);
    expect(params.has('created_at[gte]')).toBe(false);
    expect(params.getAll('statuses[]')).toEqual(['idle']);
    expect(params.get('limit')).toBe('5');
  });

  it('should_返回空串_when_buildSessionListQuery_given_无任何条件', () => {
    // given & when & then
    expect(buildSessionListQuery()).toBe('');
    expect(buildSessionListQuery({}, {})).toBe('');
  });

  it('should_提交false_when_buildSessionListQuery_given_显式不含归档', () => {
    // given & when
    const query = buildSessionListQuery({ includeArchived: false });

    // then
    expect(toParams(query).get('include_archived')).toBe('false');
  });

  it('should_补全为UTC时间_when_buildSessionListQuery_given_日期范围', () => {
    // given & when
    const query = buildSessionListQuery({ timeRange: ['2026-10-01', '2026-10-05'] });

    // then
    const params = toParams(query);
    expect(params.get('created_at[gte]')).toBe(new Date('2026-10-01T00:00:00').toISOString());
    expect(params.get('created_at[lte]')).toBe(new Date('2026-10-05T23:59:59').toISOString());
  });
});

describe('toRfc3339Bounds 时间边界归一', () => {
  it('should_原样保留完整时间串_when_toRfc3339Bounds_given_已是RFC3339形态', () => {
    // given & when & then
    expect(toRfc3339Bounds(['2026-10-01T08:00:00Z', '2026-10-02T08:00:00Z'])).toEqual({
      gte: '2026-10-01T08:00:00Z',
      lte: '2026-10-02T08:00:00Z',
    });
  });

  it('should_返回空边界_when_toRfc3339Bounds_given_null与单元素数组', () => {
    // given & when & then
    expect(toRfc3339Bounds(null)).toEqual({ gte: '', lte: '' });
    expect(toRfc3339Bounds(['2026-10-01'])).toEqual({
      gte: new Date('2026-10-01T00:00:00').toISOString(),
      lte: '',
    });
  });
});

describe('validateMountPath 挂载路径形态', () => {
  it('should_通过_when_validateMountPath_given_空值与合法mounts路径', () => {
    // given & when & then
    expect(validateMountPath('')).toBe('');
    expect(validateMountPath(undefined)).toBe('');
    expect(validateMountPath('mounts/data.csv')).toBe('');
    expect(validateMountPath(`${DEFAULT_MOUNT_PATH_PREFIX}file_1`)).toBe('');
    expect(validateMountPath('mounts/a/b/c.txt')).toBe('');
  });

  it('should_拒绝对等路径_when_validateMountPath_given_以斜杠开头', () => {
    // given & when & then
    expect(validateMountPath('/workspace/mounts/a.txt')).toContain('相对路径');
  });

  it('should_拒绝反斜杠_when_validateMountPath_given_windows风格路径', () => {
    // given & when & then
    expect(validateMountPath('mounts\\a.txt')).toContain('反斜杠');
  });

  it('should_拒绝非mounts前缀_when_validateMountPath_given_data目录或mounts本身', () => {
    // given & when & then
    expect(validateMountPath('data/a.txt')).toContain('mounts/');
    expect(validateMountPath('mounts')).toContain('不得等于 mounts');
  });

  it('should_拒绝空路径段_when_validateMountPath_given_连续斜杠或结尾斜杠', () => {
    // given & when & then
    expect(validateMountPath('mounts//a.txt')).toContain('空路径段');
    expect(validateMountPath('mounts/a/')).toContain('空路径段');
  });

  it('should_拒绝当前与上级目录段_when_validateMountPath_given_点或双点段', () => {
    // given & when & then
    expect(validateMountPath('mounts/./a.txt')).toContain('当前目录段');
    expect(validateMountPath('mounts/../escape.txt')).toContain('上级目录段');
  });
});

describe('defaultMountPath 与 truncateSessionId 展示辅助', () => {
  it('should_拼接mounts前缀_when_defaultMountPath_given_文件ID', () => {
    // given & when & then
    expect(defaultMountPath('file_1')).toBe('mounts/file_1');
  });

  it('should_截断长ID并保留首尾_when_truncateSessionId_given_sess_长ID', () => {
    // given & when & then
    expect(truncateSessionId('sess_0123456789abcdef')).toBe('sess_01234…def');
    expect(truncateSessionId('sess_1')).toBe('sess_1');
    expect(truncateSessionId('')).toBe('-');
    expect(truncateSessionId(null)).toBe('-');
  });
});

describe('环境变量归一与配额估算', () => {
  it('should_字符串化数值_when_normalizeEnvVars_given_数字与布尔值', () => {
    // given & when
    const result = normalizeEnvVars({ TIMEOUT: 30, DEBUG: true, NAME: 'ok' });

    // then
    expect(result.error).toBe('');
    expect(result.vars).toEqual({ TIMEOUT: '30', DEBUG: 'true', NAME: 'ok' });
  });

  it('should_拒绝空键与非法名_when_normalizeEnvVars_given_键不合规', () => {
    // given & when & then
    expect(normalizeEnvVars({ '': 'v' }).error).toContain('不能为空');
    expect(normalizeEnvVars({ '1BAD': 'v' }).error).toContain('非法');
    expect(normalizeEnvVars({ 'A-B': 'v' }).error).toContain('非法');
  });

  it('should_拒绝保留名与前缀_when_normalizeEnvVars_given_平台保留变量', () => {
    // given & when & then
    expect(normalizeEnvVars({ WORK_DIR: '/x' }).error).toContain('平台保留');
    expect(normalizeEnvVars({ CAW_TOKEN: 'x' }).error).toContain('平台保留');
  });

  it('should_拒绝非字符串值_when_normalizeEnvVars_given_null与对象值', () => {
    // given & when & then
    expect(normalizeEnvVars({ A: null }).error).toContain('必须为字符串');
    expect(normalizeEnvVars({ A: { b: 1 } as unknown as string }).error).toContain('必须为字符串');
  });

  it('should_拒绝超条目_when_normalizeEnvVars_given_超过64条', () => {
    // given
    const input: Record<string, string> = {};
    for (let i = 0; i < MAX_ENV_ENTRIES + 1; i += 1) {
      input[`V_${i}`] = String(i);
    }

    // when & then
    expect(normalizeEnvVars(input).error).toContain('64');
  });

  it('should_返回空对象_when_normalizeEnvVars_given_空输入', () => {
    // given & when & then
    expect(normalizeEnvVars(undefined)).toEqual({ vars: {}, error: '' });
    expect(normalizeEnvVars({})).toEqual({ vars: {}, error: '' });
  });

  it('should_累计字节并忽略非法值_when_estimateResourceBytes_given_混合大小', () => {
    // given
    const rows = [draft('file_1', 1024), draft('file_2', null), draft('file_3', -5), draft('file_4', 2048)];

    // when & then
    expect(estimateResourceBytes(rows)).toBe(3072);
    expect(validateResourceQuota(rows)).toBe('');
  });

  it('should_报超限_when_validateResourceQuota_given_总量超500MB', () => {
    // given
    const rows = [draft('file_1', MAX_MOUNTED_TOTAL_BYTES + 1)];

    // when & then
    expect(validateResourceQuota(rows)).toContain('500MB');
  });

  it('should_按1024进制展示_when_formatBytes_given_字节/MB/非法值', () => {
    // given & when & then
    expect(formatBytes(0)).toBe('0 B');
    expect(formatBytes(1024)).toBe('1 KB');
    expect(formatBytes(1536)).toBe('1.5 KB');
    expect(formatBytes(500 * 1024 * 1024)).toBe('500 MB');
    expect(formatBytes(-1)).toBe('0 B');
  });

  it('should_边界恰好等于上限不拦截_when_validateResourceQuota_given_总量等于500MB', () => {
    // given
    const rows = [draft('file_1', MAX_MOUNTED_TOTAL_BYTES)];

    // when & then
    expect(validateResourceQuota(rows)).toBe('');
  });
});

describe('buildCreatePayload 载荷装配', () => {
  it('should_仅含契约字段_when_buildCreatePayload_given_完整表单', () => {
    // given
    const form = baseForm();
    form.title = '  数据分析会话  ';
    form.envVars = { TZ: 'Asia/Shanghai' };
    form.resources = [{ fileId: 'file_1', sizeBytes: 10, mountPath: 'mounts/a.csv' }];
    form.initialMessage = '帮我读一下挂载文件';

    // when
    const payload = buildCreatePayload(form);

    // then
    expect(Object.keys(payload).sort()).toEqual(
      ['agent', 'environment_id', 'environment_variables', 'resources', 'title'].sort(),
    );
    expect(payload.agent).toBe('agent_1');
    expect(payload.title).toBe('数据分析会话');
    expect(payload.resources).toEqual([{ type: 'file', file_id: 'file_1', mount_path: 'mounts/a.csv' }]);
    expect(payload.environment_variables).toEqual({ TZ: 'Asia/Shanghai' });
  });

  it('should_不含废止字段_when_buildCreatePayload_given_最小表单', () => {
    // given & when
    const payload = buildCreatePayload(baseForm());

    // then
    const keys = Object.keys(payload);
    expect(keys).toEqual(['agent', 'environment_id']);
    for (const legacy of LEGACY_FIELDS) {
      expect(keys).not.toContain(legacy);
    }
  });

  it('should_省略mount_path_when_buildCreatePayload_given_挂载路径留空', () => {
    // given
    const form = baseForm();
    form.resources = [draft('file_1', 10, '  ')];

    // when
    const payload = buildCreatePayload(form);

    // then
    expect(payload.resources).toEqual([{ type: 'file', file_id: 'file_1' }]);
  });

  it('should_抛错_when_buildCreatePayload_given_未选智能体或环境', () => {
    // given
    const noAgent = { ...baseForm(), agentId: '' };
    const noEnv = { ...baseForm(), environmentId: '' };

    // when & then
    expect(() => buildCreatePayload(noAgent)).toThrowError(/智能体/);
    expect(() => buildCreatePayload(noEnv)).toThrowError(/环境/);
  });

  it('should_抛错_when_buildCreatePayload_given_挂载路径非法', () => {
    // given
    const form = baseForm();
    form.resources = [draft('file_1', 10, 'data/a.txt')];

    // when & then
    expect(() => buildCreatePayload(form)).toThrowError(/mounts\//);
  });

  it('should_抛错_when_buildCreatePayload_given_挂载总量超500MB', () => {
    // given
    const form = baseForm();
    form.resources = [draft('file_1', MAX_MOUNTED_TOTAL_BYTES), draft('file_2', 1)];

    // when & then
    expect(() => buildCreatePayload(form)).toThrowError(/500MB/);
  });

  it('should_抛错_when_buildCreatePayload_given_环境变量名非法', () => {
    // given
    const form = baseForm();
    form.envVars = { 'BAD-NAME': 'v' };

    // when & then
    expect(() => buildCreatePayload(form)).toThrowError(/非法/);
  });

  it('should_与契约类型兼容_when_buildCreatePayload_then_赋值CreateSessionPayload', () => {
    // given
    const form = baseForm();
    form.resources = [draft('file_1', 10, defaultMountPath('file_1'))];

    // when
    const payload: CreateSessionPayload = buildCreatePayload(form);

    // then
    expect(payload.environment_id).toBe('env_1');
  });
});

describe('validateSessionCreateForm 必填与拦截', () => {
  it('should_返回空错误_when_validateSessionCreateForm_given_合法表单', () => {
    // given
    const form = baseForm();
    form.envVars = { TZ: 'UTC' };
    form.resources = [draft('file_1', 1024, 'mounts/a.txt')];

    // when & then
    expect(validateSessionCreateForm(form)).toEqual({});
  });

  it('should_报必填_when_validateSessionCreateForm_given_智能体与环境均空', () => {
    // given & when
    const errors = validateSessionCreateForm(createEmptySessionForm());

    // then
    expect(errors.agentId).toBe('请选择智能体');
    expect(errors.environmentId).toContain('cloud');
  });

  it('should_优先报路径错误_when_validateSessionCreateForm_given_路径非法且总量超限', () => {
    // given
    const form = baseForm();
    form.resources = [draft('file_1', MAX_MOUNTED_TOTAL_BYTES * 2, 'bad/path')];

    // when
    const errors = validateSessionCreateForm(form);

    // then
    expect(errors.resources).toContain('mounts/');
    expect(errors.envVars).toBeUndefined();
  });
});
