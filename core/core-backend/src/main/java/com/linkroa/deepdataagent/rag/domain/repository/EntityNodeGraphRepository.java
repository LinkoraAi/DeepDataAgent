package com.linkroa.deepdataagent.rag.domain.repository;

import com.linkroa.deepdataagent.rag.domain.model.EntityNode;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 图谱实体节点仓储端口（对应 {@code entity_node_graph} 表）。
 * <p>实体合并为事务内 read-modify-write：先按实体名锁定旧行，合并后以
 * upsert（{@code ON CONFLICT ... DO UPDATE}）写回；同名实体在库内全局唯一。
 * 锁序约束：{@link #lockByKbIdAndNames} 按实体名升序加锁，配合事务规范 3.1
 * 固定加锁顺序，避免并发合并死锁。</p>
 */
public interface EntityNodeGraphRepository {

    /**
     * 按知识库与实体名查询节点。
     *
     * @param kbId       所属知识库ID
     * @param entityName 实体名称
     * @return 实体节点；不存在时返回空
     */
    Optional<EntityNode> findByKbIdAndName(Long kbId, String entityName);

    /**
     * 锁定指定名称集合的实体节点行（read-modify-write 前置）。
     * <p>按 {@code entity_name} 升序 {@code FOR UPDATE} 加锁，返回已存在的节点
     * （不存在的名称不出现在结果中）。锁定顺序与调用方聚合顺序无关，仅由本方法保证。
     * 批量输入的名称应先去重。</p>
     *
     * @param kbId        所属知识库ID
     * @param entityNames 待锁定实体名称集合（非空，按序加锁）
     * @return 库内已存在的节点（无锁空名不返回）
     */
    List<EntityNode> lockByKbIdAndNames(Long kbId, List<String> entityNames);

    /**
     * 单条 upsert 实体节点（存在则按唯一键更新属性）。
     *
     * @param node     待写入节点
     * @param operator 操作人标识（可空，空则由审计兜底）
     */
    void upsert(EntityNode node, String operator);

    /**
     * 批量 upsert 实体节点，内部按固定批次分片执行。
     *
     * @param nodes    待写入节点列表（非空）
     * @param operator 操作人标识（可空，空则由审计兜底）
     */
    void upsertAll(List<EntityNode> nodes, String operator);

    /**
     * 统计知识库内实体节点数量。
     *
     * @param kbId 所属知识库ID
     * @return 节点数量
     */
    long countByKbId(Long kbId);

    /**
     * 按知识库整库物理删除实体图行。
     * <p>整库消亡语义：不走逐批账本收敛，主键子查询分片循环物理删除该库全部行
     * （每片一条 DELETE 语句独立提交，子查询按 id 升序保证固定加锁顺序）。
     * 幂等可重入：无该库行时零影响返回 0，重复执行第二次起零影响。</p>
     *
     * @param kbId 所属知识库ID，必填
     * @return 本方法实际物理删除的总行数；入参为空返回 0（零 DB 交互）
     */
    int deleteByKbId(Long kbId);

    /**
     * 图谱来源收敛第④步：收缩实体图行展示来源列（{@code properties.sourceIds} 剔除被清退分块标识）。
     * <p>与向量账本收缩同事务、同谓词口径（仅命中「来源列与入参有交集」的存活行，剩余元素重建、
     * 空集兜底空数组）；MUST 在「剔空物理删图行」之后执行。幂等：重复执行第二次起零影响。
     * kbId 为空或集合为空返回 0（零 DB 交互）。</p>
     *
     * @param kbId     所属知识库ID（扫描面收窄条件），必填
     * @param chunkIds 被清退的切片ID集合，非空
     * @return 展示来源列被收缩的图行数（仅留痕口径，不计入收敛删除计数）
     */
    int shrinkDisplaySourceIds(Long kbId, Collection<Long> chunkIds);

    /**
     * 按实体名物理删除单个实体图行（重建写回阶段「存活集合为空转删除」的逐条删除原语）。
     * <p>与向量账本行删除（{@code EntityInfoVectorRepository#deleteByKbIdAndName}）成对使用、
     * 同事务生效；MUST 在调用方事务内先经 {@link #lockByKbIdAndNames} 加锁重读判定后调用
     * （账本剔除被删分块后无存活来源才允许删除）。幂等：行已不存在时返回 0。</p>
     *
     * @param kbId       所属知识库ID（等值定位条件），必填
     * @param entityName 实体名称（唯一定位），必填
     * @return 实际物理删除行数（0 或 1）；入参为空返回 0（零 DB 交互）
     */
    int deleteByKbIdAndName(Long kbId, String entityName);
}