package com.linkroa.deepdataagent.runtime.infrastructure.client;

import com.linkroa.deepdataagent.runtime.domain.model.MemoryStoreRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MemoryStoreListTool} 记忆检索占位工具单测（只读清单文本输出）。
 */
class MemoryStoreListToolTest {

    @Test
    void should_listMemoryStores_when_listMemoryStores_given_refs() {
        // given
        MemoryStoreListTool tool = new MemoryStoreListTool(List.of(
                new MemoryStoreRef("mem-1", "短期记忆", "SHORT_TERM"),
                new MemoryStoreRef("mem-2", "长期记忆", "LONG_TERM")));

        // when
        String result = tool.listMemoryStores();

        // then
        assertTrue(result.contains("mem-1 - 短期记忆"));
        assertTrue(result.contains("SHORT_TERM"));
        assertTrue(result.contains("mem-2 - 长期记忆"));
        assertTrue(result.contains("LONG_TERM"));
    }

    @Test
    void should_returnEmptyHint_when_listMemoryStores_given_noRefs() {
        // given
        MemoryStoreListTool tool = new MemoryStoreListTool(List.of());

        // when
        String result = tool.listMemoryStores();

        // then
        assertEquals("当前无可用记忆库", result);
    }
}