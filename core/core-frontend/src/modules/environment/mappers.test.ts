// @vitest-environment node
import { beforeEach, describe, expect, it } from 'vitest';
import type { EnvironmentPackagesWire, EnvironmentWire } from './api';
import {
  ENVIRONMENT_PACKAGE_KEYS,
  MAX_SETUP_SCRIPT_BYTES,
  buildEnvironmentPayload,
  createEmptyEnvironmentForm,
  environmentFormFromWire,
  environmentTypeLabel,
  isArchived,
  normalizePackageEntries,
  packagesSummary,
  utf8ByteLength,
  validateEnvironmentForm,
} from './mappers';
import type { EnvironmentFormState } from './mappers';

/** 后端历史/异常数据可能出现的未知类型值（用于验证表单收敛，绕过联合类型约束）。 */
const UNKNOWN_CONFIG_TYPE = 'self_host' as unknown as EnvironmentFormState['configType'];

/** 六类全空的响应侧 packages（后端固定回显形态）。 */
function emptyPackages(): EnvironmentPackagesWire {
  return { type: 'packages', apt: [], npm: [], pip: [], cargo: [], gem: [], go: [] };
}

/** 构造一个合法的最小云端环境表单。 */
function baseForm(): EnvironmentFormState {
  const form = createEmptyEnvironmentForm();
  form.name = '数据分析环境';
  return form;
}

/** 构造线格式环境对象（用例只覆盖关心的字段）。 */
function wire(overrides: Partial<EnvironmentWire> = {}): EnvironmentWire {
  return {
    id: 'env_1',
    type: 'environment',
    name: '默认云环境',
    description: '',
    config: { type: 'cloud', packages: emptyPackages(), setup_script: null },
    metadata: {},
    archived_at: null,
    created_at: '2026-10-01T10:00:00+08:00',
    updated_at: '2026-10-02T10:00:00+08:00',
    ...overrides,
  };
}

describe('buildEnvironmentPayload packages 形状', () => {
  let form: EnvironmentFormState;

  beforeEach(() => {
    form = baseForm();
  });

  it('should_仅含三键_when_buildEnvironmentPayload_given_三类包均有值', () => {
    // given
    form.apt = ['nginx', 'curl'];
    form.pip = ['pandas==2.1.0'];
    form.npm = ['typescript@5.0.0'];

    // when
    const payload = buildEnvironmentPayload(form);

    // then
    const keys = Object.keys(payload.config?.packages ?? {}).sort();
    expect(keys).toEqual([...ENVIRONMENT_PACKAGE_KEYS].sort());
    expect(payload.config?.packages?.apt).toEqual(['nginx', 'curl']);
  });

  it('should_剔除空键_when_buildEnvironmentPayload_given_仅pip有值', () => {
    // given
    form.apt = [];
    form.pip = ['numpy'];
    form.npm = ['   '];

    // when
    const payload = buildEnvironmentPayload(form);

    // then
    expect(Object.keys(payload.config?.packages ?? {})).toEqual(['pip']);
    expect(payload.config?.packages?.pip).toEqual(['numpy']);
  });

  it('should_不提交packages_when_buildEnvironmentPayload_given_cloud且无任何包', () => {
    // given & when
    const payload = buildEnvironmentPayload(form);

    // then
    expect(payload.config).not.toHaveProperty('packages');
    expect(payload.config?.type).toBe('cloud');
  });

  it('should_剔除packages_when_buildEnvironmentPayload_given_self_hosted残留包配置', () => {
    // given
    form.configType = 'self_hosted';
    form.apt = ['nginx'];
    form.pip = ['numpy'];

    // when
    const payload = buildEnvironmentPayload(form);

    // then
    expect(payload.config).not.toHaveProperty('packages');
    expect(payload.config?.type).toBe('self_hosted');
  });

  it('should_丢弃空白并去重_when_buildEnvironmentPayload_given_包名含空白与重复项', () => {
    // given
    form.pip = [' pandas ', 'pandas', '', 'numpy'];

    // when
    const payload = buildEnvironmentPayload(form);

    // then
    expect(payload.config?.packages?.pip).toEqual(['pandas', 'numpy']);
  });
});

describe('buildEnvironmentPayload 字段装配', () => {
  it('should_trim名称且不提交description_when_buildEnvironmentPayload_given_描述留空', () => {
    // given
    const form = baseForm();
    form.name = '  生产环境  ';
    form.description = '   ';

    // when
    const payload = buildEnvironmentPayload(form);

    // then
    expect(payload.name).toBe('生产环境');
    expect(payload).not.toHaveProperty('description');
  });

  it('should_提交脚本原文_when_buildEnvironmentPayload_given_填写setup_script', () => {
    // given
    const form = baseForm();
    form.setupScript = 'apt update\npip install pandas\n';

    // when
    const payload = buildEnvironmentPayload(form);

    // then
    expect(payload.config?.setup_script).toBe('apt update\npip install pandas\n');
  });

  it('should_不提交setup_script_when_buildEnvironmentPayload_given_脚本为纯空白', () => {
    // given
    const form = baseForm();
    form.setupScript = '  \n  ';

    // when
    const payload = buildEnvironmentPayload(form);

    // then
    expect(payload.config).not.toHaveProperty('setup_script');
  });

  it('should_抛错_when_buildEnvironmentPayload_given_名称为空', () => {
    // given
    const form = baseForm();
    form.name = '';

    // when
    const build = () => buildEnvironmentPayload(form);

    // then
    expect(build).toThrowError(/运行环境名称/);
  });

  it('should_抛错_when_buildEnvironmentPayload_given_setup_script超过64KB字节', () => {
    // given
    const form = baseForm();
    form.setupScript = '中'.repeat(Math.floor(MAX_SETUP_SCRIPT_BYTES / 3) + 1);

    // when
    const build = () => buildEnvironmentPayload(form);

    // then
    expect(build).toThrowError(/64KB/);
  });
});

describe('validateEnvironmentForm 边界', () => {
  it('should_通过_when_validateEnvironmentForm_given_合法cloud表单', () => {
    // given
    const form = baseForm();
    form.pip = ['numpy'];

    // when
    const errors = validateEnvironmentForm(form);

    // then
    expect(errors).toEqual({});
  });

  it('should_报名称必填_when_validateEnvironmentForm_given_名称为空白', () => {
    // given
    const form = baseForm();
    form.name = '  ';

    // when
    const errors = validateEnvironmentForm(form);

    // then
    expect(errors.name).toBe('请输入运行环境名称');
  });

  it('should_报类型非法_when_validateEnvironmentForm_given_未知configType', () => {
    // given
    const form = baseForm();
    form.configType = UNKNOWN_CONFIG_TYPE;

    // when
    const errors = validateEnvironmentForm(form);

    // then
    expect(errors.configType).toBe('请选择环境类型');
  });

  it('should_按字节判定而非字符数_when_validateEnvironmentForm_given_中文脚本刚好在上限内', () => {
    // given
    const form = baseForm();
    form.setupScript = '中'.repeat(Math.floor(MAX_SETUP_SCRIPT_BYTES / 3));

    // when
    const errors = validateEnvironmentForm(form);

    // then
    expect(utf8ByteLength(form.setupScript)).toBeLessThanOrEqual(MAX_SETUP_SCRIPT_BYTES);
    expect(errors.setupScript).toBeUndefined();
  });

  it('should_报包名超长_when_validateEnvironmentForm_given_单个apt条目超过255字符', () => {
    // given
    const form = baseForm();
    form.apt = ['a'.repeat(256)];

    // when
    const errors = validateEnvironmentForm(form);

    // then
    expect(errors.apt).toContain('255');
  });

  it('should_跳过包校验_when_validateEnvironmentForm_given_self_hosted残留超长包名', () => {
    // given
    const form = baseForm();
    form.configType = 'self_hosted';
    form.apt = ['a'.repeat(256)];

    // when
    const errors = validateEnvironmentForm(form);
    const payload = buildEnvironmentPayload(form);

    // then
    expect(errors.apt).toBeUndefined();
    expect(payload.config).not.toHaveProperty('packages');
  });
});

describe('回显与展示辅助', () => {
  it('should_收敛未知类型为cloud_when_environmentFormFromWire_given_后端返回未知type', () => {
    // given
    const env = wire({ config: { type: 'edge', packages: emptyPackages(), setup_script: null } });

    // when
    const form = environmentFormFromWire(env);

    // then
    expect(form.configType).toBe('cloud');
  });

  it('should_回显嵌套config_when_environmentFormFromWire_given_完整响应', () => {
    // given
    const packages: EnvironmentPackagesWire = {
      ...emptyPackages(),
      apt: ['nginx'],
      pip: ['pandas==2.1.0'],
    };
    const env = wire({
      name: '分析环境',
      description: '含依赖',
      config: { type: 'self_hosted', packages, setup_script: 'echo ok' },
    });

    // when
    const form = environmentFormFromWire(env);

    // then
    expect(form.name).toBe('分析环境');
    expect(form.description).toBe('含依赖');
    expect(form.configType).toBe('self_hosted');
    expect(form.apt).toEqual(['nginx']);
    expect(form.pip).toEqual(['pandas==2.1.0']);
    expect(form.setupScript).toBe('echo ok');
  });

  it('should_回空数组_when_environmentFormFromWire_given_响应缺失packages', () => {
    // given
    const env = wire({ config: { type: 'cloud' } });

    // when
    const form = environmentFormFromWire(env);

    // then
    expect(form.apt).toEqual([]);
    expect(form.pip).toEqual([]);
    expect(form.npm).toEqual([]);
  });

  it('should_往返一致_when_environmentFormFromWire_then_buildEnvironmentPayload', () => {
    // given
    const packages: EnvironmentPackagesWire = { ...emptyPackages(), npm: ['typescript@5.0.0'] };
    const env = wire({ config: { type: 'cloud', packages, setup_script: 'npm ci' } });

    // when
    const payload = buildEnvironmentPayload(environmentFormFromWire(env));

    // then
    expect(payload.config?.packages?.npm).toEqual(['typescript@5.0.0']);
    expect(payload.config?.setup_script).toBe('npm ci');
  });

  it('should_仅拼接非空类别_when_packagesSummary_given_三类均有值', () => {
    // given
    const packages: EnvironmentPackagesWire = { ...emptyPackages(), apt: ['nginx', 'curl'], pip: ['numpy'] };
    const env = wire({ config: { type: 'cloud', packages, setup_script: null } });

    // when
    const summary = packagesSummary(env);

    // then
    expect(summary).toBe('apt: nginx, curl; pip: numpy');
  });

  it('should_返回空串_when_packagesSummary_given_全部为空数组', () => {
    // given
    const env = wire();

    // when & then
    expect(packagesSummary(env)).toBe('');
  });

  it('should_以archived_at判定归档_when_isArchived_given_有时间戳与null', () => {
    // given & when & then
    expect(isArchived(wire({ archived_at: '2026-10-03T10:00:00+08:00' }))).toBe(true);
    expect(isArchived(wire({ archived_at: null }))).toBe(false);
  });

  it('should_返回中文标签_when_environmentTypeLabel_given_已知与未知类型', () => {
    // given & when & then
    expect(environmentTypeLabel('cloud')).toBe('云端托管');
    expect(environmentTypeLabel('self_hosted')).toContain('自托管');
    expect(environmentTypeLabel('unknown_type')).toBe('unknown_type');
    expect(environmentTypeLabel(null)).toBe('-');
  });

  it('should_归一空白与重复_when_normalizePackageEntries_given_混合输入', () => {
    // given & when & then
    expect(normalizePackageEntries([' a ', 'a', '', '  ', 'b'])).toEqual(['a', 'b']);
    expect(normalizePackageEntries(undefined)).toEqual([]);
  });
});
