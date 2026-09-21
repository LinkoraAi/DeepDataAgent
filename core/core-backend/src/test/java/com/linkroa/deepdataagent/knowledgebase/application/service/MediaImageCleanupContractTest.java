package com.linkroa.deepdataagent.knowledgebase.application.service;

import com.linkroa.deepdataagent.rag.domain.service.MediaImageObjectKeys;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 媒体图片对象前缀约定的契约测试。
 * <p>把「写入方（rag BC {@link MediaImageObjectKeys#prefixOf}）与清理方（knowledgebase BC
 * {@link MediaImageObjectPrefixes#documentPrefix}）前缀约定必须逐字符一致」从注释要求升级为构建期强制断言：
 * 任一侧改形而未同步另一侧时本测试立即失败，并给出两侧失配的前缀值。</p>
 * <p>本测试与两侧类同处 core-backend 单一 Maven 模块，故仅测试期可同时引用两 BC 的类；
 * 主代码仍维持 rag → knowledgebase 的单向依赖，不因本测试被破坏。纯静态断言，无需 Mockito。</p>
 *
 * @author DeepDataAgent
 */
class MediaImageCleanupContractTest {

    /** 参与比对的 (kbId, documentId) 组合：覆盖常规值、边界极值与非法零/负值 */
    private static final Long[][] ID_PAIRS = {
            {1L, 1L},
            {7L, 42L},
            {Long.MAX_VALUE, Long.MAX_VALUE},
            {0L, 0L},
            {-1L, -1L},
    };

    @Test
    void should_beCharEqualWithRagPrefix_when_documentPrefix_given_sameIds() {
        // given / when / then 同输入下清理方与写入方的文档级前缀逐字符相等
        for (Long[] pair : ID_PAIRS) {
            String cleanupSide = MediaImageObjectPrefixes.documentPrefix(pair[0], pair[1]);
            String writeSide = MediaImageObjectKeys.prefixOf(pair[0], pair[1]);
            assertEquals(writeSide, cleanupSide,
                    "前缀约定失配：rag 侧 MediaImageObjectKeys.prefixOf(" + pair[0] + ", " + pair[1]
                            + ")=\"" + writeSide + "\"，KB 侧 MediaImageObjectPrefixes.documentPrefix=\""
                            + cleanupSide + "\"");
        }
    }

    @Test
    void should_coverDocumentPrefix_when_knowledgeBasePrefix_given_sameKbId() {
        // given / when / then 整库前缀必然是文档级前缀的前缀：整库清理必然覆盖文档清理范围
        for (Long[] pair : ID_PAIRS) {
            String kbPrefix = MediaImageObjectPrefixes.knowledgeBasePrefix(pair[0]);
            String documentPrefix = MediaImageObjectPrefixes.documentPrefix(pair[0], pair[1]);
            assertTrue(documentPrefix.startsWith(kbPrefix),
                    "整库前缀未包含文档级前缀: kbPrefix=" + kbPrefix + ", documentPrefix=" + documentPrefix);
        }
    }

    @Test
    void should_matchGoldenLiterals_when_prefixes_given_kb7Document42() {
        // given / when / then 字面量黄金值：防两侧被同时改错而互测不出
        assertEquals("rag/7/42/images/", MediaImageObjectPrefixes.documentPrefix(7L, 42L));
        assertEquals("rag/7/42/images/", MediaImageObjectKeys.prefixOf(7L, 42L));
        assertEquals("rag/7/", MediaImageObjectPrefixes.knowledgeBasePrefix(7L));
    }

    @Test
    void should_returnNull_when_documentPrefix_given_nullId() {
        // given / when ID 为空时清理侧前缀返回 null（不抛异常，由清理方 WARN 跳过）
        String byNullKbId = MediaImageObjectPrefixes.documentPrefix(null, 42L);
        String byNullDocumentId = MediaImageObjectPrefixes.documentPrefix(7L, null);
        String kbPrefixByNull = MediaImageObjectPrefixes.knowledgeBasePrefix(null);

        // then
        assertNull(byNullKbId);
        assertNull(byNullDocumentId);
        assertNull(kbPrefixByNull);
    }
}
