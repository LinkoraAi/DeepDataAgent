package com.linkroa.deepdataagent.knowledgebase.infrastructure;

import com.linkroa.deepdataagent.knowledgebase.api.KbCleanupWriter;
import com.linkroa.deepdataagent.knowledgebase.application.service.ChunkPhysicalDeleteService;
import com.linkroa.deepdataagent.knowledgebase.application.service.MediaImageCleanupService;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.KbAuditFieldUtils;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.mapper.ChunkMapper;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.mapper.DocumentMapper;
import org.apache.commons.lang3.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;

import java.util.List;

/**
 * {@link KbCleanupWriter} 契约的默认实现（整库清退回写端口）。
 * <p>彻底物理删体系下本类不再持有任何「绕过逻辑删」补丁语句：实体无 {@code is_deleted} 列、
 * 无 {@code @TableLogic}，标准 {@code delete} 即 {@code DELETE FROM}，行消失即「已删除」——
 * 取批走普通查询，删除即物理删除，天然幂等可重入（已清退的行不会再被取到，二次执行即空转返回 0）。</p>
 * <p>步骤口径（与 RAG 侧清退编排的次序一致）：</p>
 * <ol>
 *   <li>{@link #cleanupAssetsByKnowledgeBase(Long)}：Storage 先删——整库前缀 {@code rag/{kbId}/}
 *       单次清退（桶概念已退役，前缀仅由 kbId 派生），对象存储远程 IO 全程事务外；</li>
 *   <li>{@link #cleanupDerivedDataBatch(Long, int)}：按主键升序取一批切片 ID，整批交给
 *       {@link ChunkPhysicalDeleteService#deleteChunks(java.util.Collection, String, boolean)} 原语
 *       （两段式：图谱重建计算在事务外先行，事务内「图谱账本收敛与缓存回收 → 1:1 表示
 *       「向量 → 全文」→ 切片行」同一小事务原子生效——整库清退跨文档批次由原语按组取批次内
 *       最小文档ID作审计锚点）；整库场景
 *       {@code syncDocumentChunkCount = false}——文档行随后即终删，回写分块计数属白做功；
 *       图谱收敛由原语按批次来源数据推导（整库批以解析块为主，含解析块即同事务收敛，
 *       不依赖后置的清四表步骤）；</li>
 *   <li>{@link #cleanupDocumentsBatch(Long, int)}：按主键升序取一批文档 ID，单批一个小事务内
 *       {@code deleteByIds} 物理删行（仅在派生数据清退完成后由消费方调用）。</li>
 * </ol>
 * <p>事务规范（数据库事务处理规范）：远程 IO 一律置于事务外；每批一个独立小事务、批大小按
 * 500~1000 封顶截断；批删恒按主键升序取批与删除，固定加锁顺序，杜绝交叉死锁。</p>
 *
 * @author DeepDataAgent
 */
@Component
public class DefaultKbCleanupWriter implements KbCleanupWriter {

    private static final Logger log = LoggerFactory.getLogger(DefaultKbCleanupWriter.class);

    /** 单批物理删除行数上限（事务规范：批量操作建议 500~1000 条/批，此处取上限封顶） */
    private static final int MAX_BATCH_SIZE = 1000;

    private final MediaImageCleanupService mediaImageCleanupService;
    private final ChunkMapper chunkMapper;
    private final ChunkPhysicalDeleteService chunkPhysicalDeleteService;
    private final DocumentMapper documentMapper;
    private final TransactionTemplate transactionTemplate;

    public DefaultKbCleanupWriter(MediaImageCleanupService mediaImageCleanupService,
                                  ChunkMapper chunkMapper,
                                  ChunkPhysicalDeleteService chunkPhysicalDeleteService,
                                  DocumentMapper documentMapper,
                                  TransactionTemplate transactionTemplate) {
        this.mediaImageCleanupService = mediaImageCleanupService;
        this.chunkMapper = chunkMapper;
        this.chunkPhysicalDeleteService = chunkPhysicalDeleteService;
        this.documentMapper = documentMapper;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 按知识库回收全部文件资产（对象存储远程 IO，事务外执行）。
     * <p>委托 {@link MediaImageCleanupService#cleanupKnowledgeBaseImages} 按整库前缀
     * {@code rag/{kbId}/} 单次幂等清退：该整库前缀天然覆盖源文件对象段
     * {@code rag/{kbId}/source/} 与文档媒体图片段，无需另写源文件前缀清退逻辑，
     * 也无需从文档行引用发现任何清理目标（桶概念已退役，前缀仅由 kbId 派生）。</p>
     * <p>失败分级：本步骤为可归因步骤，存储异常原样上抛由消费方即时置 DELETE_FAILED 留痕（零重试），
     * 不吞没、不部分成功却静默；「对象不存在」在委托服务内视为成功，天然幂等可重入。</p>
     *
     * @param kbId 待回收资产的知识库主键，必填
     * @throws RuntimeException         对象存储操作失败（可归因异常，消费方据此终止本清退任务）
     * @throws IllegalArgumentException kbId 为空
     */
    @Override
    public void cleanupAssetsByKnowledgeBase(Long kbId) {
        if (ObjectUtils.isEmpty(kbId)) {
            throw new IllegalArgumentException("知识库ID不能为空");
        }
        mediaImageCleanupService.cleanupKnowledgeBaseImages(kbId);
        log.info("整库文件资产回收完成, kbId={}", kbId);
    }

    /**
     * 分批物理清退知识库自有派生数据（图谱收敛与缓存回收 + 1:1 表示 + 切片行，单批一个原语事务，
     * 重建计算在事务外先行）。
     * <p>取批为普通查询（无逻辑删过滤干扰，按主键升序固定加锁序）；本批切片整体委托
     * {@link ChunkPhysicalDeleteService#deleteChunks(java.util.Collection, String, boolean)}
     * 完成「图谱收敛与缓存回收 → 向量 → 全文 → 切片行」同事务清退，操作人取系统缺省值。
     * 开关口径：{@code syncDocumentChunkCount=false}（文档行随后即终删，回写分块计数属白做功）；
     * 图谱收敛由原语按批次来源数据推导（整库批以解析块为主，含解析块即同事务收敛，
     * 本链的图谱清退不依赖后置步骤）。
     * 返回本批清退的切片行数（取批命中数）；返回 0 表示该库派生数据已清空（幂等可续跑）。</p>
     *
     * @param kbId      待清退知识库主键，必填
     * @param batchSize 单批切片条数上限（按事务规范 {@value #MAX_BATCH_SIZE} 封顶解释）
     * @return 本批清退的切片行数；返回 0 表示已清空
     * @throws IllegalArgumentException kbId 为空或 batchSize 非法
     */
    @Override
    public int cleanupDerivedDataBatch(Long kbId, int batchSize) {
        if (ObjectUtils.isEmpty(kbId)) {
            throw new IllegalArgumentException("知识库ID不能为空");
        }
        int limit = resolveBatchLimit(batchSize);
        List<Long> chunkIds = chunkMapper.selectIdsByKbId(kbId, limit);
        if (CollectionUtils.isEmpty(chunkIds)) {
            return 0;
        }
        // 整库场景不回写文档分块计数（文档行随后即终删）；图谱收敛由原语按批次来源推导（不依赖后置清四表步骤）；
        // 事务边界由删除原语自持（重建计算在事务外、DB 写段单事务；本链调用处无外层事务）
        chunkPhysicalDeleteService.deleteChunks(chunkIds, KbAuditFieldUtils.DEFAULT_OPERATOR, false);
        log.info("整库清退派生数据一批: kbId={}, chunkRows={}", kbId, chunkIds.size());
        return chunkIds.size();
    }

    /**
     * 分批物理清退知识库内文档行（整库消亡语义的终态清退）。
     * <p>按主键升序取批后，单批一个独立小事务内 {@code deleteByIds} 物理删行（标准 delete 即
     * {@code DELETE FROM}，行消失）。返回本批清退的文档行数（取批命中数）；返回 0 表示该库文档已清空
     * （幂等可续跑）。本步骤在派生数据清退完成后由消费方调用，MUST NOT 早于派生数据清退执行。</p>
     *
     * @param kbId      待清退知识库主键，必填
     * @param batchSize 单批文档条数上限（按事务规范 {@value #MAX_BATCH_SIZE} 封顶解释）
     * @return 本批清退的文档行数；返回 0 表示已清空
     * @throws IllegalArgumentException kbId 为空或 batchSize 非法
     */
    @Override
    public int cleanupDocumentsBatch(Long kbId, int batchSize) {
        if (ObjectUtils.isEmpty(kbId)) {
            throw new IllegalArgumentException("知识库ID不能为空");
        }
        int limit = resolveBatchLimit(batchSize);
        List<Long> documentIds = documentMapper.selectIdsByKbId(kbId, limit);
        if (CollectionUtils.isEmpty(documentIds)) {
            return 0;
        }
        Integer deleted = transactionTemplate.execute(status -> documentMapper.deleteByIds(documentIds));
        log.info("整库清退文档一批: kbId={}, docRows={}, affectedRows={}",
                kbId, documentIds.size(), ObjectUtils.isEmpty(deleted) ? 0 : deleted);
        return documentIds.size();
    }

    /**
     * 解释批次上限：非法（&lt; 1）抛出参数异常，超过事务规范上限（{@value #MAX_BATCH_SIZE}）封顶截断。
     */
    private int resolveBatchLimit(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("批次大小必须大于 0");
        }
        return Math.min(batchSize, MAX_BATCH_SIZE);
    }
}
