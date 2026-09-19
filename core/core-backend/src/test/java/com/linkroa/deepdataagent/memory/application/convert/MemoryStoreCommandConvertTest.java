package com.linkroa.deepdataagent.memory.application.convert;

import com.linkroa.deepdataagent.memory.application.command.CreateMemoryEntryCommand;
import com.linkroa.deepdataagent.memory.application.command.CreateMemoryStoreCommand;
import com.linkroa.deepdataagent.memory.application.command.UpdateMemoryEntryCommand;
import com.linkroa.deepdataagent.memory.controller.request.CreateMemoryEntryRequest;
import com.linkroa.deepdataagent.memory.controller.request.CreateMemoryStoreRequest;
import com.linkroa.deepdataagent.memory.controller.request.UpdateMemoryEntryRequest;
import com.linkroa.deepdataagent.memory.domain.model.MemoryMetadata;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link MemoryStoreCommandConvert} 请求转换单测。
 * <p>覆盖库创建、条目创建 / 更新命令装配（路径参数注入、OCC 版本改名映射）与元数据 VO 转换。</p>
 */
class MemoryStoreCommandConvertTest {

    @Test
    void should_mapCreateCommand_when_toCreateCommand_given_validRequest() {
        // given
        CreateMemoryStoreRequest request = new CreateMemoryStoreRequest("长期记忆", "长期会话记忆库");

        // when
        CreateMemoryStoreCommand command = MemoryStoreCommandConvert.INSTANCE.toCreateCommand(request);

        // then
        assertEquals("长期记忆", command.name());
        assertEquals("长期会话记忆库", command.description());
    }

    @Test
    void should_mapNullableDescription_when_toCreateCommand_given_noDescription() {
        // given
        CreateMemoryStoreRequest request = new CreateMemoryStoreRequest("会话记忆", null);

        // when
        CreateMemoryStoreCommand command = MemoryStoreCommandConvert.INSTANCE.toCreateCommand(request);

        // then
        assertEquals("会话记忆", command.name());
        assertNull(command.description());
    }

    @Test
    void should_injectStoreIdAndMapMetadata_when_toCreateEntryCommand_given_requestWithMetadata() {
        // given（路径参数 storeId 注入命令，元数据 Map 转领域 VO）
        CreateMemoryEntryRequest request = new CreateMemoryEntryRequest(
                "docs/README.md", "内容", Map.of("category", "guide"));

        // when
        CreateMemoryEntryCommand command =
                MemoryStoreCommandConvert.INSTANCE.toCreateEntryCommand("ms_1", request);

        // then
        assertEquals("ms_1", command.storeId());
        assertEquals("docs/README.md", command.path());
        assertEquals("内容", command.content());
        assertEquals("guide", command.metadata().entries().get("category"));
    }

    @Test
    void should_keepNullMetadata_when_toCreateEntryCommand_given_noMetadata() {
        // given（null=不设置，归一交给领域层）
        CreateMemoryEntryRequest request = new CreateMemoryEntryRequest("docs/a.md", "内容", null);

        // when
        CreateMemoryEntryCommand command =
                MemoryStoreCommandConvert.INSTANCE.toCreateEntryCommand("ms_1", request);

        // then
        assertNull(command.metadata());
    }

    @Test
    void should_mapExpectedVersionAndIds_when_toUpdateEntryCommand_given_request() {
        // given（请求 version 映射为命令 expectedVersion，双路径参数注入）
        UpdateMemoryEntryRequest request =
                new UpdateMemoryEntryRequest("新内容", 3, Map.of("k", "v"));

        // when
        UpdateMemoryEntryCommand command = MemoryStoreCommandConvert.INSTANCE
                .toUpdateEntryCommand("ms_1", "mem_9", request);

        // then
        assertEquals("ms_1", command.storeId());
        assertEquals("mem_9", command.memoryId());
        assertEquals("新内容", command.content());
        assertEquals(3, command.expectedVersion());
        assertEquals("v", command.metadata().entries().get("k"));
    }

    @Test
    void should_keepNullMetadata_when_toUpdateEntryCommand_given_noMetadata() {
        // given（null 元数据表示沿用现有）
        UpdateMemoryEntryRequest request = new UpdateMemoryEntryRequest("新内容", 1, null);

        // when
        UpdateMemoryEntryCommand command = MemoryStoreCommandConvert.INSTANCE
                .toUpdateEntryCommand("ms_1", "mem_9", request);

        // then
        assertNull(command.metadata());
    }

    @Test
    void should_wrapMapIntoVo_when_toMetadata_given_map() {
        // when
        MemoryMetadata metadata = MemoryStoreCommandConvert.INSTANCE.toMetadata(Map.of("a", "1"));

        // then
        assertEquals("1", metadata.entries().get("a"));
    }

    @Test
    void should_returnNull_when_toMetadata_given_null() {
        // when / then（null 不转空 VO，保留「未设置」语义）
        assertNull(MemoryStoreCommandConvert.INSTANCE.toMetadata(null));
    }
}
