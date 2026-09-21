package com.linkroa.deepdataagent.rag.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.entity.EntityInfoVectorEntity;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.projection.EntityLedgerOverlapProjection;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.projection.EntityVectorHitProjection;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 实体向量 Mapper（entity_info_vector 表，与 entity_node_graph 1:1）。
 * <p>写路径统一走 {@code ON CONFLICT (kb_id, entity_name)} 增量合并；
 * content_vector 为向量字面量，写入时由 SQL 显式 {@code ::vector} 转换。</p>
 */
@Mapper
public interface EntityInfoVectorMapper extends BaseMapper<EntityInfoVectorEntity> {

    /**
     * 按实体名悲观锁读取单行（read-modify-write 前置）。
     *
     * @param kbId       所属知识库ID
     * @param entityName 实体名称
     * @return 已存在的向量行；不存在返回 null
     */
    default EntityInfoVectorEntity selectForUpdateByKbIdAndName(Long kbId, String entityName) {
        return selectOne(Wrappers.<EntityInfoVectorEntity>lambdaQuery()
                .eq(EntityInfoVectorEntity::getKbId, kbId)
                .eq(EntityInfoVectorEntity::getEntityName, entityName)
                .last("FOR UPDATE"));
    }

    /**
     * 批量 upsert 实体向量（多值 INSERT + {@code ON CONFLICT DO UPDATE}）。
     *
     * @param list 待写入向量列表（非空，调用方保证；chunk_ids 序列化为非空 JSON "[]"，content_vector 可为 null）
     * @return 受影响行数
     */
    @Insert("""
            <script>
            INSERT INTO entity_info_vector (kb_id, entity_name, content, content_vector, chunk_ids, file_path,
                                           created_at, updated_at, created_by, updated_by)
            VALUES
            <foreach collection="list" item="it" separator=",">
                (#{it.kbId}, #{it.entityName}, #{it.content}, #{it.contentVector}::vector,
                 #{it.chunkIds, typeHandler=com.linkroa.deepdataagent.shared.util.PostgresJsonbTypeHandler},
                 #{it.filePath},
                 #{it.createdAt}, #{it.updatedAt}, #{it.createdBy}, #{it.updatedBy})
            </foreach>
            ON CONFLICT (kb_id, entity_name)
            DO UPDATE SET content = EXCLUDED.content,
                          content_vector = EXCLUDED.content_vector,
                          chunk_ids = EXCLUDED.chunk_ids,
                          updated_at = now(),
                          updated_by = EXCLUDED.updated_by
            </script>
            """)
    int upsertBatch(@Param("list") List<EntityInfoVectorEntity> list);

    /**
     * 内容收口窄更新（CAS）：仅更新内容类列，带乐观守卫 {@code content = guardContent}。
     * <p>只改 {@code content} / {@code content_vector} / {@code updated_at}，
     * MUST NOT 触碰 {@code chunk_ids} 等账本列（整行覆盖会回卷并发合并刚并进的来源）；
     * 守卫未命中时受影响 0 行。{@code contentVector} 为向量字面量，由 SQL 侧 {@code ::vector} 转换。</p>
     *
     * @param kbId          所属知识库ID
     * @param entityName    实体名称
     * @param content       期望内容（收口后写入）
     * @param contentVector 期望内容对应的新向量字面量（形如 {@code "[0.1,0.2]"}）
     * @param guardContent  守卫内容（仅当该行当前内容等于此值时才落库）
     * @return 受影响行数（1=命中并更新，0=守卫未命中）
     */
    @Update("""
            UPDATE entity_info_vector
            SET content = #{content},
                content_vector = #{contentVector}::vector,
                updated_at = now()
            WHERE kb_id = #{kbId}
              AND entity_name = #{entityName}
              AND content = #{guardContent}
            """)
    int updateContentIfUnchanged(@Param("kbId") Long kbId, @Param("entityName") String entityName,
                                 @Param("content") String content, @Param("contentVector") String contentVector,
                                 @Param("guardContent") String guardContent);

    /**
     * 检索侧只读：实体路向量命中（①，GRAPH 通道实体路）。
     * <p><b>取数骨架</b>：向量命中（HNSW）+ 图表 1:1 属性点查 JOIN + chunk 账本 COALESCE 兜底。</p>
     * <ul>
     *     <li>pgvector 余弦距离操作符 {@code <=>} 命中：距离小于阈值即召回，按距离升序截断；
     *     {@code score = 1 - 余弦距离}（余弦相似度，越高越相关）。向量以字面量字符串传入并
     *     由 SQL 显式 {@code ::vector} 转换（与 {@link #upsertBatch} 写入风格一致）；</li>
     *     <li><b>展示权威为图表</b>：{@code entity_type}/{@code description}/{@code file_paths_raw}
     *     （{@code properties.filePaths} 的 JSON 数组文本）取图行 {@code properties} 文本，
     *     {@code created_at} 取图行列值
     *     （向量表无 entity_type 列，既然必须 JOIN，其余属性一并同源取，避免 content 与
     *     properties 双源漂移）；JOIN 谓词为唯一索引 {@code uk_entity_node_kb_name}
     *     的两列等值，HNSW topK 行集对图表逐行点查（常量级，非常规顺表扫）；</li>
     *     <li><b>账本权威为向量表</b>：{@code chunk_ids} 非空数组优先，空数组回落图行
     *     {@code properties.sourceIds} <b>整数组</b>（单列 COALESCE 取代旧的「两次往返 +
     *     只取首元素」兜底路径），图行缺失时自然退化为 {@code '[]'}；统一以
     *     {@code ::text} 出列，由仓储层 {@code jsonToLongList} 消化；</li>
     *     <li>{@code graph_missing}（图行 LEFT JOIN 空）标记摄入收敛中间态，
     *     由仓储层记录 WARN 并剔除该命中。</li>
     * </ul>
     * <p>查询不带 {@code <script>}，避免 XML 解析转义 {@code <=>}。</p>
     *
     * @param kbId       所属知识库ID（单库隔离）
     * @param vecLiteral 查询向量字面量，形如 {@code "[0.1,0.2,...]"}（调用方保证非空）
     * @param threshold  余弦距离阈值（命中条件为距离小于该值）
     * @param limit      返回上限（实体路 top_k）
     * @return 命中投影列表（按向量距离升序，含图行属性与账本兜底结果）
     */
    @Select("""
            SELECT v.entity_name,
                   1 - (v.content_vector <=> #{vecLiteral}::vector) AS score,
                   COALESCE(NULLIF(v.chunk_ids, '[]'::jsonb),
                            g.properties -> 'sourceIds', '[]'::jsonb)::text AS chunk_ids_raw,
                   g.properties ->> 'entityType'  AS entity_type,
                   g.properties ->> 'description' AS description,
                   (g.properties -> 'filePaths')::text AS file_paths_raw,
                   g.created_at                   AS created_at,
                   g.entity_name IS NULL          AS graph_missing
            FROM entity_info_vector v
            LEFT JOIN entity_node_graph g
                   ON g.kb_id = v.kb_id AND g.entity_name = v.entity_name
            WHERE v.kb_id = #{kbId}
              AND v.content_vector IS NOT NULL
              AND (v.content_vector <=> #{vecLiteral}::vector) < #{threshold}
            ORDER BY v.content_vector <=> #{vecLiteral}::vector
            LIMIT #{limit}
            """)
    List<EntityVectorHitProjection> searchByCosineDistance(@Param("kbId") Long kbId,
                                                           @Param("vecLiteral") String vecLiteral,
                                                           @Param("threshold") double threshold,
                                                           @Param("limit") int limit);

    /**
     * 收敛①：物理删除「账本剔空」实体对应的图节点行（entity_node_graph）。
     * <p>向量行与图行按 {@code (kb_id, entity_name)} 1:1 关联；剔空谓词 =
     * 账本与入参集合有交集且剔除后无存活元素。必须在
     * {@link #physicalDeletePrunedVectors(Long, List)} 之前执行（依赖向量行存在做关联判定）。
     * 本语句整行物理删除图行、不触碰保留条目的图行属性——保留条目的权重由图谱贡献重建服务
     * 按存活来源全额重算（回扣已实现，RQ-22 已平账），缓存不可用时降级保留原值。</p>
     * <p><b>kb_id 收窄</b>：外层与向量侧子查询均带知识库等值条件——切片主键全局唯一、
     * 只属于一个知识库，收窄不改变命中集，只把逐行展开 JSON 数组的扫描面从「全系统」
     * 收窄到「本知识库」。</p>
     *
     * @param kbId     所属知识库ID（扫描面收窄条件，调用方保证非空）
     * @param chunkIds 被清退的切片ID列表（非空，调用方已去 null 去重）
     * @return 物理删除的图行数
     */
    @Delete("""
            <script>
            DELETE FROM entity_node_graph g
            WHERE g.kb_id = #{kbId}
              AND EXISTS (
                SELECT 1 FROM entity_info_vector v
                WHERE v.kb_id = #{kbId}
                  AND v.entity_name = g.entity_name
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
    int physicalDeletePrunedGraphNodes(@Param("kbId") Long kbId, @Param("chunkIds") List<Long> chunkIds);

    /**
     * 收敛②：物理删除账本剔空的实体向量行（entity_info_vector，彻底物理删、无逻辑删墓碑）。
     * <p>剔空谓词同 {@link #physicalDeletePrunedGraphNodes(Long, List)}；重复执行时
     * 已剔除的 chunkId 与剩余账本零交集，语句零影响（幂等）。
     * {@code kb_id} 等值条件把扫描面收窄到本知识库（不改变命中集）。</p>
     *
     * @param kbId     所属知识库ID（扫描面收窄条件，调用方保证非空）
     * @param chunkIds 被清退的切片ID列表（非空，调用方已去 null 去重）
     * @return 物理删除的向量行数（收敛计数口径）
     */
    @Delete("""
            <script>
            DELETE FROM entity_info_vector v
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
     * 收敛③：收缩仍有存活贡献的实体向量行账本（剔除入参集合中的 chunkId）。
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
            UPDATE entity_info_vector v
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
     * 整库清退分片：按 kb_id 主键子查询分片物理删除实体向量行（彻底物理删，无逻辑删墓碑）。
     * <p>{@code DELETE ... WHERE id IN (SELECT id ... ORDER BY id LIMIT n)}：子查询按主键升序
     * 圈定本片行集，保证多事务并发时固定加锁顺序（事务规范 3.1）；单语句即一片，由仓储层
     * 循环至本片不足批大小。幂等：无该库行时零影响返回 0。</p>
     *
     * @param kbId      所属知识库ID（调用方保证非空）
     * @param batchSize 单片删除上限（调用方保证为正）
     * @return 本片实际物理删除行数
     */
    @Delete("""
            DELETE FROM entity_info_vector
            WHERE id IN (
                SELECT id FROM entity_info_vector
                WHERE kb_id = #{kbId}
                ORDER BY id
                LIMIT #{batchSize}
            )
            """)
    int physicalDeleteByKbIdBatch(@Param("kbId") Long kbId, @Param("batchSize") int batchSize);

    /**
     * 重建分类阶段①：查「向量账本与入参分块集合有交集」的全部实体条目（账本 + 向量内容 +
     * 图行属性一次取齐，重建服务组 4 专用只读语句）。
     * <p>重叠谓词与 {@link #shrinkChunkContributions(Long, List)} 同式
     * （{@code EXISTS (jsonb_array_elements(chunk_ids) 元素 ∈ 入参)}）；账本权威为向量表
     * {@code chunk_ids}。图行经 {@code (kb_id, entity_name)} 唯一索引 LEFT JOIN 带出
     * {@code properties}（降级原值与写回前比较来源），图行缺失以 {@code graph_missing} 标记。
     * 输出按实体名升序（与 {@code selectForUpdateByKbIdAndNames} 加锁序同向）。
     * 只读不加锁、可重复执行——文档删除链崩溃重试时同一批入参恒推出同一受影响集合
     * （「账本 ∩ 本批」谓词即重入依据）。{@code kb_id} 等值条件把逐行展开 JSON 数组的
     * 扫描面收窄到本知识库。实体名集合入参由仓储层保证去 null 去重且非空。</p>
     *
     * @param kbId     所属知识库ID（扫描面收窄条件，调用方保证非空）
     * @param chunkIds 分块ID列表（本批被删分块，非空，调用方已去 null 去重）
     * @return 账本重叠的实体条目投影列表（实体名升序）
     */
    @Select("""
            <script>
            SELECT v.entity_name,
                   v.content,
                   v.content_vector,
                   v.chunk_ids::text AS chunk_ids_raw,
                   g.properties::text AS properties_raw,
                   g.entity_name IS NULL AS graph_missing
            FROM entity_info_vector v
            LEFT JOIN entity_node_graph g
                   ON g.kb_id = v.kb_id AND g.entity_name = v.entity_name
            WHERE v.kb_id = #{kbId}
              AND EXISTS (
                  SELECT 1 FROM jsonb_array_elements(v.chunk_ids) AS elem
                  WHERE (elem #>> '{}')::bigint IN
                  <foreach collection="chunkIds" item="cid" open="(" separator="," close=")">#{cid}</foreach>
              )
            ORDER BY v.entity_name
            </script>
            """)
    List<EntityLedgerOverlapProjection> selectLedgerOverlappingChunks(@Param("kbId") Long kbId,
                                                                      @Param("chunkIds") List<Long> chunkIds);

    /**
     * 按条目身份物理删除单行实体向量（重建写回「存活集合为空转删除」逐条原语）。
     * <p>定位谓词为 {@code (kb_id, entity_name)} 唯一键等值；行已不存在时零影响（幂等）。
     * MUST 由调用方在事务内加锁重读判定后调用，并与图行删除（
     * {@code EntityNodeGraphMapper#physicalDeleteByKbIdAndName}）同事务成对执行。</p>
     *
     * @param kbId       所属知识库ID（调用方保证非空）
     * @param entityName 实体名称（调用方保证非空）
     * @return 实际物理删除行数（0 或 1）
     */
    @Delete("""
            DELETE FROM entity_info_vector
            WHERE kb_id = #{kbId}
              AND entity_name = #{entityName}
            """)
    int physicalDeleteByKbIdAndName(@Param("kbId") Long kbId, @Param("entityName") String entityName);
}