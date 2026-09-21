package com.linkroa.deepdataagent.rag.domain.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link MediaImageObjectKeys} 单元测试：验证对象键前缀形态、完整键拼接，
 * 以及图片名净化的路径穿越拒绝与空值语义（净化失败返回 null 交由调用方跳过该图）。
 */
class MediaImageObjectKeysTest {

    /** 测试知识库主键 */
    private static final Long KB_ID = 7L;

    /** 测试文档主键 */
    private static final Long DOCUMENT_ID = 42L;

    /** 期望的媒体图片前缀 */
    private static final String EXPECTED_PREFIX = "rag/7/42/images/";

    @Test
    void should_returnKbDocumentImagesPrefix_when_prefixOf_given_kbIdAndDocumentId() {
        // given / when
        String prefix = MediaImageObjectKeys.prefixOf(KB_ID, DOCUMENT_ID);

        // then：以分隔符结尾，可直接拼接文件名；删除链（组12）按同一前缀批量清理
        assertEquals(EXPECTED_PREFIX, prefix);
    }

    @Test
    void should_throwException_when_prefixOf_given_nullId() {
        // given / when / then：任一 ID 缺失都无法定位归属，直接拒绝
        assertThrows(IllegalArgumentException.class, () -> MediaImageObjectKeys.prefixOf(null, DOCUMENT_ID));
        assertThrows(IllegalArgumentException.class, () -> MediaImageObjectKeys.prefixOf(KB_ID, null));
    }

    @Test
    void should_returnPrefixedKey_when_keyOf_given_plainImageName() {
        // given / when
        String objectKey = MediaImageObjectKeys.keyOf(KB_ID, DOCUMENT_ID, "img_a.jpeg");

        // then
        assertEquals(EXPECTED_PREFIX + "img_a.jpeg", objectKey);
    }

    @Test
    void should_returnPrefixedKey_when_keyOf_given_relativeDirectoryImagePath() {
        // given：MinerU 的 img_name 可能带相对目录（images/xxx.png）
        // when
        String objectKey = MediaImageObjectKeys.keyOf(KB_ID, DOCUMENT_ID, "images/img_a.png");

        // then：目录段被剥离，只保留基名
        assertEquals(EXPECTED_PREFIX + "img_a.png", objectKey);
    }

    @Test
    void should_returnNull_when_keyOf_given_pathTraversalImageName() {
        // given / when / then：含 ".." 的输入一律拒绝，键为 null（调用方跳过该图）
        assertNull(MediaImageObjectKeys.keyOf(KB_ID, DOCUMENT_ID, "../x.png"));
        assertNull(MediaImageObjectKeys.keyOf(KB_ID, DOCUMENT_ID, "a/../../b.png"));
    }

    @Test
    void should_returnBaseName_when_sanitize_given_pathSegmentName() {
        // given / when / then：正斜杠与反斜杠路径段均被剥离
        assertEquals("x.png", MediaImageObjectKeys.sanitize("images/x.png"));
        assertEquals("x.png", MediaImageObjectKeys.sanitize("images\\sub\\x.png"));
        assertEquals("x.png", MediaImageObjectKeys.sanitize("/x.png"));
    }

    @Test
    void should_returnNull_when_sanitize_given_traversalOrBlankName() {
        // given / when / then：穿越、空白、以分隔符结尾（基名为空）均返回 null
        assertNull(MediaImageObjectKeys.sanitize("../x.png"));
        assertNull(MediaImageObjectKeys.sanitize("..\\x.png"));
        assertNull(MediaImageObjectKeys.sanitize("a/../../b.png"));
        assertNull(MediaImageObjectKeys.sanitize(null));
        assertNull(MediaImageObjectKeys.sanitize("   "));
        assertNull(MediaImageObjectKeys.sanitize("images/"));
    }
}
