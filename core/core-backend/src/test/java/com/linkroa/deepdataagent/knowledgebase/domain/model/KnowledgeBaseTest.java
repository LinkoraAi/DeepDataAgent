package com.linkroa.deepdataagent.knowledgebase.domain.model;

import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.LifecycleStatus;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KnowledgeBase} 知识库聚合根单测：
 * 覆盖创建不变量、名称边界、语言缺省语义与生命周期状态机
 * （ACTIVE → DELETING → DELETE_FAILED，重删清痕推回 DELETING；「已删除」无持久态、由行物理缺失表达）。
 */
class KnowledgeBaseTest {

    /** 固定时间基准，便于断言 updatedAt 刷新 */
    private static final OffsetDateTime PAST = OffsetDateTime.parse("2026-09-01T10:00:00+08:00");

    private static KnowledgeBase kbOf(LifecycleStatus status) {
        return KnowledgeBase.restore(10L, "产品手册", "描述", "Chinese", status,
                null, null, null, null, null, null, null, PAST, PAST);
    }

    @Test
    void should_beActiveWithNullId_when_create_given_validInput() {
        // when
        KnowledgeBase kb = KnowledgeBase.create("产品手册", "描述", "Chinese",
                null, null, null, null, null, null);

        // then：新建默认 ACTIVE，主键待回填
        assertNull(kb.id());
        assertEquals(LifecycleStatus.ACTIVE, kb.lifecycleStatus());
        assertTrue(kb.isActive());
        assertEquals(kb.createdAt(), kb.updatedAt());
    }

    @Test
    void should_defaultLanguageToChinese_when_create_given_nullLanguage() {
        // when：语言入参为 null（创建请求未携带）
        KnowledgeBase kb = KnowledgeBase.create("产品手册", "描述", null,
                null, null, null, null, null, null);

        // then：显式落 Chinese 缺省值
        assertEquals("Chinese", kb.language());
    }

    @Test
    void should_defaultLanguageToChinese_when_create_given_blankLanguage() {
        // when：语言入参为纯空白
        KnowledgeBase kb = KnowledgeBase.create("产品手册", "描述", "   ",
                null, null, null, null, null, null);

        // then：空白视同缺省，落 Chinese
        assertEquals("Chinese", kb.language());
    }

    @Test
    void should_keepRawLanguage_when_create_given_validLanguage() {
        // when：显式携带合法语言全名
        KnowledgeBase kb = KnowledgeBase.create("产品手册", "描述", "English",
                null, null, null, null, null, null);

        // then：原文透传落列（写入侧不做归一）
        assertEquals("English", kb.language());
    }

    @Test
    void should_throwIllegalArgument_when_create_given_blankName() {
        // when // then
        assertThrows(IllegalArgumentException.class, () -> KnowledgeBase.create("   ", "描述", "Chinese",
                null, null, null, null, null, null));
    }

    @Test
    void should_throwIllegalArgument_when_create_given_nameOverMaxLength() {
        // given：129 个字符超出 128 上限
        String tooLong = "知".repeat(129);

        // when // then
        assertThrows(IllegalArgumentException.class, () -> KnowledgeBase.create(tooLong, "描述", "Chinese",
                null, null, null, null, null, null));
    }

    @Test
    void should_acceptNameAtMaxLength_when_create_given_nameExactly128Chars() {
        // given：边界值 128 个字符
        String boundary = "知".repeat(128);

        // when
        KnowledgeBase kb = KnowledgeBase.create(boundary, "描述", "Chinese",
                null, null, null, null, null, null);

        // then
        assertEquals(boundary, kb.name());
    }

    @Test
    void should_passThroughLanguageWithoutNormalize_when_restore_given_storedLanguage() {
        // when：仓储恢复路径原样携带列值（含大小写历史样本），不做任何归一
        KnowledgeBase kb = KnowledgeBase.restore(10L, "产品手册", "描述", "ENGLISH",
                LifecycleStatus.ACTIVE, null, null, null, null, null, null, null, PAST, PAST);

        // then
        assertEquals("ENGLISH", kb.language());
    }

    @Test
    void should_fallbackStatusToActive_when_new_given_nullLifecycleStatus() {
        // when：仓储恢复路径可能携带 null 状态
        KnowledgeBase kb = KnowledgeBase.restore(10L, "产品手册", "描述", "Chinese", null,
                null, null, null, null, null, null, null, PAST, PAST);

        // then：紧凑构造器兜底为 ACTIVE
        assertEquals(LifecycleStatus.ACTIVE, kb.lifecycleStatus());
    }

    @Test
    void should_replaceConfigsAndRefreshUpdatedAt_when_withUpdatedConfig_given_newConfigs() {
        // given
        KnowledgeBase kb = kbOf(LifecycleStatus.ACTIVE);

        // when
        KnowledgeBase updated = kb.withUpdatedConfig("{\"model\":\"e5\"}", null,
                "{\"topK\":5}", null, null, null);

        // then：配置整体替换，名称、状态与语言不受影响，创建时间保持
        assertEquals("{\"model\":\"e5\"}", updated.ragEngineConfig());
        assertEquals("{\"topK\":5}", updated.retrievalStrategy());
        assertEquals("产品手册", updated.name());
        assertEquals("Chinese", updated.language());
        assertEquals(LifecycleStatus.ACTIVE, updated.lifecycleStatus());
        assertEquals(PAST, updated.createdAt());
        assertTrue(updated.updatedAt().isAfter(PAST));
    }

    @Test
    void should_replaceLanguageAndRefreshUpdatedAt_when_withLanguage_given_newLanguage() {
        // given
        KnowledgeBase kb = kbOf(LifecycleStatus.ACTIVE);

        // when
        KnowledgeBase updated = kb.withLanguage("English");

        // then：语言整体替换，其他字段不受影响，创建时间保持
        assertEquals("English", updated.language());
        assertEquals("产品手册", updated.name());
        assertEquals(LifecycleStatus.ACTIVE, updated.lifecycleStatus());
        assertEquals(PAST, updated.createdAt());
        assertTrue(updated.updatedAt().isAfter(PAST));
    }

    @Test
    void should_transitToDeleting_when_markDeleting_given_activeKnowledgeBase() {
        // when
        KnowledgeBase deleting = kbOf(LifecycleStatus.ACTIVE).markDeleting();

        // then
        assertEquals(LifecycleStatus.DELETING, deleting.lifecycleStatus());
        assertFalse(deleting.isActive());
        assertEquals("Chinese", deleting.language());
    }

    @Test
    void should_throwIllegalState_when_markDeleting_given_nonActiveKnowledgeBase() {
        // when // then：删除中不可重复发起；DELETE_FAILED 重删走 clearFailureOnRedelete 而非本方法
        assertThrows(IllegalStateException.class, () -> kbOf(LifecycleStatus.DELETING).markDeleting());
        assertThrows(IllegalStateException.class, () -> kbOf(LifecycleStatus.DELETE_FAILED).markDeleting());
    }

    @Test
    void should_transitToDeleteFailedWithMessage_when_markFailed_given_deletingKnowledgeBase() {
        // when
        KnowledgeBase failed = kbOf(LifecycleStatus.DELETING)
                .markFailed("[KB-CLEANUP] step=chunk_cleanup");

        // then：置 DELETE_FAILED 并携带失败留痕，其余分量不受影响
        assertEquals(LifecycleStatus.DELETE_FAILED, failed.lifecycleStatus());
        assertEquals("[KB-CLEANUP] step=chunk_cleanup", failed.errorMessage());
        assertFalse(failed.isActive());
        assertEquals("Chinese", failed.language());
        assertEquals(PAST, failed.createdAt());
        assertTrue(failed.updatedAt().isAfter(PAST));
    }

    @Test
    void should_keepNullErrorMessage_when_markFailed_given_blankReason() {
        // when：原因为 null（仅置态不落具体文案）
        KnowledgeBase failed = kbOf(LifecycleStatus.DELETING).markFailed(null);

        // then
        assertEquals(LifecycleStatus.DELETE_FAILED, failed.lifecycleStatus());
        assertNull(failed.errorMessage());
    }

    @Test
    void should_throwIllegalState_when_markFailed_given_nonDeletingKnowledgeBase() {
        // when // then：ACTIVE 直接落失败态说明清退流程未走，属于编排错误；重复标记同样拒绝
        assertThrows(IllegalStateException.class,
                () -> kbOf(LifecycleStatus.ACTIVE).markFailed("[KB-CLEANUP] step=x"));
        assertThrows(IllegalStateException.class,
                () -> kbOf(LifecycleStatus.DELETE_FAILED).markFailed("[KB-CLEANUP] step=x"));
    }

    @Test
    void should_transitToDeletingAndClearMessage_when_clearFailureOnRedelete_given_deleteFailedKnowledgeBase() {
        // given：携带失败留痕的 DELETE_FAILED 库
        KnowledgeBase failed = KnowledgeBase.restore(10L, "产品手册", "描述", "Chinese",
                LifecycleStatus.DELETE_FAILED, "[KB-CLEANUP] step=graph_cleanup",
                null, null, null, null, null, null, PAST, PAST);

        // when
        KnowledgeBase redeleting = failed.clearFailureOnRedelete();

        // then：推回 DELETING 且留痕清除
        assertEquals(LifecycleStatus.DELETING, redeleting.lifecycleStatus());
        assertNull(redeleting.errorMessage());
        assertEquals(PAST, redeleting.createdAt());
        assertTrue(redeleting.updatedAt().isAfter(PAST));
    }

    @Test
    void should_throwIllegalState_when_clearFailureOnRedelete_given_nonDeleteFailedKnowledgeBase() {
        // when // then：仅 DELETE_FAILED 可重删清痕，其余源态均属编排错误
        assertThrows(IllegalStateException.class,
                () -> kbOf(LifecycleStatus.ACTIVE).clearFailureOnRedelete());
        assertThrows(IllegalStateException.class,
                () -> kbOf(LifecycleStatus.DELETING).clearFailureOnRedelete());
    }
}
