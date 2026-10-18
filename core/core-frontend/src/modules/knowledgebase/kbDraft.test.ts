// @vitest-environment node
import { describe, expect, it } from 'vitest';
import type { KnowledgeBaseWire } from './api/knowledgeBase';
import {
  buildCreateRequest,
  buildUpdateRequest,
  defaultDraft,
  draftFromKb,
  validateDraft,
} from './kbDraft';
import type { KbDraft } from './kbDraft';

/** 用创建载荷 + 元数据拼一个线格式响应，供反解测试 */
function wireFromPayload(draft: KbDraft, payload: ReturnType<typeof buildCreateRequest>): KnowledgeBaseWire {
  return {
    id: 1,
    name: payload.name,
    description: payload.description ?? null,
    language: payload.language ?? null,
    lifecycleStatus: 'ACTIVE',
    errorMessage: null,
    ragEngineConfig: payload.ragEngineConfig,
    dedupPolicy: payload.dedupPolicy,
    retrievalStrategy: null,
    embeddingConfig: payload.embeddingConfig,
    multiModelConfig: payload.multiModelConfig,
    entityTypeConfig: payload.entityTypeConfig ?? null,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-02T00:00:00Z',
  };
}

describe('defaultDraft', () => {
  it('should 返回业务默认值_when 新建', () => {
    const draft = defaultDraft();
    expect(draft.language).toBe('Chinese');
    expect(draft.parseProvider).toBe('LOCAL');
    expect(draft.chunkMode).toBe('GENERAL');
    expect(draft.general).toEqual({ chunkTokenNum: 512, overlappedPercent: 0, delimiter: '\n' });
    expect(draft.dedup).toEqual({ matchRule: 'BY_NAME_OR_CONTENT', conflictAction: 'SKIP' });
    expect(draft.entityTypes).toEqual([]);
  });
});

describe('buildCreateRequest 装配', () => {
  it('should 将七个配置字段序列化为 JSON 字符串_when GENERAL+LOCAL', () => {
    const draft = defaultDraft();
    draft.name = '运营知识库';
    draft.embeddingProfileId = 'emb-1';
    draft.multiModelProfileId = 'chat-1';
    const payload = buildCreateRequest(draft);

    expect(payload.name).toBe('运营知识库');
    expect(payload.embeddingConfig).toBe(JSON.stringify({ modelProfileId: 'emb-1' }));
    expect(payload.multiModelConfig).toBe(JSON.stringify({ modelProfileId: 'chat-1' }));

    const rag = JSON.parse(payload.ragEngineConfig);
    expect(rag.engineType).toBe('DOCUMENT_ENGINE');
    expect(rag.parseEngine.provider).toBe('LOCAL');
    expect(rag.parseEngine.engineConfig.params).toEqual({});
    expect(rag.chunkStrategy).toEqual({
      chunkMode: 'GENERAL',
      modeConfig: { params: { chunk_token_num: 512, overlapped_percent: 0, delimiter: '\n' } },
    });
  });

  it('should 非 GENERAL 模式不携带 modeConfig_params_when 选择 QA', () => {
    const draft = defaultDraft();
    draft.chunkMode = 'QA';
    const rag = JSON.parse(buildCreateRequest(draft).ragEngineConfig);
    expect(rag.chunkStrategy).toEqual({ chunkMode: 'QA' });
    expect(rag.chunkStrategy.modeConfig).toBeUndefined();
  });

  it('should MINERU 仅携带非空解析参数_when provider=MINERU', () => {
    const draft = defaultDraft();
    draft.parseProvider = 'MINERU';
    draft.mineru = { apiBaseUrl: 'http://mineru:8000', backend: '', parseMethod: 'ocr', language: '', quality: '' };
    const rag = JSON.parse(buildCreateRequest(draft).ragEngineConfig);
    expect(rag.parseEngine.provider).toBe('MINERU');
    expect(rag.parseEngine.engineConfig.params).toEqual({ apiBaseUrl: 'http://mineru:8000', parseMethod: 'ocr' });
  });

  it('should NONE 去重不携带 conflictAction_when matchRule=NONE', () => {
    const draft = defaultDraft();
    draft.dedup = { matchRule: 'NONE', conflictAction: 'REJECT' };
    expect(JSON.parse(buildCreateRequest(draft).dedupPolicy)).toEqual({ matchRule: 'NONE' });
  });

  it('should 仅在存在自定义实体类型时携带 entityTypeConfig', () => {
    const draft = defaultDraft();
    expect(buildCreateRequest(draft).entityTypeConfig).toBeUndefined();
    draft.entityTypes = ['指标', '口径'];
    const entity = JSON.parse(buildCreateRequest(draft).entityTypeConfig as string);
    expect(entity).toEqual({ entityTypes: [{ entityType: '指标' }, { entityType: '口径' }] });
  });
});

describe('buildUpdateRequest 装配', () => {
  it('should 不携带 embeddingConfig_when 更新（后端锁定忽略）', () => {
    const draft = defaultDraft();
    draft.embeddingProfileId = 'emb-1';
    const payload = buildUpdateRequest(draft);
    expect(payload).not.toHaveProperty('embeddingConfig');
    expect(payload.multiModelConfig).toBe(JSON.stringify({ modelProfileId: draft.multiModelProfileId }));
  });
});

describe('装配-反解往返一致性', () => {
  it('should 反解结果与草稿关键字段一致_when GENERAL+MINERU+实体类型+去重', () => {
    const draft = defaultDraft();
    draft.name = '研发知识库';
    draft.description = '研发域';
    draft.language = 'English';
    draft.embeddingProfileId = 'emb-9';
    draft.multiModelProfileId = 'chat-9';
    draft.parseProvider = 'MINERU';
    draft.mineru = { apiBaseUrl: 'http://m:8000', backend: 'pipeline', parseMethod: 'txt', language: 'ch_en', quality: 'high' };
    draft.general = { chunkTokenNum: 1024, overlappedPercent: 15, delimiter: '\\n\\n' };
    draft.dedup = { matchRule: 'BY_CONTENT', conflictAction: 'OVERWRITE' };
    draft.entityTypes = ['指标', '维度'];

    const payload = buildCreateRequest(draft);
    const back = draftFromKb(wireFromPayload(draft, payload));

    expect(back.name).toBe(draft.name);
    expect(back.description).toBe(draft.description);
    expect(back.language).toBe('English');
    expect(back.embeddingProfileId).toBe('emb-9');
    expect(back.multiModelProfileId).toBe('chat-9');
    expect(back.parseProvider).toBe('MINERU');
    expect(back.mineru).toEqual(draft.mineru);
    expect(back.chunkMode).toBe('GENERAL');
    expect(back.general).toEqual(draft.general);
    expect(back.dedup).toEqual(draft.dedup);
    expect(back.entityTypes).toEqual(['指标', '维度']);
  });

  it('should 非 GENERAL 反解回退默认分块参数_when 无 modeConfig', () => {
    const draft = defaultDraft();
    draft.chunkMode = 'BOOK';
    const back = draftFromKb(wireFromPayload(draft, buildCreateRequest(draft)));
    expect(back.chunkMode).toBe('BOOK');
    expect(back.general).toEqual({ chunkTokenNum: 512, overlappedPercent: 0, delimiter: '\n' });
  });
});

describe('draftFromKb 防御性解析', () => {
  it('should 脏 JSON 回退默认值_when 配置字段非法', () => {
    const wire: KnowledgeBaseWire = {
      id: 7,
      name: '脏数据库',
      description: null,
      language: 'NotALanguage',
      lifecycleStatus: 'ACTIVE',
      errorMessage: null,
      ragEngineConfig: '{不是合法JSON',
      dedupPolicy: 'null',
      retrievalStrategy: null,
      embeddingConfig: '["坏形态"]',
      multiModelConfig: '[]',
      entityTypeConfig: 'oops',
      createdAt: '',
      updatedAt: '',
    };
    const back = draftFromKb(wire);
    expect(back.name).toBe('脏数据库');
    expect(back.language).toBe('Chinese');
    expect(back.parseProvider).toBe('LOCAL');
    expect(back.chunkMode).toBe('GENERAL');
    expect(back.general).toEqual({ chunkTokenNum: 512, overlappedPercent: 0, delimiter: '\n' });
    expect(back.embeddingProfileId).toBe('');
    expect(back.dedup).toEqual({ matchRule: 'BY_NAME_OR_CONTENT', conflictAction: 'SKIP' });
    expect(back.entityTypes).toEqual([]);
  });

  it('should 越界分块参数夹取回默认_when 存量数据非法', () => {
    const wire = wireFromPayload(defaultDraft(), {
      name: 'x',
      ragEngineConfig: JSON.stringify({
        engineType: 'DOCUMENT_ENGINE',
        chunkStrategy: { chunkMode: 'GENERAL', modeConfig: { params: { chunk_token_num: 99999, overlapped_percent: 88, delimiter: '|' } } },
      }),
      dedupPolicy: '{}',
      embeddingConfig: '{}',
      multiModelConfig: '{}',
    });
    const back = draftFromKb(wire);
    expect(back.general.chunkTokenNum).toBe(512);
    expect(back.general.overlappedPercent).toBe(0);
    expect(back.general.delimiter).toBe('|');
  });
});

describe('validateDraft 前置拦截', () => {
  it('should 通过_when 合法草稿', () => {
    const draft = defaultDraft();
    draft.name = '运营知识库';
    draft.embeddingProfileId = 'emb-1';
    draft.multiModelProfileId = 'chat-1';
    expect(validateDraft(draft)).toEqual({});
  });

  it('should 报名称错误_when 名称为空或超长', () => {
    const empty = defaultDraft();
    empty.embeddingProfileId = 'e';
    empty.multiModelProfileId = 'm';
    expect(validateDraft(empty).name).toBe('知识库名称不能为空');

    const longDraft = { ...empty, name: 'a'.repeat(129) };
    expect(validateDraft(longDraft).name).toContain('128');
  });

  it('should 报模型缺失_when 嵌入或多模态未选', () => {
    const draft = defaultDraft();
    draft.name = '库';
    const errors = validateDraft(draft);
    expect(errors.embeddingProfileId).toBeTruthy();
    expect(errors.multiModelProfileId).toBeTruthy();
  });

  it('should 报 MinerU 地址必填_when provider=MINERU 且地址空', () => {
    const draft = defaultDraft();
    draft.name = '库';
    draft.embeddingProfileId = 'e';
    draft.multiModelProfileId = 'm';
    draft.parseProvider = 'MINERU';
    expect(validateDraft(draft)['mineru.apiBaseUrl']).toBeTruthy();
  });

  it('should 校验 GENERAL 三参数范围_when 越界', () => {
    const draft = defaultDraft();
    draft.name = '库';
    draft.embeddingProfileId = 'e';
    draft.multiModelProfileId = 'm';
    draft.general = { chunkTokenNum: 0, overlappedPercent: 31, delimiter: '' };
    const errors = validateDraft(draft);
    expect(errors['general.chunkTokenNum']).toBeTruthy();
    expect(errors['general.overlappedPercent']).toBeTruthy();
    expect(errors['general.delimiter']).toBeTruthy();
  });

  it('should 不校验分块参数_when 非 GENERAL', () => {
    const draft = defaultDraft();
    draft.name = '库';
    draft.embeddingProfileId = 'e';
    draft.multiModelProfileId = 'm';
    draft.chunkMode = 'TABLE';
    draft.general = { chunkTokenNum: 0, overlappedPercent: 99, delimiter: '' };
    expect(validateDraft(draft)).toEqual({});
  });

  it('should 报冲突动作必填_when matchRule 非 NONE 且动作空', () => {
    const draft = defaultDraft();
    draft.name = '库';
    draft.embeddingProfileId = 'e';
    draft.multiModelProfileId = 'm';
    draft.dedup = { matchRule: 'BY_NAME', conflictAction: '' };
    expect(validateDraft(draft)['dedup.conflictAction']).toBeTruthy();
  });

  it('should 报实体类型数量超限_when 超过 10 个', () => {
    const draft = defaultDraft();
    draft.name = '库';
    draft.embeddingProfileId = 'e';
    draft.multiModelProfileId = 'm';
    draft.entityTypes = Array.from({ length: 11 }, (_, i) => `类型${i}`);
    expect(validateDraft(draft).entityTypes).toContain('10');
  });

  it('should 报实体类型非法字符_when 含空格或符号', () => {
    const draft = defaultDraft();
    draft.name = '库';
    draft.embeddingProfileId = 'e';
    draft.multiModelProfileId = 'm';
    draft.entityTypes = ['非法 名称'];
    expect(validateDraft(draft).entityTypes).toContain('仅允许');
  });

  it('should 报实体类型与内置重名_when 使用内置代码或中文名', () => {
    const draft = defaultDraft();
    draft.name = '库';
    draft.embeddingProfileId = 'e';
    draft.multiModelProfileId = 'm';
    draft.entityTypes = ['person'];
    expect(validateDraft(draft).entityTypes).toContain('内置类型');
  });

  it('should 报实体类型大小写重复_when 归一后同名', () => {
    const draft = defaultDraft();
    draft.name = '库';
    draft.embeddingProfileId = 'e';
    draft.multiModelProfileId = 'm';
    draft.entityTypes = ['Metric', 'metric'];
    expect(validateDraft(draft).entityTypes).toContain('重复');
  });
});
