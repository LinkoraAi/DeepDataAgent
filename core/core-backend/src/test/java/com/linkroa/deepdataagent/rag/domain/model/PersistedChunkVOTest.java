package com.linkroa.deepdataagent.rag.domain.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PersistedChunkVO} 单元测试（分块落库态值对象）。
 * <p>覆盖：合法绑定的构造与 {@code text()} / {@code block()} 代理、纯文本块无来源块时的代理回落、
 * sequence 为 null / 小于 1、chunkId 为 null、chunk 为 null 的参数非法快速失败。</p>
 */
class PersistedChunkVOTest {

    /**
     * 场景：三分量齐备且合法。
     * 预期：构造成功、分量原样承载，{@code text()} / {@code block()} 代理中间态对应字段。
     */
    @Test
    void should_bindRealChunkIdAndProxyChunk_when_construct_given_validInput() {
        // given
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "body", Map.of());
        ChunkVO chunk = new ChunkVO(2, "chunk two", 10, block);

        // when
        PersistedChunkVO persisted = new PersistedChunkVO(2, 1002L, chunk);

        // then
        assertEquals(Integer.valueOf(2), persisted.sequence());
        assertEquals(Long.valueOf(1002L), persisted.chunkId());
        assertSame(chunk, persisted.chunk());
        assertEquals("chunk two", persisted.text());
        assertSame(block, persisted.block());
    }

    /**
     * 场景：来源内容块为 null 的纯文本块。
     * 预期：构造仍成功，{@code block()} 代理为 null，{@code text()} 代理正常。
     */
    @Test
    void should_proxyNullBlock_when_construct_given_chunkWithoutBlock() {
        // given
        PersistedChunkVO persisted = new PersistedChunkVO(1, 1001L, new ChunkVO(1, "text", 5, null));

        // when & then
        assertNull(persisted.block());
        assertEquals("text", persisted.text());
    }

    /**
     * 场景：sequence 为 null。
     * 预期：构造快速失败。
     */
    @Test
    void should_throwIllegalArgumentException_when_construct_given_nullSequence() {
        // given & when & then
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new PersistedChunkVO(null, 1001L, new ChunkVO(1, "text", 5, null)));
        assertTrue(error.getMessage().contains("切片序号"));
    }

    /**
     * 场景：sequence 小于 1（边界非法值 0）。
     * 预期：构造快速失败。
     */
    @Test
    void should_throwIllegalArgumentException_when_construct_given_sequenceBelowOne() {
        // given & when & then
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new PersistedChunkVO(0, 1001L, new ChunkVO(1, "text", 5, null)));
        assertTrue(error.getMessage().contains("切片序号"));
    }

    /**
     * 场景：chunkId 为 null（真实主键缺位即不变量破坏）。
     * 预期：构造快速失败。
     */
    @Test
    void should_throwIllegalArgumentException_when_construct_given_nullChunkId() {
        // given & when & then
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new PersistedChunkVO(1, null, new ChunkVO(1, "text", 5, null)));
        assertTrue(error.getMessage().contains("切片主键"));
    }

    /**
     * 场景：分块中间态为 null。
     * 预期：构造快速失败。
     */
    @Test
    void should_throwIllegalArgumentException_when_construct_given_nullChunk() {
        // given & when & then
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new PersistedChunkVO(1, 1001L, null));
        assertTrue(error.getMessage().contains("切片中间态"));
    }
}
