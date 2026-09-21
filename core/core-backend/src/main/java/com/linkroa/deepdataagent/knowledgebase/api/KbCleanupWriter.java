package com.linkroa.deepdataagent.knowledgebase.api;

/**
 * 整库清退回写契约（跨 BC 服务边界，依赖倒置端口）。
 * <p>提供方与实现方均为 knowledgebase BC 自身（实现类 {@code DefaultKbCleanupWriter}），
 * 消费方为 rag BC 的清退编排服务（{@code KbCleanupOrchestrationService}）：RAG 侧按
 * 固定顺序驱动本契约清空知识库自有数据，MUST NOT 直接读写知识库 BC 的数据表。</p>
 *
 * <p>失败分级（「零重试」）：资产回收为可归因步骤——存储异常
 * SHALL 抛出可归因异常交消费方即时置 DELETE_FAILED 留痕并终止本任务，SHALL NOT 部分成功却静默；
 * 数据分批清退为纯数据库操作，每批独立小事务提交（事务规范：批 500~1000 条、远程 IO 不入事务）。</p>
 *
 * <p>清退顺序由消费方编排保证：资产回收 → 派生数据（表示 + 切片）→ 文档，
 * 即「表示 → 切片 → 文档」固定删序，本契约各方法不隐式触发其他步骤。彻底物理删体系下
 * 各步骤均为标准 {@code delete}（即 {@code DELETE FROM}，无 @TableLogic、无绕过补丁），
 * 取批为普通查询，重复调用即幂等空转。</p>
 */
public interface KbCleanupWriter {

    /**
     * 按知识库回收全部文件资产（对象存储远程 IO，事务外执行）。
     * <p>覆盖媒体图片整库前缀与源文件对象前缀（{@code rag/{kbId}/}）；「对象不存在」视为成功，
     * 天然幂等可重入。存储不可达等异常原样上抛供消费方归因重试，不在本契约内吞没。</p>
     *
     * @param kbId 待回收资产的知识库主键，必填
     * @throws RuntimeException         对象存储操作失败（可归因异常，消费方决定重试策略）
     * @throws IllegalArgumentException kbId 为空
     */
    void cleanupAssetsByKnowledgeBase(Long kbId);

    /**
     * 分批物理清退知识库自有派生数据（「表示 → 切片」固定删序，单批一个独立小事务）。
     * <p>每批先删切片的 1:1 向量/全文表示、再物理删切片本体，并同事务完成图谱账本收敛
     * （复用切片物理删除原语，实现方内即此序），同批原子生效、不遗留无主影子行；
     * 重复调用直至返回 0 即代表该库派生数据清空（幂等可续跑）。MUST NOT 触碰文档行。</p>
     *
     * @param kbId      待清退知识库主键，必填
     * @param batchSize 单批切片条数上限（实现方按事务规范 500~1000 封顶解释）
     * @return 本批清退的切片行数（取批命中数）；返回 0 表示已清空
     * @throws IllegalArgumentException kbId 为空或 batchSize 非法
     */
    int cleanupDerivedDataBatch(Long kbId, int batchSize);

    /**
     * 分批物理清退知识库内文档行（单批一个独立小事务）。
     * <p>整库消亡语义下的终态清退：按主键升序取批后标准 {@code delete} 即物理 DELETE（行消失、
     * 无逻辑删除痕迹）；重复调用直至返回 0 即代表该库文档清空（幂等可续跑）。
     * 仅在派生数据清退完成后由消费方调用。</p>
     *
     * @param kbId      待清退知识库主键，必填
     * @param batchSize 单批文档条数上限（实现方按事务规范 500~1000 封顶解释）
     * @return 本批清退的文档行数（取批命中数）；返回 0 表示已清空
     * @throws IllegalArgumentException kbId 为空或 batchSize 非法
     */
    int cleanupDocumentsBatch(Long kbId, int batchSize);
}
