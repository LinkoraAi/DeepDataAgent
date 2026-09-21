package com.linkroa.deepdataagent.rag.domain.service;

/**
 * 检索链路常量（与摄入链路取值一致）。
 * <p>常量单点挂载，避免检索域魔法值（开发规范【强制】）；取值与参考实现对齐。</p>
 */
public final class RetrievalConstants {

    /** RRF 融合常数 k（与 {@code FusionStrategyConfig.DEFAULT_RRF_K} 一致） */
    public static final int RRF_K = 60;

    /** 上下文构建预留缓冲 token（参考从 maxTotalTokens 中扣减） */
    public static final int BUFFER_TOKENS = 200;

    /**
     * 图谱 1 跳关联边返回上限默认值（度数+权重双键降序 Top-50）。
     * <p>可被 {@code RetrievalQuery.graphEdgeTop} 覆盖；取值即历史常量化时代行为，缺省不变。</p>
     */
    public static final int GRAPH_EDGE_TOP = 50;

    /**
     * 图谱 chunk 三源（实体/关系/关联边）每源抽取上限默认值。
     * <p>可被 {@code RetrievalQuery.graphEdgeChunkLimit} 覆盖；取值即历史常量化时代行为，缺省不变。</p>
     */
    public static final int GRAPH_EDGE_CHUNK_LIMIT = 5;

    /** 精排保留 top K 默认值（rerank_top_k） */
    public static final int RERANK_TOP_K_DEFAULT = 50;

    /** 每通道向量召回数量默认值（参考 QueryParam） */
    public static final int DEFAULT_TOP_K = 20;

    /** 引用列表最大条数（参考引用列表，{@code [n] 来源文件名}） */
    public static final int REFERENCE_MAX_COUNT = 5;

    /**
     * 全局兜底语言全名（由历史裸语言码 {@code zh} 退役为全局兜底缺省值）。
     * <p>知识库未配置语言且全局摄入语言配置缺失时的最终兜底；同时参与检索侧
     * 关键词 / 改写缓存键的语言维度 MD5 口径。值域为 11 语言全名（见
     * {@code KbLanguage}），模板套按「{@code Chinese} 用 zh 套、其余语言用 en 套」选择。</p>
     */
    public static final String DEFAULT_LANGUAGE = "Chinese";

    private RetrievalConstants() {
        // 工具常量类禁止实例化
    }
}