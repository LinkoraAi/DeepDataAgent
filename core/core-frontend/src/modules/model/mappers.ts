import { API_FORMATS, MODEL_TYPES } from '@/shared/enums/dict';
import {
  MODEL_TYPE_CHAT_LLM,
  MODEL_TYPE_CHAT_VLM,
  MODEL_TYPE_EMBEDDING,
  MODEL_TYPE_RERANK,
} from './api';
import type { ModelProfilePayload, ModelProfileResponse } from './api';

/**
 * 模型配置表单草稿（表单态一律用字符串/可空数字，装配与校验由此处的纯函数承担）。
 * 与后端 Create/UpdateModelProfileRequest 字段一一对应；credential/clearCredential 仅前端交互态。
 */
export type ModelFormState = {
  /** 显示名称（全局唯一，≤32） */
  displayName: string;
  /** 描述（≤500） */
  description: string;
  /** API 格式（大写枚举名：OPENAI/BAILIAN/AGENTSCOPE/OTHER） */
  apiFormat: string;
  /** API 端点 URL（≤512） */
  apiEndpointUrl: string;
  /** 模型名称（≤128） */
  modelName: string;
  /** 凭证明文（永不回显；编辑态留空表示不修改） */
  credential: string;
  /** 显式"清空凭证"开关（仅编辑态生效，勾选后提交 credential 空串） */
  clearCredential: boolean;
  /** 模型系列（≤64） */
  modelSeries: string;
  /** 输入上下文窗口（≥0，可空） */
  contextWindowInput: number | null;
  /** 输出上下文窗口（≥0，可空） */
  contextWindowOutput: number | null;
  /** 工具调用轮次（正整数，可空=沿用后端默认） */
  toolCallRounds: number | null;
  /** 模型类型线格式编码（1=对话、2=嵌入） */
  modelType: number;
  /** 向量维度（嵌入类型必填正整数） */
  vectorDimension: number | null;
};

/** 表单校验错误集合：字段名 → 中文错误文案；空对象表示校验通过。 */
export type ModelFormErrors = Partial<Record<keyof ModelFormState, string>>;

/** 显示名称长度上限（与后端 ModelProfile 同口径） */
export const MAX_DISPLAY_NAME_LENGTH = 32;

/** 描述长度上限 */
export const MAX_DESCRIPTION_LENGTH = 500;

/** 端点 URL 长度上限 */
export const MAX_ENDPOINT_URL_LENGTH = 512;

/** 模型名称长度上限 */
export const MAX_MODEL_NAME_LENGTH = 128;

/** 模型系列长度上限 */
export const MAX_MODEL_SERIES_LENGTH = 64;

/**
 * 显示名称格式：中文或英文字母开头，其后允许中文、英文字母、数字、下划线、连字符（总长 1~32）。
 * 与后端 {@code ^[\\p{IsHan}a-zA-Z][\\p{IsHan}a-zA-Z0-9_\\-]{0,31}$} 等价。
 */
export const DISPLAY_NAME_PATTERN = /^[\p{Script=Han}A-Za-z][\p{Script=Han}A-Za-z0-9_-]{0,31}$/u;

/** 模型类型编码 → 列表用简短中文标签 */
export const MODEL_TYPE_LABELS: Record<number, string> = {
  [MODEL_TYPE_CHAT_LLM]: '对话LLM',
  [MODEL_TYPE_CHAT_VLM]: '视觉对话',
  [MODEL_TYPE_EMBEDDING]: '嵌入',
  [MODEL_TYPE_RERANK]: '重排',
};

/** 新建态空表单（默认对话LLM + OpenAI 格式，与后端缺省口径 1 一致）。 */
export function createEmptyModelForm(): ModelFormState {
  return {
    displayName: '',
    description: '',
    apiFormat: API_FORMATS[0] ?? 'OPENAI',
    apiEndpointUrl: '',
    modelName: '',
    credential: '',
    clearCredential: false,
    modelSeries: '',
    contextWindowInput: null,
    contextWindowOutput: null,
    toolCallRounds: null,
    modelType: MODEL_TYPE_CHAT_LLM,
    vectorDimension: null,
  };
}

/** 响应 → 表单草稿回显（凭证永不回显，恒为空串且未勾选清空）。 */
export function modelProfileFormFromResponse(profile: ModelProfileResponse): ModelFormState {
  return {
    displayName: profile.displayName ?? '',
    description: profile.description ?? '',
    apiFormat: profile.apiFormat ?? '',
    apiEndpointUrl: profile.apiEndpointUrl ?? '',
    modelName: profile.modelName ?? '',
    credential: '',
    clearCredential: false,
    modelSeries: profile.modelSeries ?? '',
    contextWindowInput: profile.contextWindowInput ?? null,
    contextWindowOutput: profile.contextWindowOutput ?? null,
    toolCallRounds: profile.toolCallRounds ?? null,
    modelType: profile.modelType ?? MODEL_TYPE_CHAT_LLM,
    vectorDimension: profile.vectorDimension ?? null,
  };
}

/** 模型类型编码 → 中文标签（未知编码原样字符串化）。 */
export function modelTypeLabel(code: number | null | undefined): string {
  if (code === undefined || code === null) {
    return '-';
  }
  return MODEL_TYPE_LABELS[code] ?? String(code);
}

/** 是否嵌入类型。 */
function isEmbeddingType(form: ModelFormState): boolean {
  return form.modelType === MODEL_TYPE_EMBEDDING;
}

/** 取正整数（非正整数/非有限值返回 null，用于装配时剔除无效值）。 */
function toPositiveInt(value: number | null): number | null {
  if (value === null || !Number.isFinite(value)) {
    return null;
  }
  const int = Math.trunc(value);
  return int > 0 ? int : null;
}

/** 取非负整数（0 合法；负数/非有限值返回 null）。 */
function toNonNegativeInt(value: number | null): number | null {
  if (value === null || !Number.isFinite(value)) {
    return null;
  }
  const int = Math.trunc(value);
  return int >= 0 ? int : null;
}

/**
 * 表单校验（纯函数，覆盖后端 ModelProfile 紧凑构造器的全部不变量）。
 * @param form 表单草稿
 * @returns 字段级错误集合；空对象表示通过
 */
export function validateModelForm(form: ModelFormState): ModelFormErrors {
  const errors: ModelFormErrors = {};
  const displayName = form.displayName.trim();
  if (!displayName) {
    errors.displayName = '请输入显示名称';
  } else if (displayName.length > MAX_DISPLAY_NAME_LENGTH) {
    errors.displayName = `显示名称不能超过${MAX_DISPLAY_NAME_LENGTH}个字符`;
  } else if (!DISPLAY_NAME_PATTERN.test(displayName)) {
    errors.displayName = '须以中文或英文字母开头，仅允许中文、英文字母、数字、下划线与连字符';
  }
  if (form.description.trim().length > MAX_DESCRIPTION_LENGTH) {
    errors.description = `描述不能超过${MAX_DESCRIPTION_LENGTH}个字符`;
  }
  if (!API_FORMATS.includes(form.apiFormat)) {
    errors.apiFormat = '请选择 API 格式';
  }
  const endpoint = form.apiEndpointUrl.trim();
  if (!endpoint) {
    errors.apiEndpointUrl = '请输入 API 端点 URL';
  } else if (endpoint.length > MAX_ENDPOINT_URL_LENGTH) {
    errors.apiEndpointUrl = `API 端点 URL 不能超过${MAX_ENDPOINT_URL_LENGTH}个字符`;
  }
  const modelName = form.modelName.trim();
  if (!modelName) {
    errors.modelName = '请输入模型名称';
  } else if (modelName.length > MAX_MODEL_NAME_LENGTH) {
    errors.modelName = `模型名称不能超过${MAX_MODEL_NAME_LENGTH}个字符`;
  }
  if (form.modelSeries.trim().length > MAX_MODEL_SERIES_LENGTH) {
    errors.modelSeries = `模型系列不能超过${MAX_MODEL_SERIES_LENGTH}个字符`;
  }
  if (!MODEL_TYPES.some((item) => item.value === form.modelType)) {
    errors.modelType = '请选择模型类型';
  }
  if (isEmbeddingType(form) && toPositiveInt(form.vectorDimension) === null) {
    errors.vectorDimension = '向量嵌入模型必须配置正整数向量维度';
  }
  if (form.contextWindowInput !== null && toNonNegativeInt(form.contextWindowInput) === null) {
    errors.contextWindowInput = '输入上下文窗口不能为负数';
  }
  if (form.contextWindowOutput !== null && toNonNegativeInt(form.contextWindowOutput) === null) {
    errors.contextWindowOutput = '输出上下文窗口不能为负数';
  }
  if (form.toolCallRounds !== null && toPositiveInt(form.toolCallRounds) === null) {
    errors.toolCallRounds = '工具调用轮次必须为正整数';
  }
  if (form.clearCredential && form.credential) {
    errors.credential = '已勾选清空凭证时不能再填写新凭证';
  }
  return errors;
}

/** 取首条错误文案（装配函数的防御性拦截用）。 */
function firstError(errors: ModelFormErrors): string | undefined {
  for (const message of Object.values(errors)) {
    return message;
  }
  return undefined;
}

/**
 * 表单草稿 → 创建/更新载荷（纯函数）。
 *
 * 凭证三态：
 * - 新建且未填写：载荷 MUST NOT 含 credential 键（后端视为无凭证）；
 * - 编辑且留空：载荷不含 credential 键（后端保留原凭证）；
 * - 编辑且勾选清空：credential 为空串（后端清除）；
 * - 填写新值：credential 原样提交（后端重新加密，不做 trim）。
 *
 * @param form 表单草稿
 * @param isEdit 是否为编辑（更新）场景
 * @returns 后端请求载荷（可空字段缺省时不出现在对象中）
 * @throws Error 校验未通过时抛首条错误（嵌入类型缺向量维度亦在此拦截）
 */
export function buildModelProfilePayload(form: ModelFormState, isEdit: boolean): ModelProfilePayload {
  const errors = validateModelForm(form);
  const first = firstError(errors);
  if (first) {
    throw new Error(first);
  }

  const payload: ModelProfilePayload = {
    displayName: form.displayName.trim(),
    apiFormat: form.apiFormat,
    apiEndpointUrl: form.apiEndpointUrl.trim(),
    modelName: form.modelName.trim(),
    modelType: form.modelType,
  };

  const description = form.description.trim();
  if (description) {
    payload.description = description;
  }
  const modelSeries = form.modelSeries.trim();
  if (modelSeries) {
    payload.modelSeries = modelSeries;
  }
  const contextWindowInput = toNonNegativeInt(form.contextWindowInput);
  if (contextWindowInput !== null) {
    payload.contextWindowInput = contextWindowInput;
  }
  const contextWindowOutput = toNonNegativeInt(form.contextWindowOutput);
  if (contextWindowOutput !== null) {
    payload.contextWindowOutput = contextWindowOutput;
  }
  const toolCallRounds = toPositiveInt(form.toolCallRounds);
  if (toolCallRounds !== null) {
    payload.toolCallRounds = toolCallRounds;
  }
  if (isEmbeddingType(form)) {
    payload.vectorDimension = toPositiveInt(form.vectorDimension) ?? undefined;
  }
  if (isEdit && form.clearCredential) {
    payload.credential = '';
  } else if (form.credential) {
    payload.credential = form.credential;
  }
  return payload;
}
