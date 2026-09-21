package com.linkroa.deepdataagent.knowledgebase.domain.repository;

import com.linkroa.deepdataagent.knowledgebase.domain.model.Chunk;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ChunkSource;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 切片仓储接口。
 */
public interface ChunkRepository {

    Chunk save(Chunk chunk);

    Chunk update(Chunk chunk);

    /**
     * 批量写入切片（同一文档内），返回回填主键后的切片集合。
     * <p>用于切片整篇替换回写场景：内部按批次大小分片提交多值 INSERT，
     * 由于多值 INSERT 无法可靠回填主键，写入后按 documentId 回查以获得 sequence→id 映射。
     * 要求传入切片属于同一文档。</p>
     *
     * @param chunks   待写入切片集合
     * @param operator 操作人标识，用于补齐 created_by / updated_by
     * @return 回填主键后的切片集合
     */
    List<Chunk> saveBatch(List<Chunk> chunks, String operator);

    Optional<Chunk> findById(Long id);

    /**
     * 按知识库ID与切片ID集合批量回取切片（读侧只读，供检索消费）。
     * <p>带 {@code kbId} 等值过滤保证单库隔离：仅回取属于该知识库的切片，跨库 ID 不命中；
     * 物理删体系下行消失即「已删除」，已删切片天然不在结果中。</p>
     * <p><b>可见性约束</b>：仅回取所属文档处于「已处理」（{@code PROCESSED}）状态的切片——
     * 摄入中（{@code PENDING / PROCESSING}，含重新解析窗口）、已失败（{@code FAILED}）与删除链
     * （{@code DELETING / DELETE_FAILED}）状态的文档，其切片一律按未命中处理，
     * MUST NOT 进入检索结果。管理侧切片列表与详情走按文档分页的其它查询，不受本约束影响。</p>
     *
     * @param kbId     知识库ID，可为空；为空返回空列表
     * @param chunkIds 切片ID集合，可为空；为空返回空列表
     * @return 命中切片列表（按 id 升序）；无命中返回空列表
     */
    List<Chunk> findByKbIdAndIds(Long kbId, Collection<Long> chunkIds);

    List<Chunk> findByDocumentId(Long documentId, int page, int size);

    /**
     * 列举某文档全部切片的「序号 → 主键」映射（不分页，摄入产物溯源回填用）。
     * <p>仅回读 sequence 与 id 两列，不携带切片正文等重内容；返回的映射保持 sequence 升序。
     * 因切片聚合根要求正文非空，本方法不返回 {@link Chunk} 而返回轻量投影映射。</p>
     *
     * @param documentId 文档ID，可为空；为空返回空映射
     * @return sequence → 切片主键映射（sequence 升序）；无切片返回空映射
     */
    Map<Integer, Long> findIdAndSequenceByDocumentId(Long documentId);

    long countByDocumentId(Long documentId);

    List<Chunk> findByKbId(Long kbId, Long documentId, Integer sequence, String keyword, int page, int size);

    long countByKbId(Long kbId, Long documentId, Integer sequence, String keyword);

    void deleteById(Long id);

    void deleteByDocumentId(Long documentId);

    void deleteByKbId(Long kbId);

    /**
     * 统计知识库内切片数。
     */
    long countByKbIdOnly(Long kbId);

    /**
     * 分批取知识库内存活切片的 ID（id 升序，清退调度用）。
     * <p>物理删体系下已删切片行不存在，故每轮从头取 LIMIT 即可：上一批删除后自然轮到下一批，
     * 中断后重跑幂等、无需游标。</p>
     *
     * @param kbId  知识库ID
     * @param limit 单批上限
     * @return 切片 ID 列表（升序）；无命中返回空列表
     */
    List<Long> findIdsByKbId(Long kbId, int limit);

    /**
     * 分批取文档内存活切片的 ID（id 升序，文档删除链分批清退用）。
     * <p>物理删体系下已删切片行不存在，故每轮从头取 LIMIT 即可：上一批删除后自然轮到下一批，
     * 中断后重跑幂等、无需游标。</p>
     *
     * @param documentId 文档ID
     * @param limit      单批上限
     * @return 切片 ID 列表（升序）；无命中返回空列表
     */
    List<Long> findIdsByDocumentId(Long documentId, int limit);

    /**
     * 按 ID 集合批量物理删除切片（标准 delete，无 @TableLogic，行消失即「已删除」）。
     *
     * @param ids 切片 ID 集合（空集合直接返回 0）
     * @return 实际删除行数
     */
    int deleteByIds(List<Long> ids);

    /**
     * 按切片ID集合回取「切片主键 → 所属文档ID」轻量投影（删除链专用，不回读切片正文）。
     * <p>用途有二：①切片物理删除原语在删行<b>之前</b>确定受影响文档集合，供 chunkCount 回写分组；
     * ②人工删除入口据此判定切片是否存在（缺失键 = 行已物理消失，调用方按 404 处理）与所属文档闸门。</p>
     * <p>彻底物理删体系：已删切片行物理不存在，天然不出现在投影中。</p>
     *
     * @param chunkIds 切片ID集合，可为空；为空返回空映射（零 DB 交互）
     * @return 切片主键 → 文档ID 映射（id 升序）；仅包含当前存活切片行
     */
    Map<Long, Long> findDocumentIdsByChunkIds(Collection<Long> chunkIds);

    /**
     * 按切片ID集合回取「切片主键 → 所属知识库ID」轻量投影（删除链专用，不回读切片正文）。
     * <p>切片物理删除原语在删行<b>之前</b>取数：图谱账本收敛语句按 {@code kb_id} 等值条件收窄
     * 扫描面，而切片行一旦物理消失即无从反查所属知识库（与
     * {@link #findDocumentIdsByChunkIds(Collection)} 同一取数时机、同一存在性口径）。</p>
     * <p>彻底物理删体系：已删切片行物理不存在，天然不出现在投影中。</p>
     *
     * @param chunkIds 切片ID集合，可为空；为空返回空映射（零 DB 交互）
     * @return 切片主键 → 知识库ID 映射（id 升序）；仅包含当前存活切片行
     */
    Map<Long, Long> findKbIdsByChunkIds(Collection<Long> chunkIds);

    /**
     * 回取某文档全部切片的多模态图片对象引用投影（切片主键 → {@code chunk.s3_file} JSON）。
     * <p>仅回读 id 与 s3_file 两列并过滤空白引用，结果集规模等于该文档的图片切片数（远小于切片总数），
     * 供删除前的「同文档对象引用计数」使用：本批之外的存活引用决定对象是否可回收。</p>
     * <p>彻底物理删体系：已删切片行物理不存在，其引用天然不计入存活集合。</p>
     *
     * @param documentId 文档ID，可为空；为空返回空映射（零 DB 交互）
     * @return 切片主键 → 媒体对象引用 JSON 映射（id 升序）；无图片切片返回空映射
     */
    Map<Long, String> findMediaReferencesByDocumentId(Long documentId);

    /**
     * 查询文档内当前最大分块序号（人工新增序号服务端分配的前置查询）。
     * <p>新分块序号 = 本方法返回值加一，人工块因此恒位于文档分块序列末尾；
     * 返回 null 表示文档尚无切片，调用方自 0 起分配。调用方 MUST 在事务内持有文档行锁后执行
     * （同文档并发新增被行锁串行化，天然不触发 {@code uk_chunk_doc_seq} 唯一约束冲突）。</p>
     *
     * @param documentId 文档ID，可为空；为空返回 null
     * @return 当前最大序号；文档无切片返回 null
     */
    Integer findMaxSequenceByDocumentId(Long documentId);

    /**
     * 按切片ID集合回取「切片主键 → 来源标识」轻量投影（不回读切片正文等重列）。
     * <p>消费面有二：①人工删除入口校验本批全部为「人工新增」来源（含解析块整批拒绝）；
     * ②切片物理删除原语在删行<b>之前</b>推导本批是否含解析来源，以数据决定图谱账本收敛
     * 触发与否（与 {@link #findKbIdsByChunkIds(Collection)} 同一取数时机——行一旦消失即无从反查来源）。</p>
     * <p>彻底物理删体系：已删切片行物理不存在，天然不出现在投影中；
     * 来源列缺失的异常行由领域兜底视为「解析产生」（拒绝删除优于误删的安全方向）。</p>
     *
     * @param chunkIds 切片ID集合，可为空；为空返回空映射（零 DB 交互）
     * @return 切片主键 → 来源标识 映射（id 升序）；仅包含当前存活切片行
     */
    Map<Long, ChunkSource> findSourcesByChunkIds(Collection<Long> chunkIds);
}
