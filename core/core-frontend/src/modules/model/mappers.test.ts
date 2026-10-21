// @vitest-environment node
import { beforeEach, describe, expect, it } from 'vitest';
import {
  MODEL_TYPE_CHAT_LLM,
  MODEL_TYPE_CHAT_VLM,
  MODEL_TYPE_EMBEDDING,
  MODEL_TYPE_RERANK,
} from './api';
import type { ModelProfileResponse } from './api';
import {
  buildModelProfilePayload,
  createEmptyModelForm,
  modelProfileFormFromResponse,
  modelTypeLabel,
  validateModelForm,
} from './mappers';
import type { ModelFormState } from './mappers';

/** 构造一个合法的最小对话模型表单（各用例只覆盖自己关心的字段）。 */
function baseForm(): ModelFormState {
  const form = createEmptyModelForm();
  form.displayName = '生产对话模型';
  form.apiEndpointUrl = 'https://api.example.com/v1';
  form.modelName = 'gpt-4o';
  return form;
}

describe('buildModelProfilePayload 凭证三态', () => {
  let form: ModelFormState;

  beforeEach(() => {
    form = baseForm();
  });

  it('should_删除credential键_when_buildModelProfilePayload_given_新建且凭证留空', () => {
    // given
    form.credential = '';

    // when
    const payload = buildModelProfilePayload(form, false);

    // then
    expect(payload).not.toHaveProperty('credential');
  });

  it('should_原样提交凭证_when_buildModelProfilePayload_given_新建且填写凭证', () => {
    // given
    form.credential = '  sk-abcdef  ';

    // when
    const payload = buildModelProfilePayload(form, false);

    // then
    expect(payload.credential).toBe('  sk-abcdef  ');
  });

  it('should_删除credential键_when_buildModelProfilePayload_given_编辑且凭证留空未勾选清空', () => {
    // given
    form.credential = '';
    form.clearCredential = false;

    // when
    const payload = buildModelProfilePayload(form, true);

    // then
    expect(payload).not.toHaveProperty('credential');
  });

  it('should_提交空串_when_buildModelProfilePayload_given_编辑且勾选清空凭证', () => {
    // given
    form.clearCredential = true;

    // when
    const payload = buildModelProfilePayload(form, true);

    // then
    expect(payload).toHaveProperty('credential', '');
  });

  it('should_提交新值_when_buildModelProfilePayload_given_编辑且填写新凭证', () => {
    // given
    form.credential = 'sk-new-token';

    // when
    const payload = buildModelProfilePayload(form, true);

    // then
    expect(payload.credential).toBe('sk-new-token');
  });

  it('should_抛错_when_buildModelProfilePayload_given_同时填写新凭证与勾选清空', () => {
    // given
    form.credential = 'sk-new-token';
    form.clearCredential = true;

    // when
    const build = () => buildModelProfilePayload(form, true);

    // then
    expect(build).toThrowError(/清空凭证/);
  });
});

describe('buildModelProfilePayload 字段装配', () => {
  it('should_剔除空白可选字段并trim_when_buildModelProfilePayload_given_仅必填项', () => {
    // given
    const form = baseForm();
    form.description = '   ';
    form.modelSeries = '';
    form.contextWindowInput = null;
    form.contextWindowOutput = null;
    form.toolCallRounds = null;

    // when
    const payload = buildModelProfilePayload(form, false);

    // then
    expect(payload).not.toHaveProperty('description');
    expect(payload).not.toHaveProperty('modelSeries');
    expect(payload).not.toHaveProperty('contextWindowInput');
    expect(payload).not.toHaveProperty('contextWindowOutput');
    expect(payload).not.toHaveProperty('toolCallRounds');
    expect(payload).not.toHaveProperty('vectorDimension');
    expect(payload.displayName).toBe('生产对话模型');
    expect(payload.apiFormat).toBe('OPENAI');
    expect(payload.modelType).toBe(MODEL_TYPE_CHAT_LLM);
  });

  it('should_装配全部数值字段_when_buildModelProfilePayload_given_合法填写', () => {
    // given
    const form = baseForm();
    form.description = '线上主力模型';
    form.modelSeries = 'gpt-4o';
    form.contextWindowInput = 0;
    form.contextWindowOutput = 128000;
    form.toolCallRounds = 30;

    // when
    const payload = buildModelProfilePayload(form, false);

    // then
    expect(payload.description).toBe('线上主力模型');
    expect(payload.modelSeries).toBe('gpt-4o');
    expect(payload.contextWindowInput).toBe(0);
    expect(payload.contextWindowOutput).toBe(128000);
    expect(payload.toolCallRounds).toBe(30);
  });

  it('should_写入向量维度_when_buildModelProfilePayload_given_嵌入类型且维度合法', () => {
    // given
    const form = baseForm();
    form.modelType = MODEL_TYPE_EMBEDDING;
    form.vectorDimension = 1536;

    // when
    const payload = buildModelProfilePayload(form, false);

    // then
    expect(payload.modelType).toBe(MODEL_TYPE_EMBEDDING);
    expect(payload.vectorDimension).toBe(1536);
  });

  it('should_抛错_when_buildModelProfilePayload_given_嵌入类型缺少向量维度', () => {
    // given
    const form = baseForm();
    form.modelType = MODEL_TYPE_EMBEDDING;
    form.vectorDimension = null;

    // when
    const build = () => buildModelProfilePayload(form, false);

    // then
    expect(build).toThrowError(/向量维度/);
  });

  it('should_忽略向量维度_when_buildModelProfilePayload_given_对话类型误填维度', () => {
    // given
    const form = baseForm();
    form.modelType = MODEL_TYPE_CHAT_LLM;
    form.vectorDimension = 1024;

    // when
    const payload = buildModelProfilePayload(form, false);

    // then
    expect(payload).not.toHaveProperty('vectorDimension');
  });
});

describe('validateModelForm 边界与非法参数', () => {
  it('should_通过_when_validateModelForm_given_合法最小表单', () => {
    // given
    const form = baseForm();

    // when
    const errors = validateModelForm(form);

    // then
    expect(errors).toEqual({});
  });

  it.each([
    ['1开头名称', '1model'],
    ['下划线开头', '_model'],
    ['连字符开头', '-model'],
    ['含空格', 'my model'],
    ['含特殊字符', 'model@prod'],
  ])('should_报显示名称错误_when_validateModelForm_given_非法格式(%s)', (_name, displayName) => {
    // given
    const form = baseForm();
    form.displayName = displayName;

    // when
    const errors = validateModelForm(form);

    // then
    expect(errors.displayName).toContain('中文或英文字母开头');
  });

  it('should_报名称超长_when_validateModelForm_given_显示名称超过32字符', () => {
    // given
    const form = baseForm();
    form.displayName = 'a'.repeat(33);

    // when
    const errors = validateModelForm(form);

    // then
    expect(errors.displayName).toContain('32');
  });

  it('should_通过_when_validateModelForm_given_显示名称恰好32字符', () => {
    // given
    const form = baseForm();
    form.displayName = 'a'.repeat(32);

    // when
    const errors = validateModelForm(form);

    // then
    expect(errors.displayName).toBeUndefined();
  });

  it('should_报空名称_when_validateModelForm_given_显示名称为空白', () => {
    // given
    const form = baseForm();
    form.displayName = '   ';

    // when
    const errors = validateModelForm(form);

    // then
    expect(errors.displayName).toBe('请输入显示名称');
  });

  it('should_报格式错误_when_validateModelForm_given_apiFormat为小写', () => {
    // given
    const form = baseForm();
    form.apiFormat = 'openai';

    // when
    const errors = validateModelForm(form);

    // then
    expect(errors.apiFormat).toBe('请选择 API 格式');
  });

  it('should_报描述超长_when_validateModelForm_given_描述501字符', () => {
    // given
    const form = baseForm();
    form.description = 'x'.repeat(501);

    // when
    const errors = validateModelForm(form);

    // then
    expect(errors.description).toContain('500');
  });

  it('should_报端点超长_when_validateModelForm_given_端点URL513字符', () => {
    // given
    const form = baseForm();
    form.apiEndpointUrl = 'h'.repeat(513);

    // when
    const errors = validateModelForm(form);

    // then
    expect(errors.apiEndpointUrl).toContain('512');
  });

  it('should_报模型名超长_when_validateModelForm_given_模型名129字符', () => {
    // given
    const form = baseForm();
    form.modelName = 'm'.repeat(129);

    // when
    const errors = validateModelForm(form);

    // then
    expect(errors.modelName).toContain('128');
  });

  it('should_报系列超长_when_validateModelForm_given_模型系列65字符', () => {
    // given
    const form = baseForm();
    form.modelSeries = 's'.repeat(65);

    // when
    const errors = validateModelForm(form);

    // then
    expect(errors.modelSeries).toContain('64');
  });

  it('should_报类型非法_when_validateModelForm_given_modelType为未知编码', () => {
    // given
    const form = baseForm();
    form.modelType = 9;

    // when
    const errors = validateModelForm(form);

    // then
    expect(errors.modelType).toBe('请选择模型类型');
  });

  it('should_允许零值_when_validateModelForm_given_上下文窗口为0', () => {
    // given
    const form = baseForm();
    form.contextWindowInput = 0;
    form.contextWindowOutput = 0;

    // when
    const errors = validateModelForm(form);

    // then
    expect(errors.contextWindowInput).toBeUndefined();
    expect(errors.contextWindowOutput).toBeUndefined();
  });

  it('should_报负数_when_validateModelForm_given_上下文窗口为负', () => {
    // given
    const form = baseForm();
    form.contextWindowInput = -1;

    // when
    const errors = validateModelForm(form);

    // then
    expect(errors.contextWindowInput).toContain('负数');
  });

  it('should_报轮次非法_when_validateModelForm_given_工具调用轮次为0', () => {
    // given
    const form = baseForm();
    form.toolCallRounds = 0;

    // when
    const errors = validateModelForm(form);

    // then
    expect(errors.toolCallRounds).toContain('正整数');
  });

  it('should_报维度非法_when_validateModelForm_given_嵌入维度为负数', () => {
    // given
    const form = baseForm();
    form.modelType = MODEL_TYPE_EMBEDDING;
    form.vectorDimension = -8;

    // when
    const errors = validateModelForm(form);

    // then
    expect(errors.vectorDimension).toContain('正整数');
  });
});

describe('回显与标签', () => {
  /** 构造后端响应样本（可空字段为 null）。 */
  function response(): ModelProfileResponse {
    return {
      profileId: 'mp_1',
      displayName: '嵌入模型',
      description: null,
      apiFormat: 'BAILIAN',
      apiEndpointUrl: 'https://example.com/v1',
      modelName: 'text-embedding-v3',
      credentialConfigured: true,
      modelSeries: null,
      contextWindowInput: null,
      contextWindowOutput: null,
      toolCallRounds: 999999,
      modelType: MODEL_TYPE_EMBEDDING,
      vectorDimension: 1024,
      status: 'ENABLED',
      createdAt: '2026-10-01T10:00:00+08:00',
      updatedAt: '2026-10-02T10:00:00+08:00',
    };
  }

  it('should_凭证恒空_when_modelProfileFormFromResponse_given_响应已配置凭证', () => {
    // given
    const resp = response();

    // when
    const form = modelProfileFormFromResponse(resp);

    // then
    expect(form.credential).toBe('');
    expect(form.clearCredential).toBe(false);
    expect(form.apiFormat).toBe('BAILIAN');
    expect(form.modelType).toBe(MODEL_TYPE_EMBEDDING);
    expect(form.vectorDimension).toBe(1024);
    expect(form.description).toBe('');
    expect(form.modelSeries).toBe('');
    expect(form.contextWindowInput).toBeNull();
  });

  it('should_返回编辑载荷可直接提交_when_modelProfileFormFromResponse_then_build', () => {
    // given
    const form = modelProfileFormFromResponse(response());

    // when
    const payload = buildModelProfilePayload(form, true);

    // then
    expect(payload).not.toHaveProperty('credential');
    expect(payload.vectorDimension).toBe(1024);
    expect(payload.toolCallRounds).toBe(999999);
  });

  it('should_返回中文标签_when_modelTypeLabel_given_已知与未知编码', () => {
    // given & when & then
    expect(modelTypeLabel(MODEL_TYPE_CHAT_LLM)).toBe('对话LLM');
    expect(modelTypeLabel(MODEL_TYPE_CHAT_VLM)).toBe('视觉对话');
    expect(modelTypeLabel(MODEL_TYPE_EMBEDDING)).toBe('嵌入');
    expect(modelTypeLabel(MODEL_TYPE_RERANK)).toBe('重排');
    expect(modelTypeLabel(7)).toBe('7');
    expect(modelTypeLabel(null)).toBe('-');
  });

  it('should_给出默认值_when_createEmptyModelForm_given_无输入', () => {
    // given & when
    const form = createEmptyModelForm();

    // then
    expect(form.apiFormat).toBe('OPENAI');
    expect(form.modelType).toBe(MODEL_TYPE_CHAT_LLM);
    expect(form.credential).toBe('');
    expect(form.vectorDimension).toBeNull();
    expect(validateModelForm(form).displayName).toBe('请输入显示名称');
  });
});
