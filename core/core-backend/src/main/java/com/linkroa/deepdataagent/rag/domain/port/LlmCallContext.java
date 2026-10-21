package com.linkroa.deepdataagent.rag.domain.port;

import org.apache.commons.lang3.StringUtils;

/**
 * 模型调用上下文值对象（观测用，不参与缓存键计算）。
 * <p>把「这次调用属于哪个阶段、哪篇文档、哪个分块、第几轮」显式带到基础设施层，
 * 使调用级日志无需依赖线程上下文即可完成归因。字段均可空：查询、向量化、重排等
 * 不属于摄入分块链路的调用只携带阶段。</p>
 *
 * @param stage         调用阶段（非空，空白回落 {@link #STAGE_OTHER}）
 * @param documentId     文档主键（可空）
 * @param chunkSequence 分块序号（可空）
 * @param round         抽取轮次（0 表示首轮，正整数表示补漏轮次；可空）
 * @author DeepDataAgent
 */
public record LlmCallContext(
        String stage,
        Long documentId,
        Integer chunkSequence,
        Integer round
) {

    /** 阶段：多模态描述 */
    public static final String STAGE_DESCRIBE = "DESCRIBE";

    /** 阶段：实体/关系抽取首轮 */
    public static final String STAGE_EXTRACT = "EXTRACT";

    /** 阶段：实体/关系抽取补漏轮次 */
    public static final String STAGE_EXTRACT_GLEANING = "EXTRACT_GLEANING";

    /** 阶段：实体/关系描述摘要 */
    public static final String STAGE_SUMMARY = "SUMMARY";

    /** 阶段：向量化 */
    public static final String STAGE_EMBEDDING = "EMBEDDING";

    /** 阶段：重排 */
    public static final String STAGE_RERANK = "RERANK";

    /** 阶段：查询理解与答案生成 */
    public static final String STAGE_QUERY = "QUERY";

    /** 阶段：其他/未归类 */
    public static final String STAGE_OTHER = "OTHER";

    /**
     * 紧凑构造器：阶段空白回落 {@link #STAGE_OTHER}，其余字段原样保留。
     */
    public LlmCallContext {
        stage = StringUtils.defaultIfBlank(stage, STAGE_OTHER);
    }

    /**
     * 便捷构造：仅携带阶段（无文档/分块上下文）。
     *
     * @param stage 调用阶段
     * @return 调用上下文
     */
    public static LlmCallContext of(String stage) {
        return new LlmCallContext(stage, null, null, null);
    }

    /**
     * 便捷构造：抽取类调用的完整上下文。
     *
     * @param stage         调用阶段
     * @param documentId    文档主键
     * @param chunkSequence 分块序号
     * @param round         轮次
     * @return 调用上下文
     */
    public static LlmCallContext of(String stage, Long documentId, Integer chunkSequence, Integer round) {
        return new LlmCallContext(stage, documentId, chunkSequence, round);
    }
}
