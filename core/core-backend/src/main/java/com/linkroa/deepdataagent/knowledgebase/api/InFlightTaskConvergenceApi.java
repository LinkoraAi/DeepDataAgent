package com.linkroa.deepdataagent.knowledgebase.api;

/**
 * 在飞任务收敛契约（跨 BC 服务边界）。
 * <p>提供方与实现方均为 knowledgebase BC（收敛涉及的业务状态归本 BC 所有），
 * 消费方为 rag BC 的启动恢复与停机收敛流程。接口以 KB 侧业务实体为参数，
 * 不产生 knowledgebase → rag 的反向编译依赖。</p>
 *
 * <p>语义＝<strong>「状态一致性校验 + 条件置态」原语</strong>：任何由在飞注册表驱动的状态变更
 * MUST 先读取数据库权威状态并与之比对，仅当数据库状态与注册表凭据所隐含的在飞态一致时才置目标态；
 * MUST NOT 以「注册表中仍有该 ID」为直接依据改写数据库状态。置态写入 MUST 以数据库当前状态为
 * 前置条件（条件更新），MUST NOT 出现无条件覆盖。</p>
 *
 * <p>不一致分支（数据库已是终态、已被并发推进、或数据行已不存在）MUST NOT 变更数据库状态，
 * 且 MUST NOT 产生任何数据库写操作。</p>
 *
 * @author DeepDataAgent
 */
public interface InFlightTaskConvergenceApi {

    /**
     * 一致性校验收敛文档解析：仅当文档仍处于 {@code PENDING} 或 {@code PROCESSING} 时置 {@code FAILED}。
     *
     * @param documentId   文档主键，可为空（空视为不一致，直接返回 false）
     * @param errorMessage 失败原因（写入 {@code error_message}），可为空
     * @return {@code true} 表示一致且已置 {@code FAILED}；{@code false} 表示不一致或行不存在（零写操作）
     * @throws RuntimeException 置态写入失败；调用方 SHALL 保留注册表成员并留痕
     */
    boolean convergeIngestion(Long documentId, String errorMessage);

    /**
     * 一致性校验收敛文档清退：仅当文档仍处于 {@code DELETING} 时置 {@code DELETE_FAILED}。
     *
     * @param documentId   文档主键，可为空（空视为不一致，直接返回 false）
     * @param errorMessage 失败留痕（写入 {@code error_message}），可为空
     * @return {@code true} 表示一致且已置 {@code DELETE_FAILED}；{@code false} 表示不一致或行不存在（零写操作）
     * @throws RuntimeException 置态写入失败；调用方 SHALL 保留注册表成员并留痕
     */
    boolean convergeDocumentCleanup(Long documentId, String errorMessage);

    /**
     * 一致性校验收敛知识库清退：仅当知识库仍处于 {@code DELETING} 时置 {@code DELETE_FAILED}。
     *
     * @param kbId         知识库主键，可为空（空视为不一致，直接返回 false）
     * @param errorMessage 失败留痕（写入 {@code error_message}），可为空
     * @return {@code true} 表示一致且已置 {@code DELETE_FAILED}；{@code false} 表示不一致或行不存在（零写操作）
     * @throws RuntimeException 置态写入失败；调用方 SHALL 保留注册表成员并留痕
     */
    boolean convergeKbCleanup(Long kbId, String errorMessage);
}