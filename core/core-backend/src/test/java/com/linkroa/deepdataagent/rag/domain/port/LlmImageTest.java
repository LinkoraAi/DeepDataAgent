package com.linkroa.deepdataagent.rag.domain.port;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link LlmImage} 单元测试。
 * <p>校验值对象不变量：contentType 非空白（不强校验 {@code image/*} 前缀，MIME 白名单属
 * API 消费侧职责）、content 非空数组，以及字节防御性拷贝（外部修改原数组不影响已构造值）。</p>
 *
 * @author DeepDataAgent
 */
class LlmImageTest {

    /** 测试 MIME 类型 */
    private static final String CONTENT_TYPE = "image/jpeg";

    /** 测试图片字节 */
    private static final byte[] BYTES = new byte[]{1, 2, 3, 4};

    /**
     * 场景：合法 MIME 类型与非空字节数组。
     * 预期：两个分量原样保留。
     */
    @Test
    void should_keepComponents_when_new_given_validInput() {
        // given / when
        LlmImage image = new LlmImage(CONTENT_TYPE, BYTES);

        // then
        assertEquals(CONTENT_TYPE, image.contentType());
        assertArrayEquals(BYTES, image.content());
    }

    /**
     * 场景：非 {@code image/*} 的 MIME 类型。
     * 预期：值对象不做前缀强校验（白名单核验在 API 消费侧），构造成功。
     */
    @Test
    void should_accept_when_new_given_nonImageMimeType() {
        // given / when
        LlmImage image = new LlmImage("application/octet-stream", BYTES);

        // then
        assertEquals("application/octet-stream", image.contentType());
    }

    /**
     * 场景：contentType 为空白。
     * 预期：抛 {@link IllegalArgumentException}。
     */
    @Test
    void should_throwIllegalArgument_when_new_given_blankContentType() {
        // given / when / then
        assertThrows(IllegalArgumentException.class, () -> new LlmImage("  ", BYTES));
    }

    /**
     * 场景：content 为空数组。
     * 预期：抛 {@link IllegalArgumentException}（零字节图片无意义且会污染缓存键语义）。
     */
    @Test
    void should_throwIllegalArgument_when_new_given_emptyContent() {
        // given / when / then
        assertThrows(IllegalArgumentException.class, () -> new LlmImage(CONTENT_TYPE, new byte[0]));
    }

    /**
     * 场景：content 为 null。
     * 预期：抛 {@link IllegalArgumentException}。
     */
    @Test
    void should_throwIllegalArgument_when_new_given_nullContent() {
        // given / when / then
        assertThrows(IllegalArgumentException.class, () -> new LlmImage(CONTENT_TYPE, null));
    }

    /**
     * 场景：构造完成后修改调用方原数组。
     * 预期：值对象内的字节不受影响（防御性拷贝，保证缓存键摘要稳定）。
     */
    @Test
    void should_copyBytes_when_new_given_externalArray() {
        // given
        byte[] mutable = new byte[]{9, 9, 9};

        // when
        LlmImage image = new LlmImage(CONTENT_TYPE, mutable);
        mutable[0] = 0;

        // then
        assertArrayEquals(new byte[]{9, 9, 9}, image.content(), "图片字节必须做防御性拷贝");
    }
}
