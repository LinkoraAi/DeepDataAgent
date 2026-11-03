// @vitest-environment node
import { describe, expect, it } from 'vitest';
import type { QueryImageDraft, RetrievalDraft } from './retrievalDraft';
import {
  CHANNEL_WEIGHT_MAX,
  CHANNEL_WEIGHT_MIN,
  CHANNEL_WEIGHT_TOTAL_TICKS,
  DEFAULT_CHANNEL_WEIGHTS,
  DEFAULT_QUERY_COUNT,
  DEFAULT_RESULT_CHUNK_COUNT,
  DEFAULT_RERANK_TOP_K,
  DEFAULT_RRF_K,
  MAX_QUERY_IMAGE_BYTES,
  MAX_QUERY_IMAGES,
  QUERY_COUNT_MAX,
  QUERY_COUNT_MIN,
  RESULT_CHUNK_COUNT_MAX,
  buildStrategyJson,
  decodedByteLength,
  defaultRetrievalConfig,
  parseStrategyJson,
  rebalanceChannelWeights,
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
    expect(config.rerankConfig).toEqual({ enabled: false, modelProfileId: '', topK: DEFAULT_RERANK_TOP_K });
    expect(config.fusionConfig?.fusionType).toBe('RRF');
    expect(config.fusionConfig?.rrfK).toBe(DEFAULT_RRF_K);
    expect(config.fusionConfig?.channelDenseWeight).toEqual({ VECTOR: 0.7, BM25: 0.2, GRAPH: 0.1 });
    expect(config.channelQueryCountConfig).toEqual({
      vectorQueryCount: DEFAULT_QUERY_COUNT,
      bm25QueryCount: DEFAULT_QUERY_COUNT,
      graphQueryCount: DEFAULT_QUERY_COUNT,
    });
  });

  it('should_重排topK默认与后端一致_when_constants_given_前后端统一口径', () => {
    // then：后端 RetrievalConstants.RERANK_TOP_K_DEFAULT=50 为唯一口径
    expect(DEFAULT_RERANK_TOP_K).toBe(50);
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

  it('should_权重复合归一到合计1_when_buildStrategyJson_given_加权权重合计超1', () => {
    // given：0.5 + 0.3 + 0.3 = 1.1，超出「合计不大于 1」约束
    const config = configWith((draft) => {
      draft.fusionConfig = {
        fusionType: 'WEIGHTED_SUM',
        rrfK: DEFAULT_RRF_K,
        channelDenseWeight: { VECTOR: 0.5, BM25: 0.3, GRAPH: 0.3 },
      };
    });

    // when
    const node = toJson(buildStrategyJson(config));

    // then：按原比例归一到 10 刻度，合计恰为 1.0 且每路落在 0.1~0.8
    const weights = node.fusionConfig as { channelDenseWeight: Record<string, number> };
    const ticks = Object.values(weights.channelDenseWeight).map((weight) => Math.round(weight * 10));
    expect(ticks.reduce((sum, tick) => sum + tick, 0)).toBe(CHANNEL_WEIGHT_TOTAL_TICKS);
    expect(weights.channelDenseWeight).toEqual({ VECTOR: 0.4, BM25: 0.3, GRAPH: 0.3 });
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

  it('should_NAIVE不写图谱数量_when_buildStrategyJson_given_默认配置', () => {
    // given
    const config = defaultRetrievalConfig();

    // when
    const node = toJson(buildStrategyJson(config));

    // then：NAIVE 不消费图谱通道，序列化不携带 graphQueryCount
    expect(node.channelQueryCountConfig).toEqual({
      vectorQueryCount: DEFAULT_QUERY_COUNT,
      bm25QueryCount: DEFAULT_QUERY_COUNT,
    });
  });

  it('should_MIX写三通道数量_when_buildStrategyJson_given_30与15与25', () => {
    // given
    const config = configWith((draft) => {
      draft.strategyType = 'MIX';
      draft.channelQueryCountConfig = { vectorQueryCount: 30, bm25QueryCount: 15, graphQueryCount: 25 };
    });

    // when
    const node = toJson(buildStrategyJson(config));

    // then
    expect(node.channelQueryCountConfig).toEqual({ vectorQueryCount: 30, bm25QueryCount: 15, graphQueryCount: 25 });
  });

  it('should_越界通道数量归一默认_when_buildStrategyJson_given_零与超上限', () => {
    // given
    const config = configWith((draft) => {
      draft.strategyType = 'MIX';
      draft.channelQueryCountConfig = {
        vectorQueryCount: QUERY_COUNT_MIN - 1,
        bm25QueryCount: QUERY_COUNT_MAX + 1,
        graphQueryCount: 25,
      };
    });

    // when
    const node = toJson(buildStrategyJson(config));

    // then：越界字段归一为默认 20，合法字段原样保留
    expect(node.channelQueryCountConfig).toEqual({
      vectorQueryCount: DEFAULT_QUERY_COUNT,
      bm25QueryCount: DEFAULT_QUERY_COUNT,
      graphQueryCount: 25,
    });
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
        // 权重为 0.1 刻度且合计 1.0，与回显归一化口径一致
        channelDenseWeight: { VECTOR: 0.6, BM25: 0.3, GRAPH: 0.1 },
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
    expect(parsed.rerankConfig).toEqual({ enabled: true, modelProfileId: '', topK: DEFAULT_RERANK_TOP_K });
    expect(parsed.fusionConfig?.rrfK).toBe(DEFAULT_RRF_K);
    expect(parsed.fusionConfig?.channelDenseWeight).toEqual({ VECTOR: 0.7, BM25: 0.2, GRAPH: 0.1 });
  });

  it('should_通道数量缺失或脏值独立回落_when_parseStrategyJson_given_脏数量节点', () => {
    // given：vector 合法保留，bm25 越界、graph 非数值各自回落 20
    const raw = JSON.stringify({
      strategyType: 'MIX',
      channelQueryCountConfig: { vectorQueryCount: 30, bm25QueryCount: 0, graphQueryCount: 'x' },
    });

    // when
    const parsed = parseStrategyJson(raw);

    // then
    expect(parsed.channelQueryCountConfig).toEqual({
      vectorQueryCount: 30,
      bm25QueryCount: DEFAULT_QUERY_COUNT,
      graphQueryCount: DEFAULT_QUERY_COUNT,
    });
  });

  it('should_数量节点非对象整体回落_when_parseStrategyJson_given_脏节点类型', () => {
    // when
    const parsed = parseStrategyJson(JSON.stringify({ channelQueryCountConfig: [1, 2] }));

    // then
    expect(parsed.channelQueryCountConfig).toEqual(defaultRetrievalConfig().channelQueryCountConfig);
  });

  it('should_空配置对象回显全默认_when_parseStrategyJson_given_空对象', () => {
    // when
    const parsed = parseStrategyJson('{}');

    // then
    expect(parsed).toEqual(defaultRetrievalConfig());
  });
});

describe('rebalanceChannelWeights 三路联动', () => {
  /** 断言三路权重合计恒为 1.0 且每路落在 0.1~0.8（后端开区间 (0,1) 的可用区间） */
  function expectLegalTotal(weights: Record<string, number>): void {
    const ticks = Object.values(weights).map((weight) => Math.round(weight * 10));
    expect(ticks.reduce((sum, tick) => sum + tick, 0)).toBe(CHANNEL_WEIGHT_TOTAL_TICKS);
    Object.values(weights).forEach((weight) => {
      expect(weight).toBeGreaterThanOrEqual(CHANNEL_WEIGHT_MIN);
      expect(weight).toBeLessThanOrEqual(CHANNEL_WEIGHT_MAX);
    });
  }

  it('should_其余两路按比例让位_when_rebalanceChannelWeights_given_VECTOR拖到0.3', () => {
    // given：默认 0.7 / 0.2 / 0.1（BM25 与 GRAPH 比例 2:1）

    // when：VECTOR 拖到 0.3，剩余 0.7 按 2:1 分给另外两路
    const weights = rebalanceChannelWeights({ ...DEFAULT_CHANNEL_WEIGHTS }, 'VECTOR', 0.3);

    // then
    expect(weights).toEqual({ VECTOR: 0.3, BM25: 0.4, GRAPH: 0.3 });
    expectLegalTotal(weights);
  });

  it('should_两路各让到下限_when_rebalanceChannelWeights_given_VECTOR拖到上限0.8', () => {
    // when
    const weights = rebalanceChannelWeights({ ...DEFAULT_CHANNEL_WEIGHTS }, 'VECTOR', 0.8);

    // then：其余两路只剩各 1 刻度，仍无 0 权通道
    expect(weights).toEqual({ VECTOR: 0.8, BM25: 0.1, GRAPH: 0.1 });
    expectLegalTotal(weights);
  });

  it('should_夹取到0.1_when_rebalanceChannelWeights_given_拖到0或负数', () => {
    // when
    const zero = rebalanceChannelWeights({ ...DEFAULT_CHANNEL_WEIGHTS }, 'VECTOR', 0);
    const negative = rebalanceChannelWeights({ ...DEFAULT_CHANNEL_WEIGHTS }, 'VECTOR', -0.5);

    // then：0 权通道被后端拒绝，故夹取到下限 0.1，其余两路回补
    expect(zero).toEqual({ VECTOR: 0.1, BM25: 0.6, GRAPH: 0.3 });
    expect(negative).toEqual(zero);
    expectLegalTotal(zero);
  });

  it('should_按0.1就近取整_when_rebalanceChannelWeights_given_非刻度值0.35', () => {
    // when
    const weights = rebalanceChannelWeights({ ...DEFAULT_CHANNEL_WEIGHTS }, 'GRAPH', 0.35);

    // then：0.35 落到 0.4（四舍五入），VECTOR 与 BM25 按 7:2 比例分摊剩余 0.6，
    // 整数刻度取整并保底 1 刻度后为 0.4 / 0.2
    expect(weights.GRAPH).toBe(0.4);
    expectLegalTotal(weights);
  });

  it('should_保持当前刻度_when_rebalanceChannelWeights_given_非法目标值', () => {
    // when：NaN 不改变目标通道，其余两路按原比例重分（默认值本身已合法，故结果不变）
    const weights = rebalanceChannelWeights({ ...DEFAULT_CHANNEL_WEIGHTS }, 'VECTOR', Number.NaN);

    // then
    expect(weights).toEqual(DEFAULT_CHANNEL_WEIGHTS);
  });

  it('should_归一脏数据_when_rebalanceChannelWeights_given_合计1.4且缺路', () => {
    // given：存量脏配置合计 1.4，且缺少 GRAPH 键
    const dirty = { VECTOR: 0.6, BM25: 0.5 };

    // when：以其中一路为目标（目标固定）后其余两路重新分配
    const weights = rebalanceChannelWeights(dirty, 'VECTOR', 0.4);

    // then：GRAPH 回退默认 0.1 参与分配，合计收敛到 1.0
    expect(weights.VECTOR).toBe(0.4);
    expectLegalTotal(weights);
  });

  it('should_返回归一权重_when_rebalanceChannelWeights_given_非法通道名', () => {
    // when
    const weights = rebalanceChannelWeights({ VECTOR: 0.5, BM25: 0.3, GRAPH: 0.3 }, 'RERANK', 0.2);

    // then：未知通道不消费目标值，仅把当前权重归一为 10 刻度
    expect(weights).toEqual({ VECTOR: 0.4, BM25: 0.3, GRAPH: 0.3 });
    expectLegalTotal(weights);
  });

  it('should_合计恒为1_when_rebalanceChannelWeights_given_连续拖动三路', () => {
    // given
    let weights = { ...DEFAULT_CHANNEL_WEIGHTS };
    const steps: Array<[string, number]> = [
      ['VECTOR', 0.5], ['BM25', 0.8], ['GRAPH', 0.1], ['BM25', 0.6], ['VECTOR', Number.NaN],
    ];

    // when：依次拖动三路（含边界与非法值）
    steps.forEach(([channel, value]) => {
      weights = rebalanceChannelWeights(weights, channel, value);
      expectLegalTotal(weights);
    });

    // then
    expectLegalTotal(weights);
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

  it('should_报通道数量越界或非整数_when_validateRetrievalConfig_given_MIX三值均非法', () => {
    // when
    const errors = validateRetrievalConfig(configWith((c) => {
      c.strategyType = 'MIX';
      c.channelQueryCountConfig = {
        vectorQueryCount: QUERY_COUNT_MIN - 1,
        bm25QueryCount: QUERY_COUNT_MAX + 1,
        graphQueryCount: 20.5,
      };
    }));

    // then
    expect(errors['channelQueryCountConfig.vectorQueryCount']).toBeTruthy();
    expect(errors['channelQueryCountConfig.bm25QueryCount']).toBeTruthy();
    expect(errors['channelQueryCountConfig.graphQueryCount']).toBeTruthy();
  });

  it('should_NAIVE不校验图谱数量_when_validateRetrievalConfig_given_图谱数量非法', () => {
    // when：NAIVE 序列化不提交图谱数量，隐藏字段不参与校验
    const errors = validateRetrievalConfig(configWith((c) => {
      c.channelQueryCountConfig = {
        vectorQueryCount: DEFAULT_QUERY_COUNT,
        bm25QueryCount: DEFAULT_QUERY_COUNT,
        graphQueryCount: 0,
      };
    }));

    // then
    expect(errors['channelQueryCountConfig.graphQueryCount']).toBeFalsy();
    expect(validateRetrievalConfig(configWith((c) => {
      c.strategyType = 'MIX';
      c.channelQueryCountConfig = {
        vectorQueryCount: DEFAULT_QUERY_COUNT,
        bm25QueryCount: DEFAULT_QUERY_COUNT,
        graphQueryCount: 0,
      };
    }))).toHaveProperty('channelQueryCountConfig.graphQueryCount');
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

  it('should_报权重合计大于1_when_validateRetrievalConfig_given_三路合计1.2', () => {
    // when
    const errors = validateRetrievalConfig(configWith((c) => {
      c.fusionConfig = {
        fusionType: 'WEIGHTED_SUM',
        rrfK: DEFAULT_RRF_K,
        channelDenseWeight: { VECTOR: 0.5, BM25: 0.4, GRAPH: 0.3 },
      };
    }));

    // then：单路都在 (0,1) 内，但合计 1.2 超过占比上限
    expect(errors['fusionConfig.channelDenseWeight']).toBeTruthy();
  });

  it('should_不报合计错误_when_validateRetrievalConfig_given_三路合计恰为1', () => {
    // when：0.7 + 0.2 + 0.1 的浮点累加不得触发误判
    const errors = validateRetrievalConfig(configWith((c) => {
      c.fusionConfig = {
        fusionType: 'WEIGHTED_SUM',
        rrfK: DEFAULT_RRF_K,
        channelDenseWeight: { VECTOR: 0.7, BM25: 0.2, GRAPH: 0.1 },
      };
    }));

    // then
    expect(errors['fusionConfig.channelDenseWeight']).toBeFalsy();
    expect(errors).toEqual({});
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
