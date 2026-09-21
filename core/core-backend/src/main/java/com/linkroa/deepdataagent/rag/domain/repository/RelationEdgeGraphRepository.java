package com.linkroa.deepdataagent.rag.domain.repository;

import com.linkroa.deepdataagent.rag.domain.model.RelationEdge;

import java.util.Collection;
import java.util.List;

/**
 * 图谱关系边仓储端口（对应 {@code relation_edge_graph} 表）。
 * <p>图边唯一键为带方向 {@code (kb_id, source_name, target_name)}；落库方向恒为端点名
 * 归一后的字典序（抽取聚合出口定序），所有写入命中同一唯一键，反向残留行无从产生。
 * 读取仍按无向端点对同时匹配两个方向（{@link #findByKbIdAndUnorderedPair} /
 * {@link #lockByKbIdAndUnorderedPair}），以兼容历史数据中可能存在的双向行。
 * 加锁按源/目标升序输出顺序，保证固定锁序（事务规范 3.1）。</p>
 */
public interface RelationEdgeGraphRepository {

    /**
     * 按无向端点对查询全部边行（两个方向，各至多一行，<b>只读不加锁</b>）。
     * <p>SQL 同时覆盖 {@code (a,b)} 与 {@code (b,a)} 两种方向，输出按
     * {@code source_name, target_name} 升序（与 {@link #lockByKbIdAndUnorderedPair} 输出顺序一致），
     * 但不施加任何行锁。</p>
     * <p>用途限定：为构造 LLM 上下文等「非写回基准」的读取场景提供当前账本快照。其结果
     * MUST NOT 用作合并写回的过滤基准——写回基准必须由写回事务内的加锁读取
     * （{@link #lockByKbIdAndUnorderedPair}）提供：不加锁读在并发合并下可能读到过期账本
     * （weight/source_ids 落后于他人已提交结果），据此计算写回会丢失他人贡献。</p>
     *
     * @param kbId   所属知识库ID
     * @param nameA  端点一
     * @param nameB  端点二
     * @return 已存在的边（可能为空、一条或两条）
     */
    List<RelationEdge> findByKbIdAndUnorderedPair(Long kbId, String nameA, String nameB);

    /**
     * 锁定指定无向端点对的全部边行（两个方向，各至多一行）。
     * <p>SQL 同时覆盖 {@code (a,b)} 与 {@code (b,a)} 两种方向并 {@code FOR UPDATE}，
     * 输出按 {@code source_name, target_name} 升序，保证并发合并加锁顺序一致。</p>
     *
     * @param kbId   所属知识库ID
     * @param nameA  端点一
     * @param nameB  端点二
     * @return 已存在的边（可能为空、一条或两条）
     */
    List<RelationEdge> lockByKbIdAndUnorderedPair(Long kbId, String nameA, String nameB);

    /**
     * 批量 upsert 关系边（按带方向唯一键 {@code ON CONFLICT ... DO UPDATE}）。
     *
     * @param edges    待写入边列表（非空）
     * @param operator 操作人标识（可空，空则由审计兜底）
     */
    void upsertAll(List<RelationEdge> edges, String operator);

    /**
     * 按知识库整库物理删除关系图行。
     * <p>整库消亡语义：不走逐批账本收敛，主键子查询分片循环物理删除该库全部行
     * （每片一条 DELETE 语句独立提交，子查询按 id 升序保证固定加锁顺序）。
     * 幂等可重入：无该库行时零影响返回 0，重复执行第二次起零影响。</p>
     *
     * @param kbId 所属知识库ID，必填
     * @return 本方法实际物理删除的总行数；入参为空返回 0（零 DB 交互）
     */
    int deleteByKbId(Long kbId);

    /**
     * 图谱来源收敛第④步：收缩关系图行展示来源列（{@code properties.sourceIds} 剔除被清退分块标识）。
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
     * 按无向端点对物理删除关系图边行（重建写回阶段「存活集合为空转删除」的逐条删除原语）。
     * <p>定位与 {@link #lockByKbIdAndUnorderedPair} 同口径（双向 OR，兼容历史反向行，一并删除）；
     * 与向量账本行删除（{@code RelationInfoVectorRepository#deleteByKbIdAndUnorderedPair}）
     * 成对使用、同事务生效，MUST 在加锁重读判定「剔后无存活来源」后调用。
     * 幂等：行已不存在时返回 0。</p>
     *
     * @param kbId  所属知识库ID（等值定位条件），必填
     * @param nameA 无向端点对之一（内部按双向 OR 匹配），必填
     * @param nameB 无向端点对之二，必填
     * @return 实际物理删除行数（0~2，历史双向残留行一次清尽）；入参为空返回 0（零 DB 交互）
     */
    int deleteByKbIdAndUnorderedPair(Long kbId, String nameA, String nameB);
}