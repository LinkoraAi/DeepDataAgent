package com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.entity.ChunkTsvEntity;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 切片全文检索 Mapper（chunk_tsv 表）。
 * <p>彻底物理删体系：实体无 is_deleted 列、
 * 无 {@code @TableLogic}，内置 delete 即物理 DELETE，墓碑行不再污染全文索引。</p>
 */
@Mapper
public interface ChunkTsvMapper extends BaseMapper<ChunkTsvEntity> {

    /**
     * 全文检索分词配置名单点常量。
     * <p>写入侧 {@code to_tsvector} 与查询侧 {@code websearch_to_tsquery} 共用同一配置名，
     * 单点定义防止两侧口径漂移；必须与 {@code docker/postgresql/init/00-init-extensions.sql}
     * 中创建的 {@code zhparser_cfg} 保持一致。</p>
     */
    String FULLTEXT_TS_CONFIG = "zhparser_cfg";

    /**
     * 批量插入切片全文表示（多值 INSERT）。
     * <p>content_tsv 列由 SQL 内 {@code to_tsvector(tsConfig, chunkContent)} 对切片原文
     * <b>现算</b>分词生成（DB 侧分词，应用侧不再预生成字面量）；原文为 NULL 时列为 NULL。
     * 自定义注解 SQL 不触发 MetaObjectHandler，audit 字段必须在 Java 侧经
     * {@code KbAuditFieldUtils.fillInsert} 显式赋值。</p>
     *
     * @param list     待插入全文实体列表（不可为空，调用方需保证非空，实体的 chunkContent 承载原文）
     * @param tsConfig 分词配置名（取 {@link #FULLTEXT_TS_CONFIG}）
     * @return 插入行数
     */
    @Insert("""
            <script>
            INSERT INTO chunk_tsv (kb_id, document_id, chunk_id, content_tsv,
                                   created_at, updated_at, created_by, updated_by)
            VALUES
            <foreach collection="list" item="it" separator=",">
                (#{it.kbId}, #{it.documentId}, #{it.chunkId},
                 to_tsvector(#{tsConfig}::regconfig, #{it.chunkContent}),
                 #{it.createdAt}, #{it.updatedAt}, #{it.createdBy}, #{it.updatedBy})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("list") List<ChunkTsvEntity> list,
                    @Param("tsConfig") String tsConfig);

    /**
     * 按切片主键写入单条切片全文表示：不存在则插入、存在则覆盖。
     * <p>{@code content_tsv} 由 SQL 内
     * {@code to_tsvector(#{tsConfig}::regconfig, #{entity.chunkContent})} 对切片原文<b>现算</b>
     * 分词生成（分词职责在存储侧，口径与 {@link #insertBatch} 一致，应用侧不传预分词字面量）；
     * 冲突目标为 {@code chunk_id}（对应唯一索引 {@code uk_chunk_tsv_chunk_id}）——命中既有行时
     * 覆盖 tsvector 与 {@code updated_at} / {@code updated_by}，不覆盖 {@code created_at} / {@code created_by}。</p>
     * <p>单语句自提交或并入调用方事务；自定义注解 SQL 不触发 MetaObjectHandler，
     * audit 字段必须在 Java 侧经 {@code KbAuditFieldUtils.fillInsert} 显式赋值。</p>
     *
     * @param entity   待写入全文实体（不可为空，实体的 chunkContent 承载切片原文）
     * @param tsConfig 分词配置名（取 {@link #FULLTEXT_TS_CONFIG}）
     * @return 受影响行数（插入或覆盖均为 1）
     */
    @Insert("""
            INSERT INTO chunk_tsv (kb_id, document_id, chunk_id, content_tsv,
                                   created_at, updated_at, created_by, updated_by)
            VALUES (#{entity.kbId}, #{entity.documentId}, #{entity.chunkId},
                    to_tsvector(#{tsConfig}::regconfig, #{entity.chunkContent}),
                    #{entity.createdAt}, #{entity.updatedAt}, #{entity.createdBy}, #{entity.updatedBy})
            ON CONFLICT (chunk_id)
            DO UPDATE SET content_tsv = EXCLUDED.content_tsv,
                          updated_at = EXCLUDED.updated_at,
                          updated_by = EXCLUDED.updated_by
            """)
    int upsert(@Param("entity") ChunkTsvEntity entity, @Param("tsConfig") String tsConfig);

    /**
     * 物理删除指定切片的全文表示记录（无 @TableLogic，delete(wrapper) 即 DELETE FROM）。
     *
     * @param chunkIds 切片 ID 集合
     * @return 受影响行数；入参为空时返回 0
     */
    default int deleteByChunkIds(List<Long> chunkIds) {
        if (ObjectUtils.isEmpty(chunkIds)) {
            return 0;
        }
        return delete(Wrappers.<ChunkTsvEntity>lambdaQuery()
                .in(ChunkTsvEntity::getChunkId, chunkIds));
    }

    /**
     * 物理删除指定文档的全部全文表示记录（无 @TableLogic，delete(wrapper) 即 DELETE FROM）。
     *
     * @param documentId 文档 ID
     * @return 受影响行数
     */
    default int deleteByDocumentId(Long documentId) {
        return delete(Wrappers.<ChunkTsvEntity>lambdaQuery()
                .eq(ChunkTsvEntity::getDocumentId, documentId));
    }

    /**
     * 物理删除指定知识库的全部全文表示记录（无 @TableLogic，delete(wrapper) 即 DELETE FROM）。
     *
     * @param kbId 知识库 ID
     * @return 受影响行数
     */
    default int deleteByKbId(Long kbId) {
        return delete(Wrappers.<ChunkTsvEntity>lambdaQuery()
                .eq(ChunkTsvEntity::getKbId, kbId));
    }

    /**
     * BM25 通道全文检索：按关键词匹配命中切片并返回相关度分。
     * <p>只读检索，供 knowledgebase 侧只读端口暴露给 rag 检索侧；
     * {@code websearch_to_tsquery(tsConfig, query)} 与写入侧共用同一分词配置
     * （{@link #FULLTEXT_TS_CONFIG}，zhparser 中文分词），保证中英查询都能按词命中；
     * 相关度由 {@code ts_rank_cd} 计算（排序靠 rank，阈值归一化在调用方做）。
     * 彻底物理删体系：表内只有存活行，无墓碑干扰。</p>
     * <p><b>可见性过滤</b>：仅返回「所属文档状态命中 {@code documentStatus}」的切片
     * （检索侧传入 {@code DocumentStatus.PROCESSED} 对应取值）——摄入未完成的文档其切片虽已落库，
     * 但图谱与向量尚未就绪，SHALL NOT 占用召回名额进入结果。</p>
     *
     * @param kbId           所属知识库ID
     * @param query          用户关键词（或改写后 query）
     * @param limit          返回上限 topN
     * @param tsConfig       分词配置名（取 {@link #FULLTEXT_TS_CONFIG}）
     * @param documentStatus 可见性过滤的文档状态取值（非空，由调用方以受控枚举名传入）
     * @return 命中切片（score 为 ts_rank_cd 相关度分，降序）
     */
    @Select("""
            <script>
            SELECT tsv.chunk_id,
                   ts_rank_cd(tsv.content_tsv, query) AS score
            FROM chunk_tsv tsv,
                 websearch_to_tsquery(#{tsConfig}::regconfig, #{query}) AS query
            WHERE tsv.kb_id = #{kbId}
              AND tsv.content_tsv @@ query
              AND EXISTS (SELECT 1 FROM document d
                          WHERE d.id = tsv.document_id
                            AND d.status = #{documentStatus})
            ORDER BY score DESC
            LIMIT #{limit}
            </script>
            """)
    List<ChunkTsvEntity> searchByKeywords(@Param("kbId") Long kbId,
                                          @Param("query") String query,
                                          @Param("limit") int limit,
                                          @Param("tsConfig") String tsConfig,
                                          @Param("documentStatus") String documentStatus);
}
