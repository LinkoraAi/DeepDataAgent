package com.linkroa.deepdataagent.rag.infrastructure.repository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.linkroa.deepdataagent.knowledgebase.api.KnowledgeBaseApi;
import com.linkroa.deepdataagent.rag.domain.model.EntityHit;
import com.linkroa.deepdataagent.rag.domain.model.GraphEdgeHit;
import com.linkroa.deepdataagent.rag.domain.model.RelationHit;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.mapper.EntityInfoVectorMapper;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.mapper.EntityNodeGraphMapper;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.mapper.RelationEdgeGraphMapper;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.mapper.RelationInfoVectorMapper;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.projection.EntityVectorHitProjection;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.projection.GraphEdgeProjection;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.projection.GraphNodeAttrProjection;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.projection.RelationVectorHitProjection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JdbcGraphSearchRepository} 检索读路径单元测试（mock rag 自持只读 Mapper）。
 *
 * <p>对齐《realign-rag-retrieval-with-lightrag》任务 2.9 仓储侧覆盖：投影 → 值对象全字段装配
 * （{@link EntityHit}/{@link RelationHit}/{@link GraphEdgeHit}，含 keywords 数组、
 * {@code OffsetDateTime} 时间、weight 缺省 1.0、{@code filePathsRaw} JSON 数组文本
 * 解析为来源路径列表，graph-source-file-paths R1）；{@code graph_missing} 命中记 WARN 并剔除；
 * chunk 账本三态（有值/兜底整数组/两空）在仓储侧表现为对 {@code chunkIdsRaw} 的解析；
 * 检索侧宽容解析（quietly）遇脏数组（非数字元素、非数组文本）返回空列表且不抛，单行脏账本只让该
 * 命中贡献零块；端点属性批量回查 {@code findEntityAttributes}（2.6b）的空入参短路、按名索引、
 * 账本宽容解析与空白名跳过。</p>
 *
 * <p>注解 SQL 文本语义按项目惯例不在单测校验（HNSW/JOIN/COALESCE/DISTINCT ON 由集成环境 EXPLAIN
 * 签核，任务 2.10）。pgvector 字面量转换已有独立口径，本测试对 {@code vecLiteral} 参数以
 * {@code anyString} 匹配，聚焦投影装配。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class JdbcGraphSearchRepositoryTest {

    /** 测试知识库ID */
    private static final Long KB_ID = 7L;

    /** 查询向量（值内容不参与断言，字面量以 anyString 匹配） */
    private static final float[] QUERY_VECTOR = {0.1F, 0.2F};

    /** 余弦距离阈值 */
    private static final double THRESHOLD = 0.5D;

    /** 返回上限 */
    private static final int LIMIT = 10;

    /** 实体相似度分 */
    private static final double SCORE = 0.88D;

    /** 图行创建时间夹具（非零偏移，锁定时间分量直映） */
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2024-05-01T08:30:00+08:00");

    /** 实体向量 Mapper Mock */
    @Mock
    private EntityInfoVectorMapper entityInfoVectorMapper;

    /** 关系向量 Mapper Mock */
    @Mock
    private RelationInfoVectorMapper relationInfoVectorMapper;

    /** 关系边图 Mapper Mock */
    @Mock
    private RelationEdgeGraphMapper relationEdgeGraphMapper;

    /** 实体节点图 Mapper Mock */
    @Mock
    private EntityNodeGraphMapper entityNodeGraphMapper;

    /** 知识库只读契约 Mock */
    @Mock
    private KnowledgeBaseApi knowledgeBaseApi;

    /** 被测检索仓储 */
    @InjectMocks
    private JdbcGraphSearchRepository repository;

    // ===== 实体路 =====

    @Test
    void should_assembleEntityHitFully_when_searchEntities_given_completeProjectionRow() {
        // given：图行存在（graphMissing=false）、账本非空、展示属性齐备（来源路径为多篇 JSON 数组文本）
        when(entityInfoVectorMapper.searchByCosineDistance(eq(KB_ID), anyString(), eq(THRESHOLD), eq(LIMIT)))
                .thenReturn(List.of(entityProjection("张三", SCORE, "[1,2,3]", "人物", "实体描述",
                        filePathsRawOf("docs/张三.md", "docs/团队.xlsx"), CREATED_AT, false)));

        // when
        List<EntityHit> hits = repository.searchEntities(KB_ID, QUERY_VECTOR, THRESHOLD, LIMIT);

        // then：全字段装配，chunkIds 由账本解析、时间直映、分数取 SQL 计算列、filePaths 解析为列表
        assertEquals(1, hits.size());
        EntityHit hit = hits.get(0);
        assertEquals("张三", hit.name());
        assertEquals(SCORE, hit.score(), 1e-9);
        assertEquals(List.of(1L, 2L, 3L), hit.chunkIds());
        assertEquals("人物", hit.entityType());
        assertEquals("实体描述", hit.description());
        assertEquals(List.of("docs/张三.md", "docs/团队.xlsx"), hit.filePaths(),
                "跨文档共享条目的来源路径列表应完整解析");
        assertEquals(CREATED_AT, hit.createdAt());
    }

    @Test
    void should_warnAndDrop_when_searchEntities_given_graphMissingRow() {
        // given：一条图行存在、一条图行缺失（摄入收敛中间态）
        when(entityInfoVectorMapper.searchByCosineDistance(eq(KB_ID), anyString(), eq(THRESHOLD), eq(LIMIT)))
                .thenReturn(List.of(
                        entityProjection("张三", SCORE, "[1]", "人物", "描述",
                                filePathsRawOf("docs/a.md"), CREATED_AT, false),
                        entityProjection("李四", 0.7D, "[2]", null, null, null, null, true)));
        ch.qos.logback.classic.Logger repoLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(JdbcGraphSearchRepository.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        repoLogger.addAppender(appender);
        try {
            // when
            List<EntityHit> hits = repository.searchEntities(KB_ID, QUERY_VECTOR, THRESHOLD, LIMIT);

            // then：仅保留图行存在的命中，缺失者剔除
            assertEquals(1, hits.size());
            assertEquals("张三", hits.get(0).name());
            // then：为被剔除的命中打一条 WARN
            assertTrue(appender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains("李四")));
        } finally {
            repoLogger.detachAppender(appender);
        }
    }

    @Test
    void should_resolveLedgerThreeStates_when_searchEntities_given_coalesceOutcomes() {
        // given：账本有值 / 兜底整数组（SQL 已 COALESCE 出列）/ 两空（SQL 退化为 '[]'）三态
        when(entityInfoVectorMapper.searchByCosineDistance(eq(KB_ID), anyString(), eq(THRESHOLD), eq(LIMIT)))
                .thenReturn(List.of(
                        entityProjection("甲", 0.9D, "[7,8]", "T", "D", filePathsRawOf("f"), CREATED_AT, false),
                        entityProjection("乙", 0.8D, "[9]", "T", "D", filePathsRawOf("f"), CREATED_AT, false),
                        entityProjection("丙", 0.7D, "[]", "T", "D", filePathsRawOf("f"), CREATED_AT, false)));

        // when
        List<EntityHit> hits = repository.searchEntities(KB_ID, QUERY_VECTOR, THRESHOLD, LIMIT);

        // then：分别解析为 [7,8] / [9] / 空（两空贡献零块，不报错）
        assertEquals(List.of(7L, 8L), hits.get(0).chunkIds());
        assertEquals(List.of(9L), hits.get(1).chunkIds());
        assertTrue(hits.get(2).chunkIds().isEmpty());
    }

    @Test
    void should_degradeToEmptyLedger_when_searchEntities_given_dirtyLedgerText() {
        // given：非数字元素数组 + 非数组文本两型脏账本
        when(entityInfoVectorMapper.searchByCosineDistance(eq(KB_ID), anyString(), eq(THRESHOLD), eq(LIMIT)))
                .thenReturn(List.of(
                        entityProjection("脏元素", 0.9D, "[\"a\",1]", "T", "D", filePathsRawOf("f"), CREATED_AT, false),
                        entityProjection("非数组", 0.8D, "not-a-json-array", "T", "D", filePathsRawOf("f"),
                                CREATED_AT, false)));

        // when：宽容解析不抛，仅让该命中贡献零块
        List<EntityHit> hits = assertDoesNotThrow(
                () -> repository.searchEntities(KB_ID, QUERY_VECTOR, THRESHOLD, LIMIT));

        // then：两条命中均在，账本各自归空，其余属性保留
        assertEquals(2, hits.size());
        assertTrue(hits.get(0).chunkIds().isEmpty());
        assertTrue(hits.get(1).chunkIds().isEmpty());
        assertEquals("脏元素", hits.get(0).name());
    }

    @Test
    void should_returnEmptyWithoutQuery_when_searchEntities_given_nullVectorOrNonPositiveLimit() {
        // when & then：空向量与非正上限均短路返回空表，不触碰 Mapper
        assertTrue(repository.searchEntities(KB_ID, null, THRESHOLD, LIMIT).isEmpty());
        assertTrue(repository.searchEntities(KB_ID, new float[0], THRESHOLD, LIMIT).isEmpty());
        assertTrue(repository.searchEntities(KB_ID, QUERY_VECTOR, THRESHOLD, 0).isEmpty());
        verify(entityInfoVectorMapper, never()).searchByCosineDistance(any(), anyString(), anyDouble(), anyInt());
    }

    // ===== 关系路 =====

    @Test
    void should_assembleRelationHitFully_when_searchRelations_given_completeProjectionRow() {
        // given：keywords 为 JSON 数组文本、weight 缺失（SQL 侧本应兜 1.0，此处传 null 验证值对象归一）
        RelationVectorHitProjection row = new RelationVectorHitProjection();
        row.setSourceName("张三");
        row.setTargetName("李四");
        row.setScore(0.77D);
        row.setChunkIdsRaw("[11,12]");
        row.setDescription("关系描述");
        row.setKeywordsRaw("[\"喜欢\",\"关注\"]");
        row.setWeight(null);
        row.setFilePathsRaw(filePathsRawOf("docs/rel.md"));
        row.setCreatedAt(CREATED_AT);
        row.setGraphMissing(false);
        when(relationInfoVectorMapper.searchByCosineDistance(eq(KB_ID), anyString(), eq(THRESHOLD), eq(LIMIT)))
                .thenReturn(List.of(row));

        // when
        List<RelationHit> hits = repository.searchRelations(KB_ID, QUERY_VECTOR, THRESHOLD, LIMIT);

        // then：双端点原生承载、keywords 解析为数组、weight 缺省归一 1.0
        assertEquals(1, hits.size());
        RelationHit hit = hits.get(0);
        assertEquals("张三", hit.sourceName());
        assertEquals("李四", hit.targetName());
        assertEquals(0.77D, hit.score(), 1e-9);
        assertEquals(List.of(11L, 12L), hit.chunkIds());
        assertEquals("关系描述", hit.description());
        assertEquals(List.of("喜欢", "关注"), hit.keywords());
        assertEquals(1.0D, hit.weight(), 1e-9);
        assertEquals(List.of("docs/rel.md"), hit.filePaths());
        assertEquals(CREATED_AT, hit.createdAt());
        assertEquals(List.of("张三", "李四"), hit.normalizedPair());
    }

    @Test
    void should_warnAndDrop_when_searchRelations_given_graphMissingRow() {
        // given：一条正常 + 一条图行缺失
        RelationVectorHitProjection missing = new RelationVectorHitProjection();
        missing.setSourceName("甲");
        missing.setTargetName("乙");
        missing.setScore(0.6D);
        missing.setGraphMissing(true);
        when(relationInfoVectorMapper.searchByCosineDistance(eq(KB_ID), anyString(), eq(THRESHOLD), eq(LIMIT)))
                .thenReturn(List.of(missing));

        // when
        List<RelationHit> hits = repository.searchRelations(KB_ID, QUERY_VECTOR, THRESHOLD, LIMIT);

        // then：缺失者被剔除，结果空
        assertTrue(hits.isEmpty());
    }

    @Test
    void should_degradeToEmptyKeywords_when_searchRelations_given_dirtyKeywordsText() {
        // given：keywords 脏数据 + 账本脏数据，均走宽容解析
        RelationVectorHitProjection row = new RelationVectorHitProjection();
        row.setSourceName("甲");
        row.setTargetName("乙");
        row.setScore(0.6D);
        row.setChunkIdsRaw("{bad}");
        row.setKeywordsRaw("123");
        row.setWeight(2.0D);
        row.setGraphMissing(false);
        when(relationInfoVectorMapper.searchByCosineDistance(eq(KB_ID), anyString(), eq(THRESHOLD), eq(LIMIT)))
                .thenReturn(List.of(row));

        // when
        List<RelationHit> hits = assertDoesNotThrow(
                () -> repository.searchRelations(KB_ID, QUERY_VECTOR, THRESHOLD, LIMIT));

        // then：账本与关键词各自归空、不抛；其余分量保留
        assertEquals(1, hits.size());
        assertTrue(hits.get(0).chunkIds().isEmpty());
        assertTrue(hits.get(0).keywords().isEmpty());
        assertEquals(2.0D, hits.get(0).weight(), 1e-9);
    }

    @Test
    void should_degradeToEmptyFilePaths_when_searchRelations_given_dirtyOrMissingFilePathsText() {
        // given：一条 filePaths 为脏文本（非数组）、一条为 null（JSONB 键缺失出列 NULL）
        RelationVectorHitProjection dirty = new RelationVectorHitProjection();
        dirty.setSourceName("甲");
        dirty.setTargetName("乙");
        dirty.setScore(0.6D);
        dirty.setFilePathsRaw("not-a-json-array");
        dirty.setGraphMissing(false);
        RelationVectorHitProjection absent = new RelationVectorHitProjection();
        absent.setSourceName("丙");
        absent.setTargetName("丁");
        absent.setScore(0.5D);
        absent.setFilePathsRaw(null);
        absent.setGraphMissing(false);
        when(relationInfoVectorMapper.searchByCosineDistance(eq(KB_ID), anyString(), eq(THRESHOLD), eq(LIMIT)))
                .thenReturn(List.of(dirty, absent));

        // when：宽容解析不抛
        List<RelationHit> hits = assertDoesNotThrow(
                () -> repository.searchRelations(KB_ID, QUERY_VECTOR, THRESHOLD, LIMIT));

        // then：两条命中的来源路径列表各自归空，其余分量保留
        assertEquals(2, hits.size());
        assertTrue(hits.get(0).filePaths().isEmpty(), "脏 filePaths 文本应宽容归空列表");
        assertTrue(hits.get(1).filePaths().isEmpty(), "键缺失（NULL 出列）应归空列表");
        assertEquals("甲", hits.get(0).sourceName());
    }

    @Test
    void should_returnEmptyWithoutQuery_when_searchRelations_given_nullVector() {
        // when
        List<RelationHit> hits = repository.searchRelations(KB_ID, null, THRESHOLD, LIMIT);

        // then
        assertTrue(hits.isEmpty());
        verify(relationInfoVectorMapper, never()).searchByCosineDistance(any(), anyString(), anyDouble(), anyInt());
    }

    // ===== 关联边 =====

    @Test
    void should_assembleGraphEdgeHit_when_relatedEdges_given_completeProjectionRows() {
        // given：投影携带度数、权重、账本、属性
        GraphEdgeProjection row = new GraphEdgeProjection();
        row.setSourceName("张三");
        row.setTargetName("李四");
        row.setEdgeDegree(6);
        row.setWeight(2.5D);
        row.setChunkIdsRaw("[21,22]");
        row.setDescription("边描述");
        row.setKeywordsRaw("[\"k1\",\"k2\"]");
        row.setFilePathsRaw(filePathsRawOf("docs/edge.md"));
        row.setCreatedAt(CREATED_AT);
        when(relationEdgeGraphMapper.searchRelatedEdgesByDegree(eq(KB_ID), anyList(), eq(LIMIT)))
                .thenReturn(List.of(row));

        // when
        List<GraphEdgeHit> edges = repository.relatedEdges(KB_ID, List.of("张三", "李四"), LIMIT);

        // then：全字段装配
        assertEquals(1, edges.size());
        GraphEdgeHit edge = edges.get(0);
        assertEquals("张三", edge.sourceName());
        assertEquals("李四", edge.targetName());
        assertEquals(6, edge.edgeDegree());
        assertEquals(2.5D, edge.weight(), 1e-9);
        assertEquals(List.of(21L, 22L), edge.chunkIds());
        assertEquals("边描述", edge.description());
        assertEquals(List.of("k1", "k2"), edge.keywords());
        assertEquals(List.of("docs/edge.md"), edge.filePaths());
        assertEquals(CREATED_AT, edge.createdAt());
    }

    @Test
    void should_defaultWeightAndDegree_when_relatedEdges_given_nullWeightAndDegree() {
        // given：weight/edgeDegree 缺失（null）
        GraphEdgeProjection row = new GraphEdgeProjection();
        row.setSourceName("甲");
        row.setTargetName("乙");
        row.setEdgeDegree(null);
        row.setWeight(null);
        when(relationEdgeGraphMapper.searchRelatedEdgesByDegree(eq(KB_ID), anyList(), eq(LIMIT)))
                .thenReturn(List.of(row));

        // when
        List<GraphEdgeHit> edges = repository.relatedEdges(KB_ID, List.of("甲"), LIMIT);

        // then：度数兜 0、权重归一 1.0
        assertEquals(0, edges.get(0).edgeDegree());
        assertEquals(1.0D, edges.get(0).weight(), 1e-9);
    }

    @Test
    void should_returnEmptyWithoutQuery_when_relatedEdges_given_emptyNames() {
        // when
        List<GraphEdgeHit> edges = repository.relatedEdges(KB_ID, List.of(), LIMIT);

        // then：空名称集合短路，不触碰 Mapper
        assertTrue(edges.isEmpty());
        verify(relationEdgeGraphMapper, never()).searchRelatedEdgesByDegree(any(), anyList(), anyInt());
    }

    // ===== 端点属性批量回查（2.6b） =====

    @Test
    void should_returnEmptyMapWithoutQuery_when_findEntityAttributes_given_emptyNames() {
        // when
        Map<String, EntityHit> attributes = repository.findEntityAttributes(KB_ID, List.of());

        // then：零缺口零查询
        assertTrue(attributes.isEmpty());
        verify(entityNodeGraphMapper, never()).selectByKbIdAndNames(any(), anyList());
    }

    @Test
    void should_indexAttributesByName_when_findEntityAttributes_given_projectionRows() {
        // given：两条图行属性、sourceIds 整数组、来源路径为多篇 JSON 数组文本
        when(entityNodeGraphMapper.selectByKbIdAndNames(eq(KB_ID), eq(List.of("甲", "乙"))))
                .thenReturn(List.of(
                        nodeAttr("甲", "类型甲", "描述甲", filePathsRawOf("docs/甲.md", "docs/共享.pdf"),
                                CREATED_AT, "[31,32]"),
                        nodeAttr("乙", "类型乙", "描述乙", filePathsRawOf("docs/乙.md"), CREATED_AT, "[33]")));

        // when
        Map<String, EntityHit> attributes = repository.findEntityAttributes(KB_ID, List.of("甲", "乙"));

        // then：按名索引、score 恒 0、账本由 sourceIds 解析、属性齐备
        assertEquals(2, attributes.size());
        EntityHit hitA = attributes.get("甲");
        assertEquals("甲", hitA.name());
        assertEquals(0.0D, hitA.score(), 1e-9);
        assertEquals(List.of(31L, 32L), hitA.chunkIds());
        assertEquals("类型甲", hitA.entityType());
        assertEquals("描述甲", hitA.description());
        assertEquals(List.of("docs/甲.md", "docs/共享.pdf"), hitA.filePaths(),
                "端点回查的来源路径应按列表装配");
        assertEquals(CREATED_AT, hitA.createdAt());
        assertEquals(List.of(33L), attributes.get("乙").chunkIds());
    }

    @Test
    void should_omitMissingRowsAndDegradeLedger_when_findEntityAttributes_given_partialAndDirty() {
        // given：仅返回一个图行（另一名称图行缺失，Mapper 不返回），且该行的 sourceIds 为脏数组
        when(entityNodeGraphMapper.selectByKbIdAndNames(eq(KB_ID), anyList()))
                .thenReturn(List.of(nodeAttr("甲", "类型甲", "描述甲", filePathsRawOf("f"), CREATED_AT, "[\"x\"]")));

        // when
        Map<String, EntityHit> attributes = repository.findEntityAttributes(KB_ID, List.of("甲", "乙"));

        // then：乙不出现（保持上层裸名降级判定依据）；甲账本脏→宽容归空、其余属性保留
        assertEquals(1, attributes.size());
        assertTrue(attributes.containsKey("甲"));
        assertFalse(attributes.containsKey("乙"));
        assertTrue(attributes.get("甲").chunkIds().isEmpty());
        assertEquals("类型甲", attributes.get("甲").entityType());
    }

    @Test
    void should_skipBlankNamedRow_when_findEntityAttributes_given_blankEntityName() {
        // given：一行实体名为空白（脏数据），应被跳过
        when(entityNodeGraphMapper.selectByKbIdAndNames(eq(KB_ID), anyList()))
                .thenReturn(List.of(nodeAttr("  ", "T", "D", filePathsRawOf("f"), CREATED_AT, "[1]")));

        // when
        Map<String, EntityHit> attributes = repository.findEntityAttributes(KB_ID, List.of("  "));

        // then：空白名行不入结果
        assertTrue(attributes.isEmpty());
    }

    // ===== 夹具构造 =====

    /**
     * 将若干来源文件路径装配为 SQL 侧 {@code (properties -> 'filePaths')::text} 出列的 JSON 数组文本。
     *
     * @param paths 路径元素（零个时返回 null，模拟 JSONB 键缺失出列 NULL）
     * @return JSON 数组文本（如 {@code ["a.md","b.md"]}）
     */
    private static String filePathsRawOf(String... paths) {
        if (paths.length == 0) {
            return null;
        }
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < paths.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append('"').append(paths[i]).append('"');
        }
        return builder.append(']').toString();
    }

    /**
     * 构造实体路向量命中投影。
     *
     * @param entityName   实体名
     * @param score        相似度分
     * @param chunkIdsRaw  账本 JSON 数组文本
     * @param entityType   类型
     * @param description  描述
     * @param filePathsRaw 来源文件路径列表 JSON 数组文本（可为 null）
     * @param createdAt    创建时间
     * @param graphMissing 图行缺失标志
     * @return 实体命中投影
     */
    private static EntityVectorHitProjection entityProjection(String entityName, double score, String chunkIdsRaw,
                                                              String entityType, String description,
                                                              String filePathsRaw, OffsetDateTime createdAt,
                                                              boolean graphMissing) {
        EntityVectorHitProjection row = new EntityVectorHitProjection();
        row.setEntityName(entityName);
        row.setScore(score);
        row.setChunkIdsRaw(chunkIdsRaw);
        row.setEntityType(entityType);
        row.setDescription(description);
        row.setFilePathsRaw(filePathsRaw);
        row.setCreatedAt(createdAt);
        row.setGraphMissing(graphMissing);
        return row;
    }

    /**
     * 构造端点属性回查投影。
     *
     * @param entityName   实体名
     * @param entityType   类型
     * @param description  描述
     * @param filePathsRaw 来源文件路径列表 JSON 数组文本（可为 null）
     * @param createdAt    创建时间
     * @param sourceIdsRaw 节点账本 JSON 数组文本
     * @return 端点属性投影
     */
    private static GraphNodeAttrProjection nodeAttr(String entityName, String entityType, String description,
                                                    String filePathsRaw, OffsetDateTime createdAt,
                                                    String sourceIdsRaw) {
        GraphNodeAttrProjection row = new GraphNodeAttrProjection();
        row.setEntityName(entityName);
        row.setEntityType(entityType);
        row.setDescription(description);
        row.setFilePathsRaw(filePathsRaw);
        row.setCreatedAt(createdAt);
        row.setSourceIdsRaw(sourceIdsRaw);
        return row;
    }
}
