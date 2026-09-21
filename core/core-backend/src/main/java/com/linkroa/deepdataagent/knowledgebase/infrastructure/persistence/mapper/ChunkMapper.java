package com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentStatus;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.entity.ChunkEntity;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * 文档切片 Mapper。
 * <p>彻底物理删体系：实体无 is_deleted 列、
 * 无 {@code @TableLogic}，内置 delete / deleteById 即物理 DELETE。</p>
 * <p><b>Lambda 列写法约束</b>：条件构造器（{@code lambdaQuery/lambdaUpdate}）的列参数必须使用
 * 方法引用（如 {@code ChunkEntity::getId}），禁止箭头 lambda（如 {@code e -> e.getId()}）——
 * MyBatis-Plus 依据 lambda 序列化元信息的方法名解析列名，合成方法名（{@code lambda$xxx}）
 * 会直接抛 {@code ReflectionException: Didn't start with 'is', 'get' or 'set'}。</p>
 */
@Mapper
public interface ChunkMapper extends BaseMapper<ChunkEntity> {

    /**
     * 检索可见性过滤子查询：仅「已处理」文档的切片可被检索侧回取。
     * <p>取值来源为 {@link DocumentStatus#PROCESSED} 的枚举名（单一真相源，避免 SQL 内硬编码字面量）；
     * 摄入中（{@code PENDING / PROCESSING}，含重新解析窗口）、已失败（{@code FAILED}）与删除链
     * （{@code DELETING / DELETE_FAILED}）状态的文档，其切片 SHALL NOT 被检索链路消费。</p>
     */
    String RETRIEVAL_VISIBLE_DOCUMENT_SUB_QUERY = "SELECT id FROM document WHERE status = '"
            + DocumentStatus.PROCESSED.name() + "'";

    /**
     * 批量插入切片（多值 INSERT）。
     * <p>MyBatis-Plus 内置批处理 default 方法在 Spring 事务内的 BATCH 执行器语义无法确证，
     * 因此使用 {@code <foreach>} 多值 INSERT 确保单语句确定走当前连接/事务。</p>
     * <p>自定义注解 SQL 不触发 MetaObjectHandler，audit 字段必须在 Java 侧经
     * {@code KbAuditFieldUtils.fillInsert} 显式赋值。</p>
     *
     * @param list 待插入切片实体列表（不可为空，调用方需保证非空）
     * @return 插入行数
     */
    @Insert("""
            <script>
            INSERT INTO chunk (kb_id, document_id, sequence, tokens, chunk_content,
                               original_item, chunk_content_type, source_file_name,
                               s3_file, source_type, created_at, updated_at, created_by, updated_by)
            VALUES
            <foreach collection="list" item="it" separator=",">
                (#{it.kbId}, #{it.documentId}, #{it.sequence}, #{it.tokens}, #{it.chunkContent},
                 #{it.originalItem}::jsonb, #{it.chunkContentType}, #{it.sourceFileName},
                 #{it.s3File}::jsonb, #{it.sourceType}, #{it.createdAt}, #{it.updatedAt}, #{it.createdBy}, #{it.updatedBy})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("list") List<ChunkEntity> list);

    /**
     * 按知识库ID + 切片ID集合批量查询切片（读侧只读，检索消费用）。
     * <p>等值条件 {@code kb_id} + 主键 {@code IN} 保证单库隔离；已删切片行物理不存在，天然不可达。</p>
     * <p><b>可见性过滤</b>：仅返回所属文档处于「已处理」状态的切片（见
     * {@link #RETRIEVAL_VISIBLE_DOCUMENT_SUB_QUERY}）——本方法为检索链路按主键回取切片正文的
     * 单点收口，图谱通道账本带来的切片标识与精排 / 上下文 / 答案链路同受此约束；
     * 管理侧切片列表与详情走按文档分页的其它查询，不受本过滤影响。</p>
     *
     * @param kbId     知识库ID，可为空；为空返回空列表
     * @param chunkIds 切片ID集合，可为空；为空返回空列表
     * @return 切片实体列表（id 升序）；无命中返回空列表
     */
    default List<ChunkEntity> selectByKbIdAndIds(Long kbId, Collection<Long> chunkIds) {
        if (ObjectUtils.isEmpty(kbId) || ObjectUtils.isEmpty(chunkIds)) {
            return List.of();
        }
        return selectList(Wrappers.<ChunkEntity>lambdaQuery()
                .eq(ChunkEntity::getKbId, kbId)
                .in(ChunkEntity::getId, chunkIds)
                .inSql(ChunkEntity::getDocumentId, RETRIEVAL_VISIBLE_DOCUMENT_SUB_QUERY)
                .orderByAsc(ChunkEntity::getId));
    }

    /**
     * 按文档 ID 查询全部切片（不分页，仓储内部用于批插后回查 sequence→id 映射）。
     *
     * @param documentId 文档 ID
     * @return 按 sequence 升序排列的切片列表
     */
    default List<ChunkEntity> selectAllByDocumentId(Long documentId) {
        return selectList(Wrappers.<ChunkEntity>lambdaQuery()
                .eq(ChunkEntity::getDocumentId, documentId)
                .orderByAsc(ChunkEntity::getSequence));
    }

    /**
     * 文档内切片分页查询。
     * <p>
     * 按 sequence 升序返回，保证切片重装配为原文的顺序稳定。
     * </p>
     *
     * @param documentId 文档ID
     * @param offset     偏移量
     * @param size       每页条数
     * @return 切片实体列表
     */
    default List<ChunkEntity> selectByDocumentId(Long documentId, long offset, int size) {
        return selectList(Wrappers.<ChunkEntity>lambdaQuery()
                .eq(ChunkEntity::getDocumentId, documentId)
                .orderByAsc(ChunkEntity::getSequence)
                .orderByAsc(ChunkEntity::getId)
                .last("LIMIT " + size + " OFFSET " + offset));
    }

    /**
     * 统计文档内切片数。
     *
     * @param documentId 文档ID
     * @return 命中记录数
     */
    default long countByDocumentId(Long documentId) {
        Long count = selectCount(Wrappers.<ChunkEntity>lambdaQuery()
                .eq(ChunkEntity::getDocumentId, documentId));
        return ObjectUtils.isEmpty(count) ? 0L : count;
    }

    /**
     * 知识库内切片条件分页查询。
     *
     * @param kbId       知识库ID
     * @param documentId 文档ID（可空）
     * @param sequence   切片序号（可空）
     * @param keyword    切片内容关键字（可空）
     * @param offset     偏移量
     * @param size       每页条数
     * @return 切片实体列表
     */
    default List<ChunkEntity> selectByKbId(Long kbId, Long documentId, Integer sequence, String keyword,
                                           long offset, int size) {
        return selectList(buildCondition(kbId, documentId, sequence, keyword)
                .orderByAsc(ChunkEntity::getKbId)
                .orderByAsc(ChunkEntity::getDocumentId)
                .orderByAsc(ChunkEntity::getSequence)
                .orderByAsc(ChunkEntity::getId)
                .last("LIMIT " + size + " OFFSET " + offset));
    }

    /**
     * 知识库内切片条件统计。
     *
     * @param kbId       知识库ID
     * @param documentId 文档ID（可空）
     * @param sequence   切片序号（可空）
     * @param keyword    切片内容关键字（可空）
     * @return 命中记录数
     */
    default long countByKbId(Long kbId, Long documentId, Integer sequence, String keyword) {
        Long count = selectCount(buildCondition(kbId, documentId, sequence, keyword));
        return ObjectUtils.isEmpty(count) ? 0L : count;
    }

    /**
     * 统计知识库内切片数。
     *
     * @param kbId 知识库ID
     * @return 命中记录数
     */
    default long countByKbIdOnly(Long kbId) {
        Long count = selectCount(Wrappers.<ChunkEntity>lambdaQuery()
                .eq(ChunkEntity::getKbId, kbId));
        return ObjectUtils.isEmpty(count) ? 0L : count;
    }

    /**
     * 物理删除文档内全部切片（无 @TableLogic，delete(wrapper) 即 DELETE FROM）。
     *
     * @param documentId 文档ID
     * @return 受影响行数
     */
    default int deleteByDocumentId(Long documentId) {
        return delete(Wrappers.<ChunkEntity>lambdaQuery()
                .eq(ChunkEntity::getDocumentId, documentId));
    }

    /**
     * 物理删除知识库内全部切片（无 @TableLogic，delete(wrapper) 即 DELETE FROM）。
     *
     * @param kbId 知识库ID
     * @return 受影响行数
     */
    default int deleteByKbId(Long kbId) {
        return delete(Wrappers.<ChunkEntity>lambdaQuery()
                .eq(ChunkEntity::getKbId, kbId));
    }

    /**
     * 分批取知识库内切片的主键 ID（id 升序，清退调度用，仅回读 id 列）。
     * <p>彻底物理删体系：已删切片行物理不存在，逐轮从头取 LIMIT 即可续跑。</p>
     *
     * @param kbId  知识库ID
     * @param limit 单批上限
     * @return 切片 ID 列表（升序）；无命中返回空列表
     */
    default List<Long> selectIdsByKbId(Long kbId, int limit) {
        if (ObjectUtils.isEmpty(kbId) || limit <= 0) {
            return List.of();
        }
        return selectList(Wrappers.<ChunkEntity>lambdaQuery()
                .select(ChunkEntity::getId)
                .eq(ChunkEntity::getKbId, kbId)
                .orderByAsc(ChunkEntity::getId)
                .last("LIMIT " + limit))
                .stream()
                .map(ChunkEntity::getId)
                .toList();
    }

    /**
     * 分批取文档内切片的主键 ID（id 升序，文档删除链分批清退用，仅回读 id 列）。
     * <p>彻底物理删体系：已删切片行物理不存在，逐轮从头取 LIMIT 即可续跑。</p>
     *
     * @param documentId 文档ID
     * @param limit      单批上限
     * @return 切片 ID 列表（升序）；入参非法或无命中返回空列表
     */
    default List<Long> selectIdsByDocumentId(Long documentId, int limit) {
        if (ObjectUtils.isEmpty(documentId) || limit <= 0) {
            return List.of();
        }
        return selectList(Wrappers.<ChunkEntity>lambdaQuery()
                .select(ChunkEntity::getId)
                .eq(ChunkEntity::getDocumentId, documentId)
                .orderByAsc(ChunkEntity::getId)
                .last("LIMIT " + limit))
                .stream()
                .map(ChunkEntity::getId)
                .toList();
    }

    /**
     * 按文档 ID 查询全部切片的身份投影（仅回读 id + sequence 两列，sequence 升序）。
     * <p>摄入产物溯源回填用：只需 sequence→id 映射，不携带切片正文等重内容；
     * 已删切片行物理不存在，天然不可达。</p>
     *
     * @param documentId 文档 ID，可为空；为空返回空列表
     * @return 仅含主键与序号的切片实体列表（sequence 升序）；无命中返回空列表
     */
    default List<ChunkEntity> selectIdAndSequenceByDocumentId(Long documentId) {
        if (ObjectUtils.isEmpty(documentId)) {
            return List.of();
        }
        return selectList(Wrappers.<ChunkEntity>lambdaQuery()
                .select(ChunkEntity::getId, ChunkEntity::getSequence)
                .eq(ChunkEntity::getDocumentId, documentId)
                .orderByAsc(ChunkEntity::getSequence));
    }

    /**
     * 按主键集合批量物理删除切片（无 @TableLogic，delete(wrapper) 即 DELETE FROM）。
     *
     * @param ids 切片 ID 集合
     * @return 受影响行数；空集合直接返回 0
     */
    default int deleteByIds(List<Long> ids) {
        if (ObjectUtils.isEmpty(ids)) {
            return 0;
        }
        return delete(Wrappers.<ChunkEntity>lambdaQuery()
                .in(ChunkEntity::getId, ids));
    }

    /**
     * 按主键集合回取「id + document_id」轻量投影（id 升序，删除链确定受影响文档用）。
     * <p>主键 {@code IN} 天然走索引；不回读切片正文等重列。彻底物理删体系：已删切片行物理不存在，
     * 不出现在结果中，由调用方按「请求ID − 命中ID」差集判定缺失。</p>
     *
     * @param chunkIds 切片ID集合，可为空；为空返回空列表
     * @return 仅含主键与文档ID的切片实体列表（id 升序）
     */
    default List<ChunkEntity> selectIdAndDocumentIdByIds(Collection<Long> chunkIds) {
        if (ObjectUtils.isEmpty(chunkIds)) {
            return List.of();
        }
        return selectList(Wrappers.<ChunkEntity>lambdaQuery()
                .select(ChunkEntity::getId, ChunkEntity::getDocumentId)
                .in(ChunkEntity::getId, chunkIds)
                .orderByAsc(ChunkEntity::getId));
    }

    /**
     * 按主键集合回取「id + kb_id」轻量投影（id 升序，删除链图谱收敛按库收窄预取用）。
     * <p>主键 {@code IN} 天然走索引；不回读切片正文等重列。彻底物理删体系：已删切片行物理不存在，
     * 不出现在结果中。调用方 MUST 在物理删除切片行<b>之前</b>取数——行一旦消失即无从反查所属知识库。</p>
     *
     * @param chunkIds 切片ID集合，可为空；为空返回空列表
     * @return 仅含主键与知识库ID的切片实体列表（id 升序）
     */
    default List<ChunkEntity> selectIdAndKbIdByIds(Collection<Long> chunkIds) {
        if (ObjectUtils.isEmpty(chunkIds)) {
            return List.of();
        }
        return selectList(Wrappers.<ChunkEntity>lambdaQuery()
                .select(ChunkEntity::getId, ChunkEntity::getKbId)
                .in(ChunkEntity::getId, chunkIds)
                .orderByAsc(ChunkEntity::getId));
    }

    /**
     * 回取文档内全部多模态图片引用投影（仅 id + s3_file 两列，id 升序）。
     * <p>{@code s3_file IS NOT NULL} 前置过滤使结果集规模等于该文档的图片切片数，
     * 供删除前的同文档对象引用计数使用。</p>
     *
     * @param documentId 文档ID，可为空；为空返回空列表
     * @return 仅含主键与媒体引用的切片实体列表（id 升序）
     */
    default List<ChunkEntity> selectMediaReferencesByDocumentId(Long documentId) {
        if (ObjectUtils.isEmpty(documentId)) {
            return List.of();
        }
        return selectList(Wrappers.<ChunkEntity>lambdaQuery()
                .select(ChunkEntity::getId, ChunkEntity::getS3File)
                .eq(ChunkEntity::getDocumentId, documentId)
                .isNotNull(ChunkEntity::getS3File)
                .orderByAsc(ChunkEntity::getId));
    }

    /**
     * 查询文档内当前最大分块序号（仅回读 sequence 一列，按 sequence 降序取首行）。
     * <p>人工新增序号服务端分配的前置查询：新分块序号 = 本方法返回值加一（无切片时返回 null，
     * 调用方按首个分块自 0 起分配）。调用方 MUST 在事务内持有文档行锁后执行，
     * 使同文档并发新增被行锁串行化、天然不冲突。</p>
     *
     * @param documentId 文档ID，可为空；为空返回 null
     * @return 当前最大序号；文档无切片返回 null
     */
    default Integer selectMaxSequenceByDocumentId(Long documentId) {
        if (ObjectUtils.isEmpty(documentId)) {
            return null;
        }
        List<ChunkEntity> rows = selectList(Wrappers.<ChunkEntity>lambdaQuery()
                .select(ChunkEntity::getSequence)
                .eq(ChunkEntity::getDocumentId, documentId)
                .orderByDesc(ChunkEntity::getSequence)
                .last("LIMIT 1"));
        if (ObjectUtils.isEmpty(rows)) {
            return null;
        }
        return rows.get(0).getSequence();
    }

    /**
     * 按主键集合回取「id + source_type」轻量投影（id 升序，人工删除来源校验与收敛触发推导用）。
     * <p>主键 {@code IN} 天然走索引；不回读切片正文等重列。彻底物理删体系：已删切片行物理不存在，
     * 不出现在结果中。调用方 MUST 在物理删除切片行<b>之前</b>取数——行一旦消失即无从反查来源。</p>
     *
     * @param chunkIds 切片ID集合，可为空；为空返回空列表
     * @return 仅含主键与来源标识的切片实体列表（id 升序）
     */
    default List<ChunkEntity> selectIdAndSourceTypeByIds(Collection<Long> chunkIds) {
        if (ObjectUtils.isEmpty(chunkIds)) {
            return List.of();
        }
        return selectList(Wrappers.<ChunkEntity>lambdaQuery()
                .select(ChunkEntity::getId, ChunkEntity::getSourceType)
                .in(ChunkEntity::getId, chunkIds)
                .orderByAsc(ChunkEntity::getId));
    }

    /**
     * 构建切片查询条件：各维度入参为空时表示该维度不加过滤（支持全库统计口径）。
     *
     * @param kbId       知识库ID（可空）
     * @param documentId 文档ID（可空）
     * @param sequence   切片序号（可空）
     * @param keyword    切片内容关键字（可空）
     * @return 条件构造器
     */
    private LambdaQueryWrapper<ChunkEntity> buildCondition(Long kbId, Long documentId, Integer sequence,
                                                           String keyword) {
        return Wrappers.<ChunkEntity>lambdaQuery()
                .eq(!ObjectUtils.isEmpty(kbId), ChunkEntity::getKbId, kbId)
                .eq(!ObjectUtils.isEmpty(documentId), ChunkEntity::getDocumentId, documentId)
                .eq(!ObjectUtils.isEmpty(sequence), ChunkEntity::getSequence, sequence)
                .like(StringUtils.isNotBlank(keyword), ChunkEntity::getChunkContent, keyword);
    }
}
