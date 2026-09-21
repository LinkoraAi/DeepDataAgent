package com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.entity.ChunkVectorEntity;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 切片向量 Mapper（chunk_vector 表）。
 * <p>彻底物理删体系：实体无 is_deleted 列、
 * 无 {@code @TableLogic}，内置 delete 即物理 DELETE——HNSW 索引不再被墓碑行污染，
 * 检索质量恒定。</p>
 */
@Mapper
public interface ChunkVectorMapper extends BaseMapper<ChunkVectorEntity> {

    /**
     * 批量插入切片向量（多值 INSERT，向量列由 SQL 显式 {@code ::vector} 转换）。
     * <p>自定义注解 SQL 不触发 MetaObjectHandler，audit 字段必须在 Java 侧经
     * {@code KbAuditFieldUtils.fillInsert} 显式赋值。</p>
     *
     * @param list 待插入向量实体列表（不可为空，调用方需保证非空）
     * @return 插入行数
     */
    @Insert("""
            <script>
            INSERT INTO chunk_vector (kb_id, document_id, chunk_id, chunk_vector,
                                      created_at, updated_at, created_by, updated_by)
            VALUES
            <foreach collection="list" item="it" separator=",">
                (#{it.kbId}, #{it.documentId}, #{it.chunkId}, #{it.chunkVector}::vector,
                 #{it.createdAt}, #{it.updatedAt}, #{it.createdBy}, #{it.updatedBy})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("list") List<ChunkVectorEntity> list);

    /**
     * 按切片主键写入单条切片向量表示：不存在则插入、存在则覆盖。
     * <p>向量以 pgvector 字面量文本传入并显式 {@code ::vector} 转换（口径与 {@link #insertBatch} 一致）；
     * 冲突目标为 {@code chunk_id}（对应唯一索引 {@code uk_chunk_vector_chunk_id}）——命中既有行时
     * 覆盖向量值与 {@code updated_at} / {@code updated_by}，不覆盖 {@code created_at} / {@code created_by}。
     * 由此人工新增切片首次写入即获得表示行，重复编辑亦不会因切片维度唯一约束失败。</p>
     * <p>单语句自提交或并入调用方事务；自定义注解 SQL 不触发 MetaObjectHandler，
     * audit 字段必须在 Java 侧经 {@code KbAuditFieldUtils.fillInsert} 显式赋值。</p>
     *
     * @param entity 待写入向量实体（不可为空，调用方需保证 kbId/documentId/chunkId/chunkVector 均已填充）
     * @return 受影响行数（插入或覆盖均为 1）
     */
    @Insert("""
            INSERT INTO chunk_vector (kb_id, document_id, chunk_id, chunk_vector,
                                      created_at, updated_at, created_by, updated_by)
            VALUES (#{entity.kbId}, #{entity.documentId}, #{entity.chunkId}, #{entity.chunkVector}::vector,
                    #{entity.createdAt}, #{entity.updatedAt}, #{entity.createdBy}, #{entity.updatedBy})
            ON CONFLICT (chunk_id)
            DO UPDATE SET chunk_vector = EXCLUDED.chunk_vector,
                          updated_at = EXCLUDED.updated_at,
                          updated_by = EXCLUDED.updated_by
            """)
    int upsert(@Param("entity") ChunkVectorEntity entity);

    /**
     * 物理删除指定切片的向量记录（无 @TableLogic，delete(wrapper) 即 DELETE FROM）。
     *
     * @param chunkIds 切片 ID 集合
     * @return 受影响行数；入参为空时返回 0
     */
    default int deleteByChunkIds(List<Long> chunkIds) {
        if (ObjectUtils.isEmpty(chunkIds)) {
            return 0;
        }
        return delete(Wrappers.<ChunkVectorEntity>lambdaQuery()
                .in(ChunkVectorEntity::getChunkId, chunkIds));
    }

    /**
     * 物理删除指定文档的全部向量记录（无 @TableLogic，delete(wrapper) 即 DELETE FROM）。
     *
     * @param documentId 文档 ID
     * @return 受影响行数
     */
    default int deleteByDocumentId(Long documentId) {
        return delete(Wrappers.<ChunkVectorEntity>lambdaQuery()
                .eq(ChunkVectorEntity::getDocumentId, documentId));
    }

    /**
     * 物理删除指定知识库的全部向量记录（无 @TableLogic，delete(wrapper) 即 DELETE FROM）。
     *
     * @param kbId 知识库 ID
     * @return 受影响行数
     */
    default int deleteByKbId(Long kbId) {
        return delete(Wrappers.<ChunkVectorEntity>lambdaQuery()
                .eq(ChunkVectorEntity::getKbId, kbId));
    }

    /**
     * VECTOR 通道向量检索：按余弦距离阈值命中切片并返回余弦相似度分。
     * <p>只读检索，供 knowledgebase 侧只读端口暴露给 rag 检索侧；命中条件为余弦距离
     * 小于阈值（阈值 = 1 - similarThreshold），结果按余弦距离升序。相似度分为检索侧临时列。
     * 彻底物理删体系：表内只有存活行，HNSW 近邻检索无墓碑污染。</p>
     * <p><b>可见性过滤</b>：仅返回「所属文档状态命中 {@code documentStatus}」的切片
     * （检索侧传入 {@code DocumentStatus.PROCESSED} 对应取值）——摄入未完成的文档其切片虽已落库，
     * 但图谱与向量尚未就绪，SHALL NOT 占用召回名额进入结果。</p>
     *
     * @param kbId           所属知识库ID
     * @param threshold      余弦距离阈值（命中要求余弦距离 &lt; threshold）
     * @param vec            查询向量字面量（形如 "[0.1,0.2]"）
     * @param limit          返回上限 topN
     * @param documentStatus 可见性过滤的文档状态取值（非空，由调用方以受控枚举名传入）
     * @return 命中切片（score 为余弦相似度，按余弦距离升序）
     */
    @Select("""
            <script>
            SELECT cv.chunk_id,
                   1 - (cv.chunk_vector &lt;=&gt; #{vec}::vector) AS score
            FROM chunk_vector cv
            WHERE cv.kb_id = #{kbId}
              AND cv.chunk_vector IS NOT NULL
              AND (cv.chunk_vector &lt;=&gt; #{vec}::vector) &lt; #{threshold}
              AND EXISTS (SELECT 1 FROM document d
                          WHERE d.id = cv.document_id
                            AND d.status = #{documentStatus})
            ORDER BY cv.chunk_vector &lt;=&gt; #{vec}::vector
            LIMIT #{limit}
            </script>
            """)
    List<ChunkVectorEntity> searchByVector(@Param("kbId") Long kbId,
                                           @Param("threshold") double threshold,
                                           @Param("vec") String vec,
                                           @Param("limit") int limit,
                                           @Param("documentStatus") String documentStatus);
}
