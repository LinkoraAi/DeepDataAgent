package com.linkroa.deepdataagent.rag.domain.repository;

import com.linkroa.deepdataagent.rag.domain.model.EntityInfoVector;
import com.linkroa.deepdataagent.rag.domain.model.EntityLedgerSnapshot;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 实体向量仓储端口（对应 {@code entity_info_vector} 表，与 entity_node_graph 1:1）。
 * <p>向量合并为事务内 read-modify-write：先按实体名锁定旧行，merge 累积
 * chunk_ids 与内容择优后 upsert 写回；向量 id 由 {@code mdhash("ent-"+name)}
 * 在领域层计算。</p>
 */
public interface EntityInfoVectorRepository {

    /**
     * 按知识库与实体名查询实体向量。
     *
     * @param kbId       所属知识库ID
     * @param entityName 实体名称
     * @return 实体向量；不存在时返回空
     */
    Optional<EntityInfoVector> findByKbIdAndName(Long kbId, String entityName);

    /**
     * 锁定单个实体向量行（read-modify-write 前置）。
     *
     * @param kbId       所属知识库ID
     * @param entityName 实体名称
     * @return 已存在的实体向量；不存在时返回空
     */
    Optional<EntityInfoVector> lockByKbIdAndName(Long kbId, String entityName);

    /**
     * 单条 upsert 实体向量（按唯一键 {@code ON CONFLICT ... DO UPDATE}）。
     *
     * @param vector   待写入向量
     * @param operator 操作人标识（可空，空则由审计兜底）
     */
    void upsert(EntityInfoVector vector, String operator);

    /**
     * 内容收口窄更新：仅当向量行内容仍等于 {@code guardContent} 时，把内容与向量更新为期望值。
     * <p><b>只更新内容类列（{@code content} / {@code content_vector} / {@code updated_at}），
     * 绝不触碰账本列（{@code chunk_ids}）</b>——{@code upsert} 为整行覆盖（含
     * {@code chunk_ids = EXCLUDED.chunk_ids}），会把并发合并中他人刚并进的来源用旧值回卷，
     * 直接破坏幂等短路与来源归因，故收口 MUST NOT 复用 {@code upsert}。</p>
     * <p>乐观守卫 {@code content = guardContent} 为唯一条件（内容与描述在同事务成对写入、
     * 内容对描述单射 → 内容未变即描述未变）：并发下若他人已改写该行内容则本条更新落空，
     * 由更晚的提交者收口，保证收口的收敛性。</p>
     *
     * @param kbId            所属知识库ID
     * @param entityName      实体名称（唯一定位）
     * @param expectedContent 期望内容（收口后要写入的向量化内容）
     * @param newVector       期望内容对应的新向量（可为空——按空向量落库）
     * @param guardContent    守卫内容（仅当该行当前内容等于此值时才落库）
     * @return 受影响行数：{@code 1} 表示收口成功；{@code 0} 表示守卫未命中（内容已被他人改写，应跳过）
     */
    int updateContentIfUnchanged(Long kbId, String entityName, String expectedContent,
                                 float[] newVector, String guardContent);

    /**
     * 批量 upsert 实体向量，内部按固定批次分片执行。
     *
     * @param vectors  待写入向量列表（非空）
     * @param operator 操作人标识（可空，空则由审计兜底）
     */
    void upsertAll(List<EntityInfoVector> vectors, String operator);

    /**
     * 图谱贡献收敛：按切片ID集合从实体向量的 {@code chunk_ids} 账本剔除贡献，剔空即剪枝。
     * <p>语义（以账本为唯一权威，存在性判定即行在/行缺）：</p>
     * <ul>
     *   <li>账本与给定集合有交集且剔除后剩余为空 → 物理删除向量行，并同步物理删除
     *       对应 {@code entity_node_graph} 图行（1:1，按 {@code (kb_id, entity_name)} 关联）；</li>
     *   <li>账本与给定集合有交集但仍有存活贡献 → 仅收缩账本，条目与图行保留；</li>
     *   <li>本语句不触碰图行属性——条目权重由图谱贡献重建服务按存活来源<b>全额重算</b>
     *       （回扣已实现，RQ-22 已平账；缓存不可用时降级保留原值），兜底收缩仅保证账本一致。</li>
     * </ul>
     * <p><b>扫描面收窄</b>：全部清退/收缩语句带 {@code kb_id} 等值条件——切片主键全局唯一、
     * 只属于一个知识库，收窄不改变命中集，只把逐行展开 JSON 数组的扫描面从「全系统」收窄到
     * 「本知识库」。被清退的切片 MUST 全部属于 {@code kbId}（调用方保证批次不跨库）。</p>
     * <p>幂等可重入：重复调用时已剔除的 chunkId 与账本零交集，全部语句零影响；
     * 空集合（或仅含 null 元素）直接返回 0，不产生任何 DB 交互。</p>
     *
     * @param kbId     所属知识库ID（扫描面收窄条件，必填）
     * @param chunkIds 被清退的切片ID集合（null 元素自动忽略；单批建议不超过 500）
     * @return 本次实际物理删除的实体向量行数（与图行 1:1）
     */
    int removeChunkContributionsAndPrune(Long kbId, Collection<Long> chunkIds);

    /**
     * 按知识库整库物理删除实体向量行。
     * <p>整库消亡语义：不走逐批账本收敛，主键子查询分片循环物理删除该库全部行
     * （每片一条 DELETE 语句独立提交，子查询按 id 升序保证固定加锁顺序）。
     * 幂等可重入：无该库行时零影响返回 0，重复执行第二次起零影响。</p>
     *
     * @param kbId 所属知识库ID，必填
     * @return 本方法实际物理删除的总行数；入参为空返回 0（零 DB 交互）
     */
    int deleteByKbId(Long kbId);

    /**
     * 查「向量账本与给定分块集合有交集」的全部实体条目快照（重建分类阶段①受影响条目发现，
     * OpenSpec rebuild-kg-on-document-delete / 组 4）。
     * <p>谓词与 {@code EntityInfoVectorMapper#shrinkChunkContributions} 的账本重叠口径同式：
     * {@code EXISTS (jsonb_array_elements(chunk_ids) 元素 ∈ 入参集合)}，账本以向量表
     * {@code chunk_ids} 为唯一权威（图行 {@code properties.sourceIds} 仅展示列、不参与判定）。
     * 命中集 LEFT JOIN 图行带出当前 {@code properties}（降级路径保留语义字段与写回前比较的
     * 原值来源），图行缺失（收敛中间态）时属性按空属性承载。</p>
     * <p><b>幂等与扫描面收窄</b>：只读语句、可重复执行（文档删除链崩溃重试时同一批入参恒推出
     * 同一受影响集合——「账本 ∩ 本批」谓词本身即重入依据，不依赖任何日志）；全部条件带
     * {@code kb_id} 等值收窄，逐行展开 JSON 数组的扫描面限定本知识库。输出按实体名升序
     * （与 {@code lockByKbIdAndNames} 的加锁序同向，下游按名升序处理即固定加锁顺序）。</p>
     *
     * @param kbId     所属知识库ID（扫描面收窄条件），必填
     * @param chunkIds 分块ID集合（本批被删分块；为空返回空列表，零 DB 交互）
     * @return 账本与入参有交集的实体条目快照列表（实体名升序）；无命中返回空列表
     */
    List<EntityLedgerSnapshot> findLedgerSnapshotsWithChunkOverlap(Long kbId, Collection<Long> chunkIds);

    /**
     * 按条目身份物理删除单个实体向量行（重建写回阶段「存活集合为空转删除」的逐条删除原语）。
     * <p>与整库清退不同：本方法只删一行、由调用方事务内的加锁重读判定后触发
     * （账本剔除被删分块后无存活来源才允许调用），MUST 与对应图行的删除同事务。
     * 幂等：行已不存在时返回 0（并发删除链已回收，重复执行零影响）。</p>
     *
     * @param kbId       所属知识库ID（等值定位条件），必填
     * @param entityName 实体名称（唯一定位），必填
     * @return 实际物理删除行数（0 或 1）；入参为空返回 0（零 DB 交互）
     */
    int deleteByKbIdAndName(Long kbId, String entityName);
}