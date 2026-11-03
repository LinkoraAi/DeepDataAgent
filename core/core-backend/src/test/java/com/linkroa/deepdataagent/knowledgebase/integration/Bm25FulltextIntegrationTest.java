package com.linkroa.deepdataagent.knowledgebase.integration;

import com.linkroa.deepdataagent.DeepDataAgentApplication;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.entity.ChunkBm25Hit;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.mapper.ChunkMapper;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BM25 全文检索真库集成测试（pg_textsearch）。
 * <p>覆盖：索引由主表正文承载（无 chunk_tsv）、查询命中且分值降序（零分占位已过滤）、
 * 空正文切片不命中、正文更新后旧词失效、执行计划命中 {@code idx_chunk_bm25_content}。</p>
 * <p>运行前提：{@code TEST_PG_URL} 指向已安装并在 {@code shared_preload_libraries} 预加载
 * {@code pg_textsearch} 的 PostgreSQL 18（且该库已执行 {@code CREATE EXTENSION pg_textsearch}），
 * {@code TEST_REDIS_URL} 指向可用 Redis（全量上下文启动即建连接）。
 * 集成测试被 surefire 排除，需显式放行 {@code @Tag("integration")}。</p>
 */
@Tag("integration")
@SpringBootTest(classes = DeepDataAgentApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class Bm25FulltextIntegrationTest {

    /** 本地 PostgreSQL 连接串环境变量 */
    private static final String ENV_PG_URL = "TEST_PG_URL";

    /** 本地 PostgreSQL 用户名环境变量 */
    private static final String ENV_PG_USERNAME = "TEST_PG_USERNAME";

    /** 本地 PostgreSQL 密码环境变量 */
    private static final String ENV_PG_PASSWORD = "TEST_PG_PASSWORD";

    /** 本地 Redis 连接串环境变量（协调租约 Redisson 客户端启动即连） */
    private static final String ENV_REDIS_URL = "TEST_REDIS_URL";

    /** 测试知识库ID（无外键约束，取高位值避免与业务数据碰撞） */
    private static final Long KB_ID = 987_654_321L;

    /** 命中词 */
    private static final String KEYWORD = "权限管理";

    /** 可见性过滤取值 */
    private static final String STATUS_PROCESSED = "PROCESSED";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ChunkMapper chunkMapper;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        String url = System.getenv(ENV_PG_URL);
        if (StringUtils.isBlank(url)) {
            throw new IllegalStateException(
                    "集成测试需要可用的 PostgreSQL：请设置环境变量 TEST_PG_URL 后运行"
                            + "（可选 TEST_PG_USERNAME / TEST_PG_PASSWORD，默认 postgres）。");
        }
        String redisUrl = System.getenv(ENV_REDIS_URL);
        if (StringUtils.isBlank(redisUrl)) {
            throw new IllegalStateException(
                    "集成测试需要可用的 Redis：请设置环境变量 TEST_REDIS_URL 后运行"
                            + "（形如 redis://:password@127.0.0.1:6379/9）。");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username",
                () -> System.getenv().getOrDefault(ENV_PG_USERNAME, "postgres"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault(ENV_PG_PASSWORD, "postgres"));
    }

    @Test
    void should_returnDescendingRelevantHits_when_searchByBm25_given_chineseChunks() {
        // given：两条命中正文 + 一条空正文切片，所属文档均已处理
        Long documentId = insertProcessedDocument();
        Long hitA = insertChunk(documentId, "权限管理手册：角色与授权流程");
        Long hitB = insertChunk(documentId, "权限管理常见问题与排查步骤");
        Long blank = insertChunk(documentId, null);

        // when
        List<ChunkBm25Hit> hits = chunkMapper.searchByBm25(KB_ID, KEYWORD, 10, STATUS_PROCESSED);

        // then：仅非空正文命中；分值降序（可为负：高 df 词 IDF 不截断）；无零分占位
        List<Long> hitIds = hits.stream().map(ChunkBm25Hit::getChunkId).toList();
        assertTrue(hitIds.contains(hitA));
        assertTrue(hitIds.contains(hitB));
        assertFalse(hitIds.contains(blank));
        double previous = Double.MAX_VALUE;
        for (ChunkBm25Hit hit : hits) {
            assertNotNull(hit.getScore());
            assertNotEquals(0.0D, hit.getScore(), "零分为 UPDATE 占位，应已被 SQL 过滤");
            assertTrue(hit.getScore() <= previous, "命中应按相关度降序");
            previous = hit.getScore();
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void should_missOldKeyword_when_chunkContentUpdated_given_indexFollowsMainTable() {
        // 说明：bm25 索引对「已提交」的 UPDATE 立即收敛（真库探针验证）；但同一未提交事务内
        // 「写后读」会命中旧 posting（索引未做堆可见性回查），属测试隔离伪影而非生产语义——
        // 生产链路检索恒发生在写入事务提交之后。故本用例脱离事务回滚，手工清理。
        Long documentId = insertProcessedDocument();
        Long chunkId = insertChunk(documentId, "权限管理手册：角色与授权流程");
        try {
            // when：直接更新主表正文（无任何全文派生表可写），提交后检索
            jdbcTemplate.update("UPDATE chunk SET chunk_content = ? WHERE id = ?", "完全无关的新内容", chunkId);

            // then：旧词不再命中，新词命中（索引随主表更新自动收敛）
            List<ChunkBm25Hit> oldHits = chunkMapper.searchByBm25(KB_ID, KEYWORD, 10, STATUS_PROCESSED);
            assertFalse(oldHits.stream().map(ChunkBm25Hit::getChunkId).toList().contains(chunkId),
                    "更新后旧词不应命中：" + oldHits);
            List<ChunkBm25Hit> newHits = chunkMapper.searchByBm25(KB_ID, "完全无关", 10, STATUS_PROCESSED);
            assertTrue(newHits.stream().map(ChunkBm25Hit::getChunkId).toList().contains(chunkId),
                    "更新后新词应命中：" + newHits);
        } finally {
            jdbcTemplate.update("DELETE FROM chunk WHERE id = ?", chunkId);
            jdbcTemplate.update("DELETE FROM document WHERE id = ?", documentId);
        }
    }

    @Test
    void should_useBm25Index_when_orderedLimitQuery_given_seqscanDisabled() {
        // given：保证有序 bm25 扫描优先于顺序扫描（SET 与 EXPLAIN 必须同连接，故用 ConnectionCallback）
        Long documentId = insertProcessedDocument();
        insertChunk(documentId, "权限管理手册：角色与授权流程");

        // when
        List<String> plan = jdbcTemplate.execute((Connection connection) -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET enable_seqscan = off");
                try (ResultSet resultSet = statement.executeQuery(
                        "EXPLAIN SELECT c.id FROM chunk c "
                                + "WHERE c.kb_id = " + KB_ID + " "
                                + "AND c.chunk_content IS NOT NULL "
                                + "AND -(c.chunk_content <@> to_bm25query('" + KEYWORD + "', 'idx_chunk_bm25_content')) <> 0 "
                                + "AND EXISTS (SELECT 1 FROM document d "
                                + "WHERE d.id = c.document_id AND d.status = '" + STATUS_PROCESSED + "') "
                                + "ORDER BY c.chunk_content <@> to_bm25query('" + KEYWORD + "', 'idx_chunk_bm25_content') "
                                + "LIMIT 10")) {
                    List<String> lines = new ArrayList<>();
                    while (resultSet.next()) {
                        lines.add(resultSet.getString(1));
                    }
                    return lines;
                }
            }
        });

        // then：执行计划必须出现 bm25 索引名
        String planText = String.join("\n", plan);
        assertTrue(planText.contains("idx_chunk_bm25_content"),
                "执行计划未命中 bm25 索引：" + planText);
    }

    /**
     * 插入一条「已处理」文档并返回主键。
     *
     * @return 文档主键
     */
    private Long insertProcessedDocument() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO document (kb_id, file_name, file_type, status) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, KB_ID, "bm25-itest-" + System.nanoTime() + ".txt", "TXT", STATUS_PROCESSED);
    }

    /**
     * 插入一条切片并返回主键。
     *
     * @param documentId   所属文档主键
     * @param chunkContent 切片正文（可为 null）
     * @return 切片主键
     */
    private Long insertChunk(Long documentId, String chunkContent) {
        Integer maxSequence = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(sequence), -1) FROM chunk WHERE document_id = ?",
                Integer.class, documentId);
        int nextSequence = (maxSequence == null ? -1 : maxSequence) + 1;
        List<Object> args = new ArrayList<>();
        args.add(KB_ID);
        args.add(documentId);
        args.add(nextSequence);
        args.add(chunkContent);
        return jdbcTemplate.queryForObject(
                "INSERT INTO chunk (kb_id, document_id, sequence, chunk_content) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, args.toArray());
    }

    @Test
    void should_notExistChunkTsvTable_when_schemaMigrated() {
        // when
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_class WHERE relname = 'chunk_tsv' AND relkind = 'r'", Integer.class);

        // then：派生全文表已随 V1 移除，全文索引直接建在主表正文上
        assertEquals(0, count);
    }
}