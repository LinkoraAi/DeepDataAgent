package com.linkroa.deepdataagent.agent.application.assembler;

import com.linkroa.deepdataagent.agent.application.command.CreateModelProfileCommand;
import com.linkroa.deepdataagent.agent.application.command.UpdateModelProfileCommand;
import com.linkroa.deepdataagent.agent.controller.request.CreateModelProfileRequest;
import com.linkroa.deepdataagent.agent.controller.request.UpdateModelProfileRequest;
import com.linkroa.deepdataagent.agent.domain.model.enums.ApiFormat;
import com.linkroa.deepdataagent.agent.domain.model.enums.ModelType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 模型配置请求装配器测试
 */
class ModelProfileCommandAssemblerTest {

    private final ModelProfileCommandAssembler assembler = new ModelProfileCommandAssembler();

    @Test
    void should_parseEnumsAndKeepFields_when_toCreateCommand_given_fullRequest() {
        // given
        CreateModelProfileRequest request = new CreateModelProfileRequest(
                "通义千问", "qwen 系列", "OPENAI", "https://api.example.com/v1", "gpt-4", "sk-1",
                "qwen", 8192, 4096, 5, 1, 1536);

        // when
        CreateModelProfileCommand command = assembler.toCreateCommand(request);

        // then
        assertEquals("通义千问", command.displayName());
        assertEquals(ApiFormat.OPENAI, command.apiFormat());
        assertEquals("https://api.example.com/v1", command.apiEndpointUrl());
        assertEquals("sk-1", command.credential());
        assertEquals(5, command.toolCallRounds());
        assertEquals(ModelType.CHAT, command.modelType());
        assertEquals(1536, command.vectorDimension());
    }

    @Test
    void should_useMaximumToolCallRounds_when_toCreateCommand_given_nullToolCallRounds() {
        // given
        CreateModelProfileRequest request = new CreateModelProfileRequest(
                "通义千问", null, "OPENAI", "https://api.example.com/v1", "gpt-4", null,
                null, null, null, null, 1, null);

        // when
        CreateModelProfileCommand command = assembler.toCreateCommand(request);

        // then
        assertEquals(999999, command.toolCallRounds());
    }

    @Test
    void should_keepNullApiFormat_when_toCreateCommand_given_blankApiFormat() {
        // given
        CreateModelProfileRequest request = new CreateModelProfileRequest(
                "通义千问", null, "  ", "https://api.example.com/v1", "gpt-4", null,
                null, null, null, null, null, null);

        // when
        CreateModelProfileCommand command = assembler.toCreateCommand(request);

        // then
        assertNull(command.apiFormat());
        assertEquals(ModelType.CHAT, command.modelType());
    }

    @Test
    void should_parseEmbeddingType_when_toUpdateCommand_given_typeCodeAndProfileId() {
        // given
        UpdateModelProfileRequest request = new UpdateModelProfileRequest(
                "嵌入模型", null, "AGENTSCOPE", "https://api.example.com/v1", "text-embedding", "",
                null, null, null, null, 2, 256);

        // when
        UpdateModelProfileCommand command = assembler.toUpdateCommand("mp-9", request);

        // then
        assertEquals("mp-9", command.profileId());
        assertEquals(ApiFormat.AGENTSCOPE, command.apiFormat());
        assertEquals(ModelType.EMBEDDING, command.modelType());
        assertEquals("", command.credential());
        assertEquals(256, command.vectorDimension());
    }
}