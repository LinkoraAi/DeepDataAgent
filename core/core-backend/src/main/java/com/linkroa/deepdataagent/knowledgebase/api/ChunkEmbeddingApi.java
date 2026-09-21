package com.linkroa.deepdataagent.knowledgebase.api;

/**
 * 切片向量化契约（跨 BC 服务边界，依赖倒置端口）。
 * <p>提供方为检索增强 BC（实现类位于 {@code rag.infrastructure}，如 {@code DefaultChunkEmbeddingApi}），
 * 消费方为知识库 BC 的人工切片变更链路（当前为 BC 间进程内消费，不提供 REST 端点）。</p>
 *
 * <p><b>依赖方向</b>：本接口归知识库 BC 的 api 包、实现归检索增强 BC，与既有跨 BC 端口
 * （如 {@link DocumentGraphConvergenceApi}）同模式；知识库 BC MUST NOT 依赖检索增强 BC 的
 * 任何实现类型。向量能力与嵌入模型 profile 解析属于检索增强 BC，向量维度与编码细节
 * （pgvector 字面量）亦留在实现侧，知识库 BC 无需感知。</p>
 *
 * <p><b>调用约束</b>：调用方 MUST 在数据库事务之外调用本契约——向量化是远程调用，
 * 本契约 MUST NOT 出现在任何数据库事务体内（持数据库连接等待 HTTP 会耗尽连接池）。
 * 调用方应在本契约返回后再开启事务，于同一事务内写入切片行与派生表示行，共提交或整体回滚。</p>
 *
 * <p><b>降级口径</b>：目标知识库未配置嵌入模型时，本契约以「无向量」返回 {@code null}
 * （MUST NOT 抛异常），由调用方跳过向量表示写入、照常写入全文表示；
 * 上游不可达或返回数量与请求不一致时抛可归因异常，由调用方使本次切片变更整体失败且零写入。</p>
 */
public interface ChunkEmbeddingApi {

    /**
     * 按切片正文同步取得该切片的向量表示字面量。
     * <p>实现方按知识库维度解析生效的嵌入模型，对给定正文调用向量化上游，
     * 并把首个向量编码为存储层可直接落库的 pgvector 文本字面量（形如 {@code [0.5,0.25,0]}，
     * 定点十进制、去尾零、禁用科学计数法）。</p>
     *
     * <p>降级：知识库未配置嵌入模型（profileId 缺失或空白）时返回 {@code null}，
     * 且不发起任何远程调用。</p>
     *
     * @param kbId         目标知识库主键，必填；为空抛参数错误异常
     * @param chunkContent 待向量化的切片正文，必填（空白抛参数错误异常，避免静默丢失向量召回）
     * @return pgvector 向量字面量；知识库未配置嵌入模型或上游返回空向量时返回 {@code null}（「无向量」）
     * @throws IllegalArgumentException kbId 为空或切片正文空白
     * @throws IllegalStateException    向量化上游不可达，或返回结果数量与请求不一致（可归因上抛）
     */
    String embedLiteral(Long kbId, String chunkContent);
}