package com.linkroa.deepdataagent.rag.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.entity.RelationInfoVectorEntity;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.projection.RelationLedgerOverlapProjection;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.projection.RelationVectorHitProjection;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 关系向量 Mapper（relation_info_vector 表，与 relation_edge_graph 1:1）。
 * <p>关系向量身份使用 sorted 双向端点对，写入方统一按字典序方向落库；
 * 写路径统一走 {@code ON CONFLICT (kb_id, source_name, target_name)} 增量合并。</p>
 */
@Mapper
public interface RelationInfoVectorMapper extends BaseMapper<RelationInfoVectorEntity> {

    /**
     * 按无向端点对悲观锁读取单行（read-modify-write 前置，向量侧至多一行）。
     *
     * @param kbId  所属知识库ID
     * @param nameA 端点一
     * @param nameB 端点二
     * @return 已存在的向量行；不存在返回 null
     */
    @Select("""
            <script>
            SELECT * FROM relation_info_vector
            WHERE kb_id = #{kbId}
              AND ((source_name = #{nameA} AND target_name = #{nameB})
                   OR (source_name = #{nameB} AND target_name = #{nameA}))
            LIMIT 1
            FOR UPDATE
            </script>
            """)
    RelationInfoVectorEntity selectForUpdateByKbIdAndUnorderedPair(@Param("kbId") Long kbId,
                                                                   @Param("nameA") String nameA,
                                                                   @Param("nameB") String nameB);

    /**
     * 批量 upsert 关系向量（多值 INSERT + {@code ON CONFLICT DO UPDATE}）。
     *
     * @param list 待写入向量列表（非空，调用方保证；chunk_ids 序列化为非空 JSON "[]"）
     * @return 受影响行数
     */
    @Insert("""
            <script>
            INSERT INTO relation_info_vector (kb_id, source_name, target_name, content, content_vector, chunk_ids, file_path,
                                             created_at, updated_at, created_by, updated_by)
            VALUES
            <foreach collection="list" item="it" separator=",">
                (#{it.kbId}, #{it.sourceName}, #{it.targetName}, #{it.content}, #{it.contentVector}::vector,
                 #{it.chunkIds, typeHandler=com.linkroa.deepdataagent.shared.util.PostgresJsonbTypeHandler},
                 #{it.filePath},
                 #{it.createdAt}, #{it.updatedAt}, #{it.createdBy}, #{it.updatedBy})
            </foreach>
            ON CONFLICT (kb_id, source_name, target_name)
            DO UPDATE SET content = EXCLUDED.content,
                          content_vector = EXCLUDED.content_vector,
                          chunk_ids = EXCLUDED.chunk_ids,
                          updated_at = now(),
                          updated_by = EXCLUDED.updated_by
            </script>
            """)
    int upsertBatch(@Param("list") List<RelationInfoVectorEntity> list);

    /**
     * 内容收口窄更新（CAS）：仅更新内容类列，带乐观守卫 {@code content = guardContent}。
     * <p>只改 {@code content} / {@code content_vector} / {@code updated_at}，
     * MUST NOT 触碰 {@code chunk_ids} 等账本列（整行覆盖会回卷并发合并刚并进的来源）；
     * 守卫未命中时受影响 0 行。端点对按无向 OR 匹配定位归一落库的唯一向量行；
     * {@code contentVector} 为向量字面量，由 SQL 侧 {@code ::vector} 转换。</p>
     *
     * @param kbId          所属知识库ID
     * @param nameA         无向端点对之一
     * @param nameB         无向端点对之二
     * @param content       期望内容（收口后写入）
     * @param contentVector 期望内容对应的新向量字面量（形如 {@code "[0.1,0.2]"}）
     * @param guardContent  守卫内容（仅当该行当前内容等于此值时才落库）
     * @return 受影响行数（1=命中并更新，0=守卫未命中）
     */
    @Update("""
            UPDATE relation_info_vector
            SET content = #{content},
                content_vector = #{contentVector}::vector,
                updated_at = now()
            WHERE kb_id = #{kbId}
              AND ((source_name = #{nameA} AND target_name = #{nameB})
                   OR (source_name = #{nameB} AND target_name = #{nameA}))
              AND content = #{guardContent}
            """)
    int updateContentIfUnchanged(@Param("kbId") Long kbId, @Param("nameA") String nameA,
                                 @Param("nameB") String nameB, @Param("content") String content,
                                 @Param("contentVector") String contentVector,
                                 @Param("guardContent") String guardContent);

    /**
     * 检索侧只读：关系路向量命中（②，GRAPH 通道关系路，保持向量相似度顺序）。
     * <p><b>取数骨架</b>：向量命中（HNSW）+ 图表 1:1 属性点查 JOIN + chunk 账本 COALESCE 兜底
     * （与 {@link EntityInfoVectorMapper#searchByCosineDistance} 同构）。</p>
     * <ul>
     *     <li>pgvector 余弦距离操作符 {@code <=>} 命中：距离小于阈值即召回，按距离升序截断；
     *     {@code score = 1 - 余弦距离}（余弦相似度，越高越相关）；端点名由向量行自带
     *     （字典序归一对），无需再拼接「源-目标」串；</li>
     *     <li><b>展示权威为图表</b>：{@code description}/{@code keywords}(JSON 数组文本)/
     *     {@code filePaths}(JSON 数组文本) 取 {@code relation_edge_graph.properties}，{@code weight} 取
     *     {@code properties.weight}（缺失 SQL 侧兜 1.0），{@code created_at} 取图行列值；</li>
     *     <li><b>账本权威为向量表</b>：{@code chunk_ids} 非空数组优先，空数组回落图行
     *     {@code properties.sourceIds} 整数组，图行缺失自然退化 {@code '[]'}；</li>
     *     <li>{@code graph_missing}（图行 LEFT JOIN 空）由仓储层 WARN 并剔除该命中。</li>
     * </ul>
     * <p><b>JOIN 方向策略</b>：向量表按字典序归一落库，而图表保留原始方向（历史可存在
     * {@code (a,b)/(b,a)} 双行），故 JOIN 必须双向匹配；此处刻意<b>不</b>写成
     * {@code e.source_name = LEAST(v.source_name, v.target_name)}——该形式虽保持
     * {@code e} 侧列裸露、可用唯一索引 {@code uk_relation_edge_kb_src_tgt}，但等价于
     * {@code e.source_name = v.source_name}，只会命中与向量行同向的图行，反向残留行被静默丢弃
     * （属性整体丢失，比双行更糟）。故选双向 OR：两个分支各自都是
     * {@code (kb_id, source_name, target_name)} 的等值键，与前向兼容终态（图侧亦归一后）同形，
     * 收敛前后行为一致（风险 R-5 的最坏假设口径）。若批 2 验收项 2.10 的 EXPLAIN 显示
     * OR 使规划器放弃参数化索引点查而顺表扫，则改写成两分支 UNION ALL 的
     * {@code LEFT JOIN LATERAL ... LIMIT 1} 形态（每分支单键等值、必然命中唯一索引），
     * 语义等价、不改上层。</p>
     * <p>查询不带 {@code <script>}，避免 XML 解析转义 {@code <=>}。</p>
     *
     * @param kbId       所属知识库ID（单库隔离）
     * @param vecLiteral 查询向量字面量，形如 {@code "[0.1,0.2,...]"}（调用方保证非空）
     * @param threshold  余弦距离阈值（命中条件为距离小于该值）
     * @param limit      返回上限（关系路 top_k）
     * @return 命中投影列表（按向量距离升序，含图行属性与账本兜底结果）
     */
    @Select("""
            SELECT v.source_name,
                   v.target_name,
                   1 - (v.content_vector <=> #{vecLiteral}::vector) AS score,
                   COALESCE(NULLIF(v.chunk_ids, '[]'::jsonb),
                            e.properties -> 'sourceIds', '[]'::jsonb)::text AS chunk_ids_raw,
                   e.properties ->> 'description' AS description,
                   (e.properties -> 'keywords')::text AS keywords_raw,
                   COALESCE((e.properties ->> 'weight')::double precision, 1.0) AS weight,
                   (e.properties -> 'filePaths')::text AS file_paths_raw,
                   e.created_at                 AS created_at,
                   e.source_name IS NULL        AS graph_missing
            FROM relation_info_vector v
            LEFT JOIN relation_edge_graph e
                   ON e.kb_id = v.kb_id
                  AND ((e.source_name = v.source_name AND e.target_name = v.target_name)
                       OR (e.source_name = v.target_name AND e.target_name = v.source_name))
            WHERE v.kb_id = #{kbId}
              AND v.content_vector IS NOT NULL
              AND (v.content_vector <=> #{vecLiteral}::vector) < #{threshold}
            ORDER BY v.content_vector <=> #{vecLiteral}::vector
            LIMIT #{limit}
            """)
    List<RelationVectorHitProjection> searchByCosineDistance(@Param("kbId") Long kbId,
                                                             @Param("vecLiteral") String vecLiteral,
                                                             @Param("threshold") double threshold,
                                                             @Param("limit") int limit);

    /**
     * 收敛①：物理删除「账本剔空」关系对应的图边行（relation_edge_graph）。
     * <p>向量行与图边 1:1 关联，故端点对按双向 OR 匹配（兼容历史双向行）；
     * 剔空谓词 = 账本与入参集合有交集且剔除后无存活元素。
     * 必须在 {@link #physicalDeletePrunedVectors(Long, List)} 之前执行（依赖向量行存在做关联判定）。
     * 本语句整行物理删除图边、不触碰保留条目的图行属性——保留条目的权重由图谱贡献重建服务
     * 按存活来源全额重算（回扣已实现，RQ-22 已平账），缓存不可用时降级保留原值。</p>
     * <p><b>kb_id 收窄</b>：外层与向量侧子查询均带知识库等值条件——切片主键全局唯一、
     * 只属于一个知识库，收窄不改变命中集，只把逐行展开 JSON 数组的扫描面从「全系统」
     * 收窄到「本知识库」。</p>
     *
     * @param kbId     所属知识库ID（扫描面收窄条件，调用方保证非空）
     * @param chunkIds 被清退的切片ID列表（非空，调用方已去 null 去重）
     * @return 物理删除的图边行数
     */
    @Delete("""
            <script>
            DELETE FROM relation_edge_graph g
            WHERE g.kb_id = #{kbId}
              AND EXISTS (
                SELECT 1 FROM relation_info_vector v
                WHERE v.kb_id = #{kbId}
                  AND ((v.source_name = g.source_name AND v.target_name = g.target_name)
                       OR (v.source_name = g.target_name AND v.target_name = g.source_name))
                  AND EXISTS (
                      SELECT 1 FROM jsonb_array_elements(v.chunk_ids) AS elem
                      WHERE (elem #>> '{}')::bigint IN
                      <foreach collection="chunkIds" item="cid" open="(" separator="," close=")">#{cid}</foreach>
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM jsonb_array_elements(v.chunk_ids) AS elem
                      WHERE (elem #>> '{}')::bigint NOT IN
                      <foreach collection="chunkIds" item="cid" open="(" separator="," close=")">#{cid}</foreach>
                  )
            )
            </script>
            """)
    int physicalDeletePrunedGraphEdges(@Param("kbId") Long kbId, @Param("chunkIds") List<Long> chunkIds);

    /**
     * 收敛②：物理删除账本剔空的关系向量行（relation_info_vector，彻底物理删、无逻辑删墓碑）。
     * <p>剔空谓词同 {@link #physicalDeletePrunedGraphEdges(Long, List)}；重复执行时
     * 已剔除的 chunkId 与剩余账本零交集，语句零影响（幂等）。
     * {@code kb_id} 等值条件把扫描面收窄到本知识库（不改变命中集）。</p>
     *
     * @param kbId     所属知识库ID（扫描面收窄条件，调用方保证非空）
     * @param chunkIds 被清退的切片ID列表（非空，调用方已去 null 去重）
     * @return 物理删除的向量行数（收敛计数口径）
     */
    @Delete("""
            <script>
            DELETE FROM relation_info_vector v
            WHERE v.kb_id = #{kbId}
            AND EXISTS (
                SELECT 1 FROM jsonb_array_elements(v.chunk_ids) AS elem
                WHERE (elem #>> '{}')::bigint IN
                <foreach collection="chunkIds" item="cid" open="(" separator="," close=")">#{cid}</foreach>
            )
            AND NOT EXISTS (
                SELECT 1 FROM jsonb_array_elements(v.chunk_ids) AS elem
                WHERE (elem #>> '{}')::bigint NOT IN
                <foreach collection="chunkIds" item="cid" open="(" separator="," close=")">#{cid}</foreach>
            )
            </script>
            """)
    int physicalDeletePrunedVectors(@Param("kbId") Long kbId, @Param("chunkIds") List<Long> chunkIds);

    /**
     * 收敛③：收缩仍有存活贡献的关系向量行账本（剔除入参集合中的 chunkId）。
     * <p>仅命中「账本与入参有交集」的存活行（剔空行已在收敛②物理删除）；
     * 剩余元素经 {@code jsonb_agg} 重建，理论空集兜底为 {@code '[]'} 保证 NOT NULL。
     * 幂等：无交集的行不命中本语句。{@code kb_id} 等值条件把扫描面收窄到本知识库。</p>
     *
     * @param kbId     所属知识库ID（扫描面收窄条件，调用方保证非空）
     * @param chunkIds 被清退的切片ID列表（非空，调用方已去 null 去重）
     * @return 账本被收缩的向量行数
     */
    @Update("""
            <script>
            UPDATE relation_info_vector v
            SET chunk_ids = COALESCE((
                    SELECT jsonb_agg(elem) FROM jsonb_array_elements(v.chunk_ids) AS elem
                    WHERE (elem #>> '{}')::bigint NOT IN
                    <foreach collection="chunkIds" item="cid" open="(" separator="," close=")">#{cid}</foreach>
                ), '[]'::jsonb),
                updated_at = now()
            WHERE v.kb_id = #{kbId}
            AND EXISTS (
                SELECT 1 FROM jsonb_array_elements(v.chunk_ids) AS elem
                WHERE (elem #>> '{}')::bigint IN
                <foreach collection="chunkIds" item="cid" open="(" separator="," close=")">#{cid}</foreach>
            )
            </script>
            """)
    int shrinkChunkContributions(@Param("kbId") Long kbId, @Param("chunkIds") List<Long> chunkIds);

    /**
     * 整库清退分片：按 kb_id 主键子查询分片物理删除关系向量行（彻底物理删，无逻辑删墓碑）。
     * <p>{@code DELETE ... WHERE id IN (SELECT id ... ORDER BY id LIMIT n)}：子查询按主键升序
     * 圈定本片行集，保证多事务并发时固定加锁顺序（事务规范 3.1）；单语句即一片，由仓储层
     * 循环至本片不足批大小。幂等：无该库行时零影响返回 0。</p>
     *
     * @param kbId      所属知识库ID（调用方保证非空）
     * @param batchSize 单片删除上限（调用方保证为正）
     * @return 本片实际物理删除行数
     */
    @Delete("""
            DELETE FROM relation_info_vector
            WHERE id IN (
                SELECT id FROM relation_info_vector
                WHERE kb_id = #{kbId}
                ORDER BY id
                LIMIT #{batchSize}
            )
            """)
    int physicalDeleteByKbIdBatch(@Param("kbId") Long kbId, @Param("batchSize") int batchSize);

    /**
     * 重建分类阶段①：查「向量账本与入参分块集合有交集」的全部关系条目（账本 + 向量内容 +
     * 图边属性一次取齐，重建服务组 4 专用只读语句）。
     * <p>重叠谓词与 {@link #shrinkChunkContributions(Long, List)} 同式；账本权威为向量表
     * {@code chunk_ids}。图边属性经 {@code LEFT JOIN LATERAL ... LIMIT 1} 取双向匹配中
     * （源、目标）升序的首个方向行——一个向量行恒出一个条目（避免双向图行把同一条目放大成
     * 多行），无属性消费方对「取哪个方向行的 properties」不敏感（历史双向残留行的属性
     * 由写回链路的归一方向行承载，LATERAL 等值/OR 谓词均可命中唯一索引）。</p>
     * <p>只读不加锁、可重复执行（「账本 ∩ 本批」谓词即删除链崩溃重试的重入依据）；
     * {@code kb_id} 等值条件收窄扫描面；输出按（源、目标）升序。入参由仓储层保证去 null
     * 去重且非空。</p>
     *
     * @param kbId     所属知识库ID（扫描面收窄条件，调用方保证非空）
     * @param chunkIds 分块ID列表（本批被删分块，非空，调用方已去 null 去重）
     * @return 账本重叠的关系条目投影列表（（源、目标）升序）
     */
    @Select("""
            <script>
            SELECT v.source_name,
                   v.target_name,
                   v.content,
                   v.content_vector,
                   v.chunk_ids::text AS chunk_ids_raw,
                   g.properties::text AS properties_raw,
                   g.properties IS NULL AS graph_missing
            FROM relation_info_vector v
            LEFT JOIN LATERAL (
                SELECT e.properties
                FROM relation_edge_graph e
                WHERE e.kb_id = v.kb_id
                  AND ((e.source_name = v.source_name AND e.target_name = v.target_name)
                       OR (e.source_name = v.target_name AND e.target_name = v.source_name))
                ORDER BY e.source_name, e.target_name
                LIMIT 1
            ) g ON TRUE
            WHERE v.kb_id = #{kbId}
              AND EXISTS (
                  SELECT 1 FROM jsonb_array_elements(v.chunk_ids) AS elem
                  WHERE (elem #>> '{}')::bigint IN
                  <foreach collection="chunkIds" item="cid" open="(" separator="," close=")">#{cid}</foreach>
              )
            ORDER BY v.source_name, v.target_name
            </script>
            """)
    List<RelationLedgerOverlapProjection> selectLedgerOverlappingChunks(@Param("kbId") Long kbId,
                                                                        @Param("chunkIds") List<Long> chunkIds);

    /**
     * 按归一端点对物理删除关系向量行（重建写回「存活集合为空转删除」逐条原语）。
     * <p>定位与 {@link #selectForUpdateByKbIdAndUnorderedPair} 同口径（双向 OR，兼容历史
     * 反向行；向量侧至多一行）。行已不存在时零影响（幂等）。MUST 由调用方在事务内加锁重读
     * 判定后调用，并与图边行删除（{@code RelationEdgeGraphMapper#physicalDeleteByKbIdAndUnorderedPair}）
     * 同事务成对执行。</p>
     *
     * @param kbId  所属知识库ID（调用方保证非空）
     * @param nameA 无向端点对之一（调用方保证非空）
     * @param nameB 无向端点对之二（调用方保证非空）
     * @return 实际物理删除行数（0 或 1）
     */
    @Delete("""
            DELETE FROM relation_info_vector
            WHERE kb_id = #{kbId}
              AND ((source_name = #{nameA} AND target_name = #{nameB})
                   OR (source_name = #{nameB} AND target_name = #{nameA}))
            """)
    int physicalDeleteByKbIdAndUnorderedPair(@Param("kbId") Long kbId, @Param("nameA") String nameA,
                                             @Param("nameB") String nameB);
}