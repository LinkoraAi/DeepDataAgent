import { ENVIRONMENT_TYPES } from '@/shared/enums/dict';
import type {
  EnvironmentConfigPayload,
  EnvironmentConfigType,
  EnvironmentPackageKey,
  EnvironmentPackagesPayload,
  EnvironmentPayload,
  EnvironmentWire,
} from './api';

/**
 * 运行环境表单草稿（config 结构在表单侧平铺，装配时还原为嵌套对象）。
 */
export type EnvironmentFormState = {
  /** 名称（owner 内唯一，必填） */
  name: string;
  /** 描述（可空） */
  description: string;
  /** 环境类型（cloud / self_hosted） */
  configType: EnvironmentConfigType;
  /** Debian/Ubuntu 系统包（仅 cloud） */
  apt: string[];
  /** Python 包（仅 cloud） */
  pip: string[];
  /** Node.js 包（仅 cloud） */
  npm: string[];
  /** 准备阶段 shell 脚本（≤64KB） */
  setupScript: string;
};

/** 表单校验错误集合：字段名 → 中文错误文案；空对象表示校验通过。 */
export type EnvironmentFormErrors = Partial<Record<keyof EnvironmentFormState, string>>;

/** 请求侧允许的包管理器键（后端 EnvironmentCommandConvert 仅此三键，其余 400） */
export const ENVIRONMENT_PACKAGE_KEYS: ReadonlyArray<'apt' | 'pip' | 'npm'> = ['apt', 'pip', 'npm'];

/** setup_script 字节上限（与后端 EnvironmentConfig.MAX_SETUP_SCRIPT_BYTES 同口径） */
export const MAX_SETUP_SCRIPT_BYTES = 64 * 1024;

/** 云端托管类型值 */
export const CLOUD_TYPE: EnvironmentConfigType = 'cloud';

/** 新建态空表单（默认云端托管）。 */
export function createEmptyEnvironmentForm(): EnvironmentFormState {
  return {
    name: '',
    description: '',
    configType: CLOUD_TYPE,
    apt: [],
    pip: [],
    npm: [],
    setupScript: '',
  };
}

/** 计算字符串 UTF-8 字节数（setup_script 上限按字节判定，非字符数）。 */
export function utf8ByteLength(text: string): number {
  return new TextEncoder().encode(text).length;
}

/** 包名条目归一：trim、丢弃空白项、按原顺序去重。 */
export function normalizePackageEntries(entries: string[] | undefined): string[] {
  const out: string[] = [];
  for (const entry of entries ?? []) {
    const text = String(entry ?? '').trim();
    if (text && !out.includes(text)) {
      out.push(text);
    }
  }
  return out;
}

/** 线格式对象 → 表单草稿回显（未知类型收敛为 cloud；packages 缺失回空数组）。 */
export function environmentFormFromWire(env: EnvironmentWire): EnvironmentFormState {
  const packages = env.config?.packages;
  const type = env.config?.type;
  return {
    name: env.name ?? '',
    description: env.description ?? '',
    configType: isKnownConfigType(type) ? type : CLOUD_TYPE,
    apt: normalizePackageEntries(packages?.apt),
    pip: normalizePackageEntries(packages?.pip),
    npm: normalizePackageEntries(packages?.npm),
    setupScript: env.config?.setup_script ?? '',
  };
}

/** 类型值是否为后端支持的 cloud / self_hosted。 */
function isKnownConfigType(value: string | null | undefined): value is EnvironmentConfigType {
  return value === 'cloud' || value === 'self_hosted';
}

/** 环境类型 → 中文标签（未知值原样展示）。 */
export function environmentTypeLabel(type: string | null | undefined): string {
  if (!type) {
    return '-';
  }
  return ENVIRONMENT_TYPES.find((item) => item.value === type)?.label ?? type;
}

/** 是否已归档（后端仅以 archived_at 表达，无布尔字段）。 */
export function isArchived(env: Pick<EnvironmentWire, 'archived_at'>): boolean {
  return Boolean(env.archived_at);
}

/** packages 摘要（列表列用）：形如 "apt: nginx, curl; pip: pandas==2.1.0"；无预装项返回空串。 */
export function packagesSummary(env: EnvironmentWire): string {
  const packages = env.config?.packages;
  if (!packages) {
    return '';
  }
  const parts: string[] = [];
  for (const key of ENVIRONMENT_PACKAGE_KEYS) {
    const entries = normalizePackageEntries(packages[key]);
    if (entries.length) {
      parts.push(`${key}: ${entries.join(', ')}`);
    }
  }
  return parts.join('; ');
}

/**
 * 表单校验（纯函数，覆盖后端 EnvironmentConfig / EnvironmentPackages 不变量）。
 * @param form 表单草稿
 * @returns 字段级错误集合；空对象表示通过
 */
export function validateEnvironmentForm(form: EnvironmentFormState): EnvironmentFormErrors {
  const errors: EnvironmentFormErrors = {};
  if (!form.name.trim()) {
    errors.name = '请输入运行环境名称';
  }
  if (!isKnownConfigType(form.configType)) {
    errors.configType = '请选择环境类型';
  }
  const bytes = utf8ByteLength(form.setupScript);
  if (bytes > MAX_SETUP_SCRIPT_BYTES) {
    errors.setupScript = `setup 脚本 UTF-8 字节数 ${bytes} 超过上限 ${MAX_SETUP_SCRIPT_BYTES}（64KB）`;
  }
  const invalidEntry = form.configType === CLOUD_TYPE ? findInvalidPackageEntry(form) : null;
  if (invalidEntry) {
    errors[invalidEntry.key] = invalidEntry.message;
  }
  return errors;
}

/** 定位首个非法包名条目（长度上限 255，与后端同口径；空白条目在归一时静默丢弃）。 */
function findInvalidPackageEntry(form: EnvironmentFormState): { key: EnvironmentPackageKey; message: string } | null {
  for (const key of ENVIRONMENT_PACKAGE_KEYS) {
    for (const entry of form[key]) {
      if (entry.length > 255) {
        return { key, message: `${key} 包名长度不能超过 255 个字符` };
      }
    }
  }
  return null;
}

/** 取首条错误文案（装配函数的防御性拦截用）。 */
function firstError(errors: EnvironmentFormErrors): string | undefined {
  for (const message of Object.values(errors)) {
    return message;
  }
  return undefined;
}

/**
 * 表单草稿 → 创建/更新载荷（纯函数）。
 * <p>规则：packages MUST 仅含 apt/pip/npm 三键且只提交非空键；
 * self_hosted MUST NOT 提交 packages（表单残留的包配置在此剔除）；
 * setup_script 空白按缺省（不提交该键）处理；description 空白不提交。</p>
 * @param form 表单草稿
 * @returns 后端请求载荷
 * @throws Error 校验未通过时抛首条错误
 */
export function buildEnvironmentPayload(form: EnvironmentFormState): EnvironmentPayload {
  const errors = validateEnvironmentForm(form);
  const first = firstError(errors);
  if (first) {
    throw new Error(first);
  }

  const config: EnvironmentConfigPayload = { type: form.configType };
  if (form.configType === CLOUD_TYPE) {
    const packages: EnvironmentPackagesPayload = {};
    for (const key of ENVIRONMENT_PACKAGE_KEYS) {
      const entries = normalizePackageEntries(form[key]);
      if (entries.length) {
        packages[key] = entries;
      }
    }
    if (Object.keys(packages).length) {
      config.packages = packages;
    }
  }
  if (form.setupScript.trim()) {
    config.setup_script = form.setupScript;
  }

  const payload: EnvironmentPayload = {
    name: form.name.trim(),
    config,
  };
  if (form.description.trim()) {
    payload.description = form.description.trim();
  }
  return payload;
}
