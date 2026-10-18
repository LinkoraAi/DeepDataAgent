// @vitest-environment node
import { describe, expect, it } from 'vitest';
import type { QueryImageDraft, RetrievalDraft } from './retrievalDraft';
import {
  DEFAULT_CHANNEL_WEIGHTS,
  DEFAULT_RESULT_CHUNK_COUNT,
  DEFAULT_RRF_K,
  MAX_QUERY_IMAGE_BYTES,
  MAX_QUERY_IMAGES,
  RESULT_CHUNK_COUNT_MAX,
  buildStrategyJson,
  decodedByteLength,
  defaultRetrievalConfig,
  parseStrategyJson,
  stripDataUrlPrefix,
  validateQueryImage,
  validateRetrievalConfig,
} from './retrievalDraft';

/** 复制默认配置并套用补丁，避免用例间相互污染 */
function configWith(patch: (config: RetrievalDraft) => void): RetrievalDraft {
  const config: RetrievalDraft = JSON.parse(JSON.stringify(defaultRetrievalConfig()));
  patch(config);
  return config;
}

/** 拼一个附图草稿（base64 载荷长度可控，解码字节数 ≈ 长度 × 3/4） */
function imageOf(contentType: string, dataLength: number, name = 'a.png'): QueryImageDraft {
  return { name, contentType, data: 'A'.repeat(dataLength) };
}

/** 解析 buildStrategyJson 产物为可读对象（测试断言用） */
function toJson(json: string): Record<string, unknown> {
  return JSON.parse(json) as Record<string, unknown>;
}

describe('defaultRetrievalConfig', () => {
  it('should_返回NAIVE不改写数量20且RRF60_when_defaultRetrievalConfig_given_无入参', () => {
    // when
    const config = defaultRetrievalConfig();

    // then
    expect(config.strategyType).toBe('NAIVE');
    expect(config.rewriteQuestion).toBe(false);
    expect(config.resultChunkCount).toBe(DEFAULT_RESULT_CHUNK_COUNT);
    expect(config.similarThreshold).toBe(0);
    expect(config.rerankConfig).toEqual({ enabled: false, modelProfileId: '', topK: undefined });
    expect(config.fusionConfig?.fusionType).toBe('RRF');
    expect(config.fusionConfig?.rrfK).toBe(DEFAULT_RRF_K);
    expect(config.fusionConfig?.channelDenseWeight).toEqual({ VECTOR: 0.7, BM25: 0.2, GRAPH: 0.1 });
  });
});

describe('buildStrategyJson 装配', () => {
  it('should_重排关闭时仅写enabled为false_when_buildStrategyJson_given_默认配置', () => {
    // given
    const config = defaultRetrievalConfig();

    // when
    const node = toJson(buildStrategyJson(config));

    // then
    expect(node.strategyType).toBe('NAIVE');
    expect(node.rewriteQuestion).toBe(false);
    expect(node.resultChunkCount).toBe(DEFAULT_RESULT_CHUNK_COUNT);
    expect(node.rerankConfig).toEqual({ enabled: false });
    expect(node.fusionConfig).toEqual({ fusionType: 'RRF', rrfK: DEFAULT_RRF_K });
  });

  it('should_加权求和时只写三通道权重不写rrfK_when_buildStrategyJson_given_选择WEIGHTED_SUM', () => {
    // given
    const config = configWith((draft) => {
      draft.strategyType = 'MIX';
      draft.rewriteQuestion = true;
      draft.similarThreshold = 0.35;
      draft.fusionConfig = {
        fusionType: 'WEIGHTED_SUM',
        rrfK: 99,
        channelDenseWeight: { VECTOR: 0.5, BM25: 0.3, GRAPH: 0.2 },
      };
    });

    // when
    const node = toJson(buildStrategyJson(config));

    // then
    expect(node.strategyType).toBe('MIX');
    expect(node.rewriteQuestion).toBe(true);
    expect(node.fusionConfig).toEqual({
      fusionType: 'WEIGHTED_SUM',
      channelDenseWeight: { VECTOR: 0.5, BM25: 0.3, GRAPH: 0.2 },
    });
  });

  it('should_携带模型引用与topK_when_buildStrategyJson_given_开启重排', () => {
    // given
    const config = configWith((draft) => {
      draft.rerankConfig = { enabled: true, modelProfileId: ' rerank-model ', topK: 8 };
    });

    // when
    const node = toJson(buildStrategyJson(config));

    // then
    expect(node.rerankConfig).toEqual({ enabled: true, modelProfileId: 'rerank-model', topK: 8 });
  });

  it('should_越界标量归一为默认值_when_buildStrategyJson_given_数量与阈值超限', () => {
    // given
    const config = configWith((draft) => {
      draft.resultChunkCount = RESULT_CHUNK_COUNT_MAX + 500;
      draft.similarThreshold = 9;
    });

    // when
    const node = toJson(buildStrategyJson(config));

    // then
    expect(node.resultChunkCount).toBe(DEFAULT_RESULT_CHUNK_COUNT);
    expect(node.similarThreshold).toBe(0);
  });

  it('should_非法枚举回落NAIVE与RRF_when_buildStrategyJson_given_脏枚举值', () => {
    // given
    const config = configWith((draft) => {
      draft.strategyType = 'HYBRID';
      draft.fusionConfig = { fusionType: 'UNKNOWN', rrfK: 0, channelDenseWeight: { ...DEFAULT_CHANNEL_WEIGHTS } };
    });

    // when
    const node = toJson(buildStrategyJson(config));

    // then
    expect(node.strategyType).toBe('NAIVE');
    expect(node.fusionConfig).toEqual({ fusionType: 'RRF', rrfK: DEFAULT_RRF_K });
  });
});

describe('parseStrategyJson 回显', () => {
  it('should_往返等于默认配置_when_parseStrategyJson_given_buildStrategyJson产物', () => {
    // given
    const config = defaultRetrievalConfig();

    // when
    const parsed = parseStrategyJson(buildStrategyJson(config));

    // then
    expect(parsed).toEqual(config);
  });

  it('should_往返保留加权配置_when_parseStrategyJson_given_WEIGHTED_SUM产物', () => {
    // given
    const config = configWith((draft) => {
      draft.fusionConfig = {
        fusionType: 'WEIGHTED_SUM',
        // 回显层恒补齐 rrfK（仅 RRF 分支参与提交），故往返基准须带上默认值
        rrfK: DEFAULT_RRF_K,
        channelDenseWeight: { VECTOR: 0.6, BM25: 0.25, GRAPH: 0.15 },
      };
      draft.rerankConfig = { enabled: true, modelProfileId: 'rerank-1', topK: 5 };
    });

    // when
    const parsed = parseStrategyJson(buildStrategyJson(config));

    // then
    expect(parsed).toEqual(config);
  });

  it('should_回退默认配置_when_parseStrategyJson_given_空或非法JSON', () => {
    // then
    expect(parseStrategyJson(null)).toEqual(defaultRetrievalConfig());
    expect(parseStrategyJson('')).toEqual(defaultRetrievalConfig());
    expect(parseStrategyJson('not-json')).toEqual(defaultRetrievalConfig());
    expect(parseStrategyJson('[1,2]')).toEqual(defaultRetrievalConfig());
    expect(parseStrategyJson('123')).toEqual(defaultRetrievalConfig());
  });

  it('should_单字段脏值独立回退_when_parseStrategyJson_given_越界与非法类型', () => {
    // given
    const raw = JSON.stringify({
      strategyType: 'MIX',
      rewriteQuestion: 'yes',
      resultChunkCount: 0,
      similarThreshold: -1,
      rerankConfig: { enabled: true },
      fusionConfig: { fusionType: 'RRF', rrfK: -5, channelDenseWeight: { VECTOR: 'x' } },
    });

    // when
    const parsed = parseStrategyJson(raw);

    // then
    expect(parsed.strategyType).toBe('MIX');
    expect(parsed.rewriteQuestion).toBe(false);
    expect(parsed.resultChunkCount).toBe(DEFAULT_RESULT_CHUNK_COUNT);
    expect(parsed.similarThreshold).toBe(0);
    expect(parsed.rerankConfig).toEqual({ enabled: true, modelProfileId: '', topK: undefined });
    expect(parsed.fusionConfig?.rrfK).toBe(DEFAULT_RRF_K);
    expect(parsed.fusionConfig?.channelDenseWeight).toEqual({ VECTOR: 0.7, BM25: 0.2, GRAPH: 0.1 });
  });

  it('should_空配置对象回显全默认_when_parseStrategyJson_given_空对象', () => {
    // when
    const parsed = parseStrategyJson('{}');

    // then
    expect(parsed).toEqual(defaultRetrievalConfig());
  });
});

describe('validateRetrievalConfig 前置校验', () => {
  it('should_无错误_when_validateRetrievalConfig_given_默认配置', () => {
    expect(validateRetrievalConfig(defaultRetrievalConfig())).toEqual({});
  });

  it('should_报结果数量越界_when_validateRetrievalConfig_given_零与超上限', () => {
    expect(validateRetrievalConfig(configWith((c) => {
      c.resultChunkCount = 0;
    }))).toHaveProperty('resultChunkCount');
    expect(validateRetrievalConfig(configWith((c) => {
      c.resultChunkCount = RESULT_CHUNK_COUNT_MAX + 1;
    }))).toHaveProperty('resultChunkCount');
    expect(validateRetrievalConfig(configWith((c) => {
      c.resultChunkCount = 20.5;
    }))).toHaveProperty('resultChunkCount');
  });

  it('should_报相似度阈值越界_when_validateRetrievalConfig_given_大于1', () => {
    // when
    const errors = validateRetrievalConfig(configWith((c) => {
      c.similarThreshold = 1.5;
    }));

    // then
    expect(errors.similarThreshold).toBeTruthy();
  });

  it('should_报缺重排模型_when_validateRetrievalConfig_given_重排开启但无模型', () => {
    // when
    const errors = validateRetrievalConfig(configWith((c) => {
      c.rerankConfig = { enabled: true, modelProfileId: '  ' };
    }));

    // then
    expect(errors['rerankConfig.modelProfileId']).toBeTruthy();
    expect(validateRetrievalConfig(configWith((c) => {
      c.rerankConfig = { enabled: false, modelProfileId: '' };
    }))).toEqual({});
  });

  it('should_报重排topK非法_when_validateRetrievalConfig_given_topK非正整数', () => {
    // when
    const errors = validateRetrievalConfig(configWith((c) => {
      c.rerankConfig = { enabled: true, modelProfileId: 'rerank-1', topK: 0 };
    }));

    // then
    expect(errors['rerankConfig.topK']).toBeTruthy();
  });

  it('should_报RRF的K必须大于0_when_validateRetrievalConfig_given_rrfK为零', () => {
    // when
    const errors = validateRetrievalConfig(configWith((c) => {
      c.fusionConfig = { fusionType: 'RRF', rrfK: 0, channelDenseWeight: { ...DEFAULT_CHANNEL_WEIGHTS } };
    }));

    // then
    expect(errors['fusionConfig.rrfK']).toBeTruthy();
  });

  it('should_报三通道权重必填_when_validateRetrievalConfig_given_加权求和缺权重', () => {
    // when
    const errors = validateRetrievalConfig(configWith((c) => {
      c.fusionConfig = {
        fusionType: 'WEIGHTED_SUM',
        rrfK: DEFAULT_RRF_K,
        channelDenseWeight: { VECTOR: 0.5 },
      };
    }));

    // then
    expect(errors['fusionConfig.channelDenseWeight.VECTOR']).toBeFalsy();
    expect(errors['fusionConfig.channelDenseWeight.BM25']).toBeTruthy();
    expect(errors['fusionConfig.channelDenseWeight.GRAPH']).toBeTruthy();
  });

  it('should_报权重不在开区间_when_validateRetrievalConfig_given_零或1', () => {
    // when
    const errors = validateRetrievalConfig(configWith((c) => {
      c.fusionConfig = {
        fusionType: 'WEIGHTED_SUM',
        rrfK: DEFAULT_RRF_K,
        channelDenseWeight: { VECTOR: 0, BM25: 1, GRAPH: 0.5 },
      };
    }));

    // then
    expect(errors['fusionConfig.channelDenseWeight.VECTOR']).toBeTruthy();
    expect(errors['fusionConfig.channelDenseWeight.BM25']).toBeTruthy();
    expect(errors['fusionConfig.channelDenseWeight.GRAPH']).toBeFalsy();
  });

  it('should_报非法策略与融合类型_when_validateRetrievalConfig_given_脏枚举', () => {
    // when
    const errors = validateRetrievalConfig(configWith((c) => {
      c.strategyType = 'FAST';
      c.fusionConfig = { fusionType: 'UNKNOWN', rrfK: DEFAULT_RRF_K, channelDenseWeight: { ...DEFAULT_CHANNEL_WEIGHTS } };
    }));

    // then
    expect(errors.strategyType).toBeTruthy();
    expect(errors['fusionConfig.fusionType']).toBeTruthy();
  });

  it('should_装配产物必然通过校验_when_validateRetrievalConfig_given_回显后配置', () => {
    // given
    const dirty = JSON.stringify({
      strategyType: 'mix',
      resultChunkCount: 30,
      similarThreshold: 0.2,
      fusionConfig: { fusionType: 'WEIGHTED_SUM', channelDenseWeight: { VECTOR: 0.6, BM25: 0.3, GRAPH: 0.1 } },
    });

    // when
    const parsed = parseStrategyJson(dirty);

    // then
    expect(validateRetrievalConfig(parsed)).toEqual({});
  });
});

describe('validateQueryImage 附图规则', () => {
  it('should_允许白名单图片_when_validateQueryImage_given_未达数量上限', () => {
    expect(validateQueryImage(imageOf('image/png', 1024), 0)).toBeNull();
  });

  it('should_拒绝超限数量_when_validateQueryImage_given_已挂三张', () => {
    const reason = validateQueryImage(imageOf('image/png', 1024), MAX_QUERY_IMAGES);
    expect(reason).toContain(String(MAX_QUERY_IMAGES));
  });

  it('should_拒绝非白名单类型_when_validateQueryImage_given_bmp', () => {
    expect(validateQueryImage(imageOf('image/bmp', 1024, 'a.bmp'), 0)).toContain('a.bmp');
  });

  it('should_拒绝单图超4MB_when_validateQueryImage_given_解码字节越界', () => {
    const reason = validateQueryImage(imageOf('image/jpeg', MAX_QUERY_IMAGE_BYTES * 2, 'big.jpg'), 0);
    expect(reason).toContain('big.jpg');
  });

  it('should_类型大小写不敏感_when_validateQueryImage_given_大写MIME', () => {
    expect(validateQueryImage(imageOf(' IMAGE/WEBP ', 1024, 'a.webp'), 2)).toBeNull();
  });

  it('should_按base64长度估算解码字节_when_decodedByteLength_given_含补齐符载荷', () => {
    expect(decodedByteLength('')).toBe(0);
    expect(decodedByteLength(null)).toBe(0);
    expect(decodedByteLength('QQ==')).toBe(1);
    expect(decodedByteLength('QUI=')).toBe(2);
    expect(decodedByteLength('QUJD')).toBe(3);
  });

  it('should_剥离data前缀_when_stripDataUrlPrefix_given_dataURL或纯载荷', () => {
    expect(stripDataUrlPrefix('data:image/png;base64,QUJD')).toBe('QUJD');
    expect(stripDataUrlPrefix('QUJD')).toBe('QUJD');
  });
});
