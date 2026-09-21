package com.linkroa.deepdataagent.rag.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.entity.EntityNodeGraphEntity;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.projection.GraphNodeAttrProjection;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 图谱实体节点 Mapper（entity_node_graph 表）。
 * <p>写路径统一走 {@code ON CONFLICT (kb_id, entity_name)} 增量合并；
 * 自定义注解 SQL 不触发 MetaObjectHandler，审计字段必须在 Java 侧显式赋值。</p>
 */
@Mapper
public interface EntityNodeGraphMapper extends BaseMapper<EntityNodeGraphEntity> {

    /**
     * 按名称集合悲观锁读取（read-modify-write 前置）。
     * <p>按 {@code entity_name} 升序 {@code FOR UPDATE}，加锁顺序与调用方无关，
     * 配合事务规范 3.1 固定加锁顺序避免并发合并死锁。</p>
     *
     * @param kbId        所属知识库ID
     * @param entityNames 实体名称集合（为空时直接返回空列表）
     * @return 已存在的实体节点（无锁空名不出现）
     */
    default List<EntityNodeGraphEntity> selectForUpdateByKbIdAndNames(Long kbId, List<String> entityNames) {
        if (ObjectUtils.isEmpty(entityNames)) {
            return List.of();
        }
        return selectList(Wrappers.<EntityNodeGraphEntity>lambdaQuery()
                .eq(EntityNodeGraphEntity::getKbId, kbId)
                .in(EntityNodeGraphEntity::getEntityName, entityNames)
                .orderByAsc(EntityNodeGraphEntity::getEntityName)
                .last("FOR UPDATE"));
    }

    /**
     * 批量 upsert 实体节点（多值 INSERT + {@code ON CONFLICT DO UPDATE}，properties JSONB 经显式 TypeHandler 写入）。
     *
     * @param list 待写入实体节点列表（非空，调用方保证；properties 由调用方序列化为非空 JSON）
     * @return 受影响行数
     */
    @Insert("""
            <script>
            INSERT INTO entity_node_graph (kb_id, entity_name, properties,
                                          created_at, updated_at, created_by, updated_by)
            VALUES
            <foreach collection="list" item="it" separator=",">
                (#{it.kbId}, #{it.entityName},
                 #{it.properties, typeHandler=com.linkroa.deepdataagent.shared.util.PostgresJsonbTypeHandler},
                 #{it.createdAt}, #{it.updatedAt}, #{it.createdBy}, #{it.updatedBy})
            </foreach>
            ON CONFLICT (kb_id, entity_name)
            DO UPDATE SET properties = EXCLUDED.properties,
                          updated_at = now(),
                          updated_by = EXCLUDED.updated_by
            </script>
            """)
    int upsertBatch(@Param("list") List<EntityNodeGraphEntity> list);

    /**
     * 检索侧只读：按实体名集合批量回查图节点展示属性（2.6b，对齐参考 §4.3⑤ {@code get_nodes_batch}）。
     * <p>用于「仅由关系路端点贡献、未被实体路命中」的裸名实体补齐类型/描述/来源文件/创建时间与
     * 节点账本（{@code properties.sourceIds} 整数组），使其以富记录进图谱视图。
     * {@code (kb_id, entity_name)} 唯一索引逐行点查；不带相似度和 {@code graph_missing} 列——
     * 命中与否由返回行集是否含该名表达，缺失者由仓储上层保持裸名降级。</p>
     * <p>仅供检索读路径使用，不参与任何摄入写路径。实体名集合为空时不得调用（IN 空列表非法），
     * 由仓储层前置判空。</p>
     *
     * @param kbId        所属知识库ID（单库隔离，调用方保证非空）
     * @param entityNames 待回查实体名列表（非空，调用方保证）
     * @return 命中的图节点属性投影列表（未命中的名称不出现）
     */
    @Select("""
            <script>
            SELECT g.entity_name,
                   g.properties ->> 'entityType'  AS entity_type,
                   g.properties ->> 'description' AS description,
                   (g.properties -> 'filePaths')::text AS file_paths_raw,
                   g.created_at                   AS created_at,
                   (g.properties -> 'sourceIds')::text AS source_ids_raw
            FROM entity_node_graph g
            WHERE g.kb_id = #{kbId}
              AND g.entity_name IN
              <foreach collection="entityNames" item="name" open="(" separator="," close=")">#{name}</foreach>
            </script>
            """)
    List<GraphNodeAttrProjection> selectByKbIdAndNames(@Param("kbId") Long kbId,
                                                       @Param("entityNames") List<String> entityNames);

    /**
     * 整库清退分片①：按 kb_id 主键子查询分片物理删除图行（彻底物理删，无逻辑删墓碑）。
     * <p>{@code DELETE ... WHERE id IN (SELECT id ... ORDER BY id LIMIT n)}：子查询按主键升序
     * 圈定本片行集，保证多事务并发时固定加锁顺序（事务规范 3.1）；单语句即一片，由仓储层
     * 循环至本片不足批大小。幂等：无该库行时零影响返回 0。</p>
     *
     * @param kbId      所属知识库ID（调用方保证非空）
     * @param batchSize 单片删除上限（调用方保证为正）
     * @return 本片实际物理删除行数
     */
    @Delete("""
            DELETE FROM entity_node_graph
            WHERE id IN (
                SELECT id FROM entity_node_graph
                WHERE kb_id = #{kbId}
                ORDER BY id
                LIMIT #{batchSize}
            )
            """)
    int physicalDeleteByKbIdBatch(@Param("kbId") Long kbId, @Param("batchSize") int batchSize);

    /**
     * 收敛④：收缩实体图行展示来源列（{@code properties.sourceIds}，剔除入参集合中的 chunkId）。
     * <p>谓词与向量账本收缩语句（{@code EntityInfoVectorMapper#shrinkChunkContributions}）同式：
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
            UPDATE entity_node_graph g
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
     * 按实体名物理删除单行实体图节点（重建写回「存活集合为空转删除」逐条原语）。
     * <p>定位谓词为 {@code (kb_id, entity_name)} 唯一键等值；行已不存在时零影响（幂等）。
     * MUST 由调用方在事务内经 {@link #selectForUpdateByKbIdAndNames} 加锁重读判定后调用，
     * 与向量账本行删除（{@code EntityInfoVectorMapper#physicalDeleteByKbIdAndName}）
     * 同事务成对执行（图行先删、向量行后删——与既有四步收敛①②同序）。</p>
     *
     * @param kbId       所属知识库ID（调用方保证非空）
     * @param entityName 实体名称（调用方保证非空）
     * @return 实际物理删除行数（0 或 1）
     */
    @Delete("""
            DELETE FROM entity_node_graph
            WHERE kb_id = #{kbId}
              AND entity_name = #{entityName}
            """)
    int physicalDeleteByKbIdAndName(@Param("kbId") Long kbId, @Param("entityName") String entityName);
}