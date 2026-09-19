package com.linkroa.deepdataagent.runtime.infrastructure.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HarnessToolNames} 单测（契约名 → Harness 运行时实名映射表，单一事实源）。
 */
class HarnessToolNamesTest {

    @Test
    void should_translateKnownContractNames_when_toRuntimeName_given_mappedTools() {
        // given // when & then（九项内置工具实名映射，Harness 2.0.3）
        assertEquals("execute", HarnessToolNames.toRuntimeName("Bash").orElseThrow());
        assertEquals("read_file", HarnessToolNames.toRuntimeName("Read").orElseThrow());
        assertEquals("write_file", HarnessToolNames.toRuntimeName("Write").orElseThrow());
        assertEquals("edit_file", HarnessToolNames.toRuntimeName("Edit").orElseThrow());
        assertEquals("glob_files", HarnessToolNames.toRuntimeName("Glob").orElseThrow());
        assertEquals("grep_files", HarnessToolNames.toRuntimeName("Grep").orElseThrow());
        assertEquals("deliver_artifact",
                HarnessToolNames.toRuntimeName("DeliverArtifacts").orElseThrow());
        assertEquals("web_fetch", HarnessToolNames.toRuntimeName("WebFetch").orElseThrow());
        assertEquals("web_search", HarnessToolNames.toRuntimeName("WebSearch").orElseThrow());
    }

    @Test
    void should_returnEmpty_when_toRuntimeName_given_unmappedOrNullOrBlankName() {
        // given // when & then（2.0.3 仍无注册实体的契约名 / 任意未知名 / null / 空白均返回空）
        assertTrue(HarnessToolNames.toRuntimeName("ImageGen").isEmpty());
        assertTrue(HarnessToolNames.toRuntimeName("ImageSearch").isEmpty());
        assertTrue(HarnessToolNames.toRuntimeName("mcp_tool_query").isEmpty());
        assertTrue(HarnessToolNames.toRuntimeName(null).isEmpty());
        assertTrue(HarnessToolNames.toRuntimeName(" ").isEmpty());
    }

    @Test
    void should_returnEmpty_when_toRuntimeName_given_caseMismatch() {
        // given // when & then（映射表精确匹配，大小写不符视为未收录，不猜测）
        assertTrue(HarnessToolNames.toRuntimeName("bash").isEmpty());
        assertTrue(HarnessToolNames.toRuntimeName("READ").isEmpty());
    }

    @Test
    void should_preserveOrderAndDeduplicate_when_toRuntimeNames_given_mixedAndRepeatedNames() {
        // given（含未映射名与重复声明的混合名单；WebFetch 2.0.3 起已映射）
        List<String> contractNames = List.of("Read", "WebFetch", "Bash", "Read", "Glob");

        // when
        List<String> runtimeNames = HarnessToolNames.toRuntimeNames(contractNames);

        // then（保序去重，无实体名静默跳过）
        assertEquals(List.of("read_file", "web_fetch", "execute", "glob_files"), runtimeNames);
    }

    @Test
    void should_returnEmptyList_when_toRuntimeNames_given_nullOrEmptyCollection() {
        // given // when & then
        assertEquals(List.of(), HarnessToolNames.toRuntimeNames(null));
        assertEquals(List.of(), HarnessToolNames.toRuntimeNames(List.of()));
    }
}
