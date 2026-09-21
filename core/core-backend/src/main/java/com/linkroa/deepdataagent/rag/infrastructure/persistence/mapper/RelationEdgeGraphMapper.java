package com.linkroa.deepdataagent.rag.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.entity.RelationEdgeGraphEntity;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.projection.GraphEdgeProjection;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 图谱关系边 Mapper（relation_edge_graph 表）。
 * <p>图边唯一键为带方向 {@code (kb_id, source_name, target_name)}；写路径统一走
 * {@code ON CONFLICT ... DO UPDATE} 增量合并。合并前经
 * {@link #selectForUpdateByKbIdAndUnorderedPair} 同时锁定两个方向的旧行。</p>
 */
@Mapper
public interface RelationEdgeGraphMapper extends BaseMapper<RelationEdgeGraphEntity> {

    /**
     * 按无向端点对只读查询两个方向的边行（<b>不加锁</b>）。
     * <p>SQL 形状与 {@link #selectForUpdateByKbIdAndUnorderedPair} 完全一致，唯一差别是不带
     * {@code FOR UPDATE}：同时匹配 {@code (a,b)} 与 {@code (b,a)} 两种方向，输出按
     * {@code source_name, target_name} 升序。</p>
     * <p>仅供构造 LLM 上下文等「非写回基准」的读取场景；合并写回基准必须走加锁读，
     * 本方法结果在并发合并下可能过期，不得据此计算写回。</p>
     *
     * @param kbId  所属知识库ID
     * @param nameA 端点一
     * @param nameB 端点二
     * @return 已存在的边行（0~2 行）
     */
    @Select("""
            <script>
            SELECT * FROM relation_edge_graph
            WHERE kb_id = #{kbId}
              AND ((source_name = #{nameA} AND target_name = #{nameB})
                   OR (source_name = #{nameB} AND target_name = #{nameA}))
            ORDER BY source_name, target_name
            </script>
            """)
    List<RelationEdgeGraphEntity> selectByKbIdAndUnorderedPair(@Param("kbId") Long kbId,
                                                              @Param("nameA") String nameA,
                                                              @Param("nameB") String nameB);

    /**
     * 按无向端点对悲观锁读取两个方向的边行（read-modify-write 前置）。
     * <p>同时匹配 {@code (a,b)} 与 {@code (b,a)} 两种方向，输出按
     * {@code source_name, target_name} 升序，保证并发合并加锁顺序一致。</p>
     *
     * @param kbId  所属知识库ID
     * @param nameA 端点一
     * @param nameB 端点二
     * @return 已存在的边行（0~2 行）
     */
    @Select("""
            <script>
            SELECT * FROM relation_edge_graph
            WHERE kb_id = #{kbId}
              AND ((source_name = #{nameA} AND target_name = #{nameB})
                   OR (source_name = #{nameB} AND target_name = #{nameA}))
            ORDER BY source_name, target_name
            FOR UPDATE
            </script>
            """)
    List<RelationEdgeGraphEntity> selectForUpdateByKbIdAndUnorderedPair(@Param("kbId") Long kbId,
                                                                        @Param("nameA") String nameA,
                                                                        @Param("nameB") String nameB);

    /**
     * 批量 upsert 关系边（多值 INSERT + {@code ON CONFLICT DO UPDATE}，properties JSONB 经显式 TypeHandler 写入）。
     *
     * @param list 待写入关系边列表（非空，调用方保证；properties 由调用方序列化为非空 JSON）
     * @return 受影响行数
     */
    @Insert("""
            <script>
            INSERT INTO relation_edge_graph (kb_id, source_name, target_name, properties,
                                            created_at, updated_at, created_by, updated_by)
            VALUES
            <foreach collection="list" item="it" separator=",">
                (#{it.kbId}, #{it.sourceName}, #{it.targetName},
                 #{it.properties, typeHandler=com.linkroa.deepdataagent.shared.util.PostgresJsonbTypeHandler},
                 #{it.createdAt}, #{it.updatedAt}, #{it.createdBy}, #{it.updatedBy})
            </foreach>
            ON CONFLICT (kb_id, source_name, target_name)
            DO UPDATE SET properties = EXCLUDED.properties,
                          updated_at = now(),
                          updated_by = EXCLUDED.updated_by
            </script>
            """)
    int upsertBatch(@Param("list") List<RelationEdgeGraphEntity> list);

    /**
     * 检索侧只读：1 跳关联边查询（③，实体候选集 → 关联边记录，双向）。
     * <p><b>三层形态（风险 R-4：{@code DISTINCT ON} 要求表达式前缀排序键，与度数排序互斥）</b>：</p>
     * <ol>
     *     <li>{@code degree_map} CTE：统计实体在边表中作为 source/target 的出现次数
     *     （对齐参考 edge_degrees_batch，边度数 = 两端节点度数之和，不落存储列）；</li>
     *     <li>{@code candidate_edges} 子查询：候选边 + 图属性 + chunk 账本。端点对经
     *     {@code LEAST/GREATEST} 无向归一为字典序（sourceName &lt;= targetName，与
     *     relation_info_vector 落库方向一致）；属性取本表 {@code properties}
     *     （description/keywords/weight/filePaths）与列级 {@code created_at}，权重缺省 1.0；
     *     账本经 {@code LEFT JOIN relation_info_vector}（<b>JOIN 键必须过 LEAST/GREATEST 映射</b>
     *     ——本表方向任意、向量表字典序归一；映射表达式由驱动侧边行算出即为常量，
     *     向量表侧 {@code v.source_name = 归一小端} 仍是唯一索引
     *     {@code uk_relation_info_vector_kb_src_tgt} 的等值前缀）取 {@code v.chunk_ids}，
     *     同款 {@code COALESCE} 兜底回落边行
     *     {@code properties.sourceIds} 整数组；{@code source/target} 任一实体名命中候选集即返回，
     *     命中边数由 {@code limit} 截断（默认 50）；</li>
     *     <li>外层：先 {@code DISTINCT ON (source_name, target_name)} + 内层
     *     {@code ORDER BY 归一对, edge_updated_at DESC} 折叠历史双向残留行（同一归一对只保留
     *     {@code updated_at} 最新的<b>整行</b>属性与账本，摄入侧收敛完成后行为不变），
     *     再按「度数 + 权重」双键降序（同键以端点名字典序保证确定性）截断 {@code limit}。</li>
     * </ol>
     * <p>{@code LIMIT} 位于去重之后的外层，故去重不会挤占候选席位（度数 Top-N 语义不变）。
     * 实体候选集为空时不得调用（IN 空列表非法），由仓储层前置判空。</p>
     *
     * @param kbId        所属知识库ID（单库隔离）
     * @param entityNames 实体候选集名称列表（非空，调用方保证）
     * @param limit       返回上限（关联边 Top，默认 {@code RetrievalConstants.GRAPH_EDGE_TOP} = 50）
     * @return 关联边投影列表（无向归一 + 归一对去重取新行，度数+权重双键降序）
     */
    @Select("""
            <script>
            WITH degree_map AS (
                SELECT name, COUNT(*) AS degree
                FROM (
                    SELECT source_name AS name FROM relation_edge_graph WHERE kb_id = #{kbId}
                    UNION ALL
                    SELECT target_name AS name FROM relation_edge_graph WHERE kb_id = #{kbId}
                ) t
                GROUP BY name
            ),
            candidate_edges AS (
                SELECT LEAST(e.source_name, e.target_name)             AS source_name,
                       GREATEST(e.source_name, e.target_name)          AS target_name,
                       COALESCE(ds.degree, 0) + COALESCE(dt.degree, 0) AS edge_degree,
                       COALESCE((e.properties ->> 'weight')::double precision, 1.0) AS weight,
                       COALESCE(NULLIF(v.chunk_ids, '[]'::jsonb),
                                e.properties -> 'sourceIds', '[]'::jsonb)::text AS chunk_ids_raw,
                       e.properties ->> 'description' AS description,
                       (e.properties -> 'keywords')::text AS keywords_raw,
                       (e.properties -> 'filePaths')::text AS file_paths_raw,
                       e.created_at                   AS created_at,
                       e.updated_at                   AS edge_updated_at
                FROM relation_edge_graph e
                LEFT JOIN degree_map ds ON ds.name = e.source_name
                LEFT JOIN degree_map dt ON dt.name = e.target_name
                LEFT JOIN relation_info_vector v
                       ON v.kb_id = e.kb_id
                      AND v.source_name = LEAST(e.source_name, e.target_name)
                      AND v.target_name = GREATEST(e.source_name, e.target_name)
                WHERE e.kb_id = #{kbId}
                  AND (e.source_name IN
                       <foreach collection="entityNames" item="name" open="(" separator="," close=")">#{name}</foreach>
                       OR e.target_name IN
                       <foreach collection="entityNames" item="name" open="(" separator="," close=")">#{name}</foreach>)
            )
            SELECT deduped.source_name,
                   deduped.target_name,
                   deduped.edge_degree,
                   deduped.weight,
                   deduped.chunk_ids_raw,
                   deduped.description,
                   deduped.keywords_raw,
                   deduped.file_paths_raw,
                   deduped.created_at
            FROM (
                SELECT DISTINCT ON (source_name, target_name) *
                FROM candidate_edges
                ORDER BY source_name, target_name, edge_updated_at DESC
            ) deduped
            ORDER BY edge_degree DESC, weight DESC, source_name, target_name
            LIMIT #{limit}
            </script>
            """)
    List<GraphEdgeProjection> searchRelatedEdgesByDegree(@Param("kbId") Long kbId,
                                                         @Param("entityNames") List<String> entityNames,
                                                         @Param("limit") int limit);

    /**
     * 整库清退分片：按 kb_id 主键子查询分片物理删除关系图行（彻底物理删，无逻辑删墓碑）。
     * <p>{@code DELETE ... WHERE id IN (SELECT id ... ORDER BY id LIMIT n)}：子查询按主键升序
     * 圈定本片行集，保证多事务并发时固定加锁顺序（事务规范 3.1）；单语句即一片，由仓储层
     * 循环至本片不足批大小。幂等：无该库行时零影响返回 0。</p>
     *
     * @param kbId      所属知识库ID（调用方保证非空）
     * @param batchSize 单片删除上限（调用方保证为正）
     * @return 本片实际物理删除行数
     */
    @Delete("""
            DELETE FROM relation_edge_graph
            WHERE id IN (
                SELECT id FROM relation_edge_graph
                WHERE kb_id = #{kbId}
                ORDER BY id
                LIMIT #{batchSize}
            )
            """)
    int physicalDeleteByKbIdBatch(@Param("kbId") Long kbId, @Param("batchSize") int batchSize);

    /**
     * 收敛④：收缩关系图行展示来源列（{@code properties.sourceIds}，剔除入参集合中的 chunkId）。
     * <p>谓词与向量账本收缩语句（{@code RelationInfoVectorMapper#shrinkChunkContributions}）同式：
     * 仅命中「来源列与入参有交集」的行，剩余元素经 {@code jsonb_agg} 重建，理论空集兜底为
     * {@code '[]'}；{@code jsonb_exists} 前置守卫使无该键的行零触碰（不误造键）。
     * MUST 在「剔空物理删图行」之后执行——否则会对即将删除的行做无谓更新。
     * 幂等：无交集的行不命中本语句，重复执行第二次起零影响。
     * {@code kb_id} 等值条件把扫描面收窄到本知识库。</p>
     *
     * @param kbId     所属知识库ID（扫描面收窄条件，调用方保证非空）
     * @param chunkIds 被清退的切片ID列表（非空，调用方已去 null 去重）
     * @return 展示来源列被收缩的图行数（不计入收敛删除计数口径，仅留痕）
     */
    @Update("""
            <script>
            UPDATE relation_edge_graph g
            SET properties = jsonb_set(g.properties, '{sourceIds}',
                    COALESCE((SELECT jsonb_agg(elem)
                              FROM jsonb_array_elements(g.properties -> 'sourceIds') AS elem
                              WHERE (elem #>> '{}')::bigint NOT IN
                              <foreach collection="chunkIds" item="cid" open="(" separator="," close=")">#{cid}</foreach>
                    ), '[]'::jsonb)),
                updated_at = now()
            WHERE g.kb_id = #{kbId}
              AND jsonb_exists(g.properties, 'sourceIds')
              AND EXISTS (
                  SELECT 1 FROM jsonb_array_elements(g.properties -> 'sourceIds') AS elem
                  WHERE (elem #>> '{}')::bigint IN
                  <foreach collection="chunkIds" item="cid" open="(" separator="," close=")">#{cid}</foreach>
              )
            </script>
            """)
    int shrinkDisplaySourceIds(@Param("kbId") Long kbId, @Param("chunkIds") List<Long> chunkIds);

    /**
     * 按无向端点对物理删除关系图边行（重建写回「存活集合为空转删除」逐条原语）。
     * <p>定位与 {@link #selectForUpdateByKbIdAndUnorderedPair} 同口径（双向 OR，历史反向残留行
     * 一次清尽）。行已不存在时零影响（幂等）。MUST 由调用方在事务内经加锁重读判定后调用，
     * 与向量账本行删除（{@code RelationInfoVectorMapper#physicalDeleteByKbIdAndUnorderedPair}）
     * 同事务成对执行（图边先删、向量行后删——与既有四步收敛①②同序）。</p>
     *
     * @param kbId  所属知识库ID（调用方保证非空）
     * @param nameA 无向端点对之一（调用方保证非空）
     * @param nameB 无向端点对之二（调用方保证非空）
     * @return 实际物理删除行数（0~2）
     */
    @Delete("""
            DELETE FROM relation_edge_graph
            WHERE kb_id = #{kbId}
              AND ((source_name = #{nameA} AND target_name = #{nameB})
                   OR (source_name = #{nameB} AND target_name = #{nameA}))
            """)
    int physicalDeleteByKbIdAndUnorderedPair(@Param("kbId") Long kbId, @Param("nameA") String nameA,
                                             @Param("nameB") String nameB);
}