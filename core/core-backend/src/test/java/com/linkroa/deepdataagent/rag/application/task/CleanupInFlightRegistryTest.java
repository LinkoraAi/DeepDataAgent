package com.linkroa.deepdataagent.rag.application.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CleanupInFlightRegistry} 删除清退在飞去重登记器单测。
 * <p>覆盖：在飞键构造格式、CAS 登记与重复登记、移除登记、未知键移除的空操作、
 * {@code kb:} / {@code doc:} 前缀的键空间隔离，以及停机收敛清点入口
 * {@link CleanupInFlightRegistry#inFlightDocumentIds()} /
 * {@link CleanupInFlightRegistry#inFlightKnowledgeBaseIds()} 的前缀解析、脏键跳过与不可变快照。</p>
 *
 * <p>本组件为无依赖纯状态账本，按规范无需 Mock，逐方法独立实例保证可重复性。</p>
 *
 * @author DeepDataAgent
 */
class CleanupInFlightRegistryTest {

    /** 测试知识库主键 */
    private static final Long KB_ID = 91L;

    /** 另一测试知识库主键 */
    private static final Long OTHER_KB_ID = 92L;

    /** 与知识库主键数值相同的文档主键（验证键空间隔离） */
    private static final Long DOCUMENT_ID = 91L;

    /** 另一测试文档主键 */
    private static final Long OTHER_DOCUMENT_ID = 93L;

    /** 被测登记器 */
    private CleanupInFlightRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new CleanupInFlightRegistry();
    }

    @Test
    void should_buildPrefixedKey_when_kbKey_given_kbId() {
        // when // then
        assertEquals("kb:91", CleanupInFlightRegistry.kbKey(KB_ID));
    }

    @Test
    void should_buildPrefixedKey_when_docKey_given_documentId() {
        // when // then
        assertEquals("doc:91", CleanupInFlightRegistry.docKey(DOCUMENT_ID));
    }

    @Test
    void should_registerOnce_when_tryRegister_given_freshKey() {
        // given
        String key = CleanupInFlightRegistry.kbKey(KB_ID);

        // when
        boolean first = registry.tryRegister(key);

        // then
        assertTrue(first);
        assertTrue(registry.isInFlight(key));
    }

    @Test
    void should_returnFalse_when_tryRegister_given_keyAlreadyInFlight() {
        // given
        String key = CleanupInFlightRegistry.kbKey(KB_ID);
        assertTrue(registry.tryRegister(key));

        // when
        boolean second = registry.tryRegister(key);

        // then：重复登记被挡下，登记状态不受影响
        assertFalse(second);
        assertTrue(registry.isInFlight(key));
    }

    @Test
    void should_allowReRegister_when_unregister_given_keyInFlight() {
        // given
        String key = CleanupInFlightRegistry.kbKey(KB_ID);
        assertTrue(registry.tryRegister(key));

        // when
        registry.unregister(key);

        // then：登记释放后同一键可再次登记（支撑重删与启动恢复重触发）
        assertFalse(registry.isInFlight(key));
        assertTrue(registry.tryRegister(key));
    }

    @Test
    void should_keepIsolated_when_tryRegister_given_sameIdDifferentResourceType() {
        // given：知识库与文档主键数值相同
        String kbKey = CleanupInFlightRegistry.kbKey(KB_ID);
        String docKey = CleanupInFlightRegistry.docKey(DOCUMENT_ID);

        // when
        assertTrue(registry.tryRegister(kbKey));
        boolean docRegistered = registry.tryRegister(docKey);

        // then：前缀隔离，跨类型不误去重
        assertTrue(docRegistered);
        registry.unregister(kbKey);
        assertFalse(registry.isInFlight(kbKey));
        assertTrue(registry.isInFlight(docKey));
    }

    @Test
    void should_doNothing_when_unregister_given_unknownKey() {
        // given
        String key = CleanupInFlightRegistry.kbKey(KB_ID);

        // when
        registry.unregister(key);

        // then
        assertFalse(registry.isInFlight(key));
    }

    @Test
    void should_returnOnlyDocumentIds_when_inFlightDocumentIds_given_mixedKeys() {
        // given：知识库键与文档键混杂登记，且数值相同者亦不互串
        assertTrue(registry.tryRegister(CleanupInFlightRegistry.kbKey(KB_ID)));
        assertTrue(registry.tryRegister(CleanupInFlightRegistry.docKey(DOCUMENT_ID)));
        assertTrue(registry.tryRegister(CleanupInFlightRegistry.docKey(OTHER_DOCUMENT_ID)));

        // when
        Set<Long> documentIds = registry.inFlightDocumentIds();

        // then：仅 doc: 前缀被解析，同数值的 kb: 键不混入停机清点快照
        assertEquals(Set.of(DOCUMENT_ID, OTHER_DOCUMENT_ID), documentIds);
    }

    @Test
    void should_returnOnlyKnowledgeBaseIds_when_inFlightKnowledgeBaseIds_given_mixedKeys() {
        // given：知识库键与文档键混杂登记
        assertTrue(registry.tryRegister(CleanupInFlightRegistry.docKey(DOCUMENT_ID)));
        assertTrue(registry.tryRegister(CleanupInFlightRegistry.kbKey(KB_ID)));
        assertTrue(registry.tryRegister(CleanupInFlightRegistry.kbKey(OTHER_KB_ID)));

        // when
        Set<Long> knowledgeBaseIds = registry.inFlightKnowledgeBaseIds();

        // then：仅 kb: 前缀被解析，doc: 键不混入
        assertEquals(Set.of(KB_ID, OTHER_KB_ID), knowledgeBaseIds);
    }

    @Test
    void should_returnEmptySets_when_inFlightIds_given_noRegisteredKey() {
        // when // then：无在飞资源时两个清点入口均为空集合（停机收敛零开销跳过）
        assertTrue(registry.inFlightDocumentIds().isEmpty());
        assertTrue(registry.inFlightKnowledgeBaseIds().isEmpty());
    }

    @Test
    void should_returnImmutableSnapshot_when_inFlightDocumentIds_given_registeredKey() {
        // given
        assertTrue(registry.tryRegister(CleanupInFlightRegistry.docKey(DOCUMENT_ID)));

        // when
        Set<Long> snapshot = registry.inFlightDocumentIds();

        // then：快照不可变，调用方无法反向篡改在飞账本
        assertEquals(Set.of(DOCUMENT_ID), snapshot);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(OTHER_DOCUMENT_ID));
    }

    @Test
    void should_excludeReleasedId_when_inFlightKnowledgeBaseIds_given_keyUnregistered() {
        // given：两个知识库清退在飞，其中一个已收尾移除登记
        assertTrue(registry.tryRegister(CleanupInFlightRegistry.kbKey(KB_ID)));
        assertTrue(registry.tryRegister(CleanupInFlightRegistry.kbKey(OTHER_KB_ID)));

        // when
        registry.unregister(CleanupInFlightRegistry.kbKey(KB_ID));

        // then：已收尾的资源不再出现在停机清点快照中
        assertEquals(Set.of(OTHER_KB_ID), registry.inFlightKnowledgeBaseIds());
    }

    @Test
    void should_skipNonNumericSuffix_when_inFlightDocumentIds_given_dirtyKey() {
        // given：前缀匹配但后缀非数字的脏键（理论不可达路径）
        assertTrue(registry.tryRegister("doc:not-a-number"));
        assertTrue(registry.tryRegister(CleanupInFlightRegistry.docKey(DOCUMENT_ID)));

        // when
        Set<Long> documentIds = registry.inFlightDocumentIds();

        // then：脏键留痕跳过，正常键照常清点，不污染其余在飞键的解析
        assertEquals(Set.of(DOCUMENT_ID), documentIds);
    }
}
