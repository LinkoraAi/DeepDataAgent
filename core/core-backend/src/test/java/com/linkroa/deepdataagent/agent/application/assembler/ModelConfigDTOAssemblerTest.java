package com.linkroa.deepdataagent.agent.application.assembler;

import com.linkroa.deepdataagent.agent.application.dto.ModelConfigDTO;
import com.linkroa.deepdataagent.agent.domain.model.ModelConfig;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ModelConfigDTOAssembler 单元测试
 * <p>覆盖 API Key 脱敏、显示名拼接、默认标记、null 处理、时间格式化等所有方法及分支。</p>
 */
class ModelConfigDTOAssemblerTest {

    private ModelConfig buildInfo(Long id, String providerName, String providerDisplayName,
                                     String modelId, String apiUrl, String apiKey, Integer defaultModel,
                                     LocalDateTime createdTime, LocalDateTime updatedTime) {
        ModelConfig info = new ModelConfig();
        info.setId(id);
        info.setProviderName(providerName);
        info.setProviderDisplayName(providerDisplayName);
        info.setModelId(modelId);
        info.setApiUrl(apiUrl);
        info.setApiKey(apiKey);
        info.setDefaultModel(defaultModel);
        info.setCreatedTime(createdTime);
        info.setUpdatedTime(updatedTime);
        return info;
    }

    @Test
    void should_returnNull_when_toDTO_given_nullInfo() {
        // given

        // when
        ModelConfigDTO dto = ModelConfigDTOAssembler.toDTO(null, true);

        // then
        assertNull(dto);
    }

    @Test
    void should_maskApiKey_when_toDTO_given_normalLengthAndMaskTrue() {
        // given
        ModelConfig info = buildInfo(1L, "dashscope", "通义千问", "qwen-plus",
                "https://api.example.com", "sk-1234567890abcdef", 1,
                LocalDateTime.of(2026, 1, 2, 3, 4, 5), LocalDateTime.of(2026, 2, 3, 4, 5, 6));

        // when
        ModelConfigDTO dto = ModelConfigDTOAssembler.toDTO(info, true);

        // then
        assertEquals(1L, dto.id());
        assertEquals("dashscope", dto.providerKey());
        assertEquals("通义千问", dto.providerName());
        assertEquals("qwen-plus", dto.modelKey());
        assertEquals("https://api.example.com", dto.baseUrl());
        assertEquals("sk-1****cdef", dto.apiKeyMasked());
        assertEquals("openai", dto.apiFormat());
        assertTrue(dto.isDefault());
        assertEquals("2026-01-02 03:04:05", dto.createdAt());
        assertEquals("2026-02-03 04:05:06", dto.updatedAt());
    }

    @Test
    void should_keepRawApiKey_when_toDTO_given_maskFalse() {
        // given
        ModelConfig info = buildInfo(2L, "openai", "OpenAI", "gpt-4o",
                "https://api.openai.com", "sk-raw-key-1234567890", 0, null, null);

        // when
        ModelConfigDTO dto = ModelConfigDTOAssembler.toDTO(info, false);

        // then
        assertEquals("sk-raw-key-1234567890", dto.apiKeyMasked());
        assertFalse(dto.isDefault());
        assertNull(dto.createdAt());
        assertNull(dto.updatedAt());
    }

    @Test
    void should_returnEmptyApiKey_when_toDTO_given_nullApiKey() {
        // given
        ModelConfig info = buildInfo(3L, "custom", "自定义", "my-model", "http://localhost", null, 1, null, null);

        // when
        ModelConfigDTO dto = ModelConfigDTOAssembler.toDTO(info, true);

        // then
        assertEquals("", dto.apiKeyMasked());
    }

    @Test
    void should_returnEmptyApiKey_when_toDTO_given_blankApiKey() {
        // given
        ModelConfig info = buildInfo(4L, "custom", "自定义", "my-model", "http://localhost", "   ", 1, null, null);

        // when
        ModelConfigDTO dto = ModelConfigDTOAssembler.toDTO(info, true);

        // then
        assertEquals("", dto.apiKeyMasked());
    }

    @Test
    void should_maskShortApiKey_when_toDTO_given_lengthEqualsFour() {
        // given
        ModelConfig info = buildInfo(5L, "custom", "自定义", "m", "u", "abcd", 1, null, null);

        // when
        ModelConfigDTO dto = ModelConfigDTOAssembler.toDTO(info, true);

        // then
        assertEquals("abcd****", dto.apiKeyMasked());
    }

    @Test
    void should_maskShortApiKey_when_toDTO_given_lengthLessThanFour() {
        // given
        ModelConfig info = buildInfo(6L, "custom", "自定义", "m", "u", "abc", 1, null, null);

        // when
        ModelConfigDTO dto = ModelConfigDTOAssembler.toDTO(info, true);

        // then
        assertEquals("abc****", dto.apiKeyMasked());
    }

    @Test
    void should_maskShortApiKey_when_toDTO_given_lengthEqualsEight() {
        // given
        ModelConfig info = buildInfo(7L, "custom", "自定义", "m", "u", "abcdefgh", 1, null, null);

        // when
        ModelConfigDTO dto = ModelConfigDTOAssembler.toDTO(info, true);

        // then
        assertEquals("abcd****", dto.apiKeyMasked());
    }

    @Test
    void should_flipDefaultFlag_when_toDTO_given_defaultModelNull() {
        // given
        ModelConfig info = buildInfo(8L, "custom", "自定义", "m", "u", "sk-1234567890abc", null, null, null);

        // when
        ModelConfigDTO dto = ModelConfigDTOAssembler.toDTO(info, true);

        // then
        assertFalse(dto.isDefault());
    }

    @Test
    void should_returnNull_when_toDTO_given_nullInfoAndSingleArg() {
        // given

        // when
        ModelConfigDTO dto = ModelConfigDTOAssembler.toDTO(null);

        // then
        assertNull(dto);
    }

    @Test
    void should_maskByDefault_when_toDTO_given_singleArg() {
        // given
        ModelConfig info = buildInfo(9L, "openai", "OpenAI", "gpt-4o", "u", "sk-1234567890abcdef", 0, null, null);

        // when
        ModelConfigDTO dto = ModelConfigDTOAssembler.toDTO(info);

        // then
        assertEquals("sk-1****cdef", dto.apiKeyMasked());
    }

    @Test
    void should_returnEmptyList_when_toDTOList_given_nullList() {
        // given

        // when
        List<ModelConfigDTO> list = ModelConfigDTOAssembler.toDTOList(null);

        // then
        assertTrue(list.isEmpty());
    }

    @Test
    void should_convertAll_when_toDTOList_given_nonNullList() {
        // given
        ModelConfig info1 = buildInfo(1L, "dashscope", "通义千问", "qwen", "u", "sk-1234567890abcdef", 1, null, null);
        ModelConfig info2 = buildInfo(2L, "openai", "OpenAI", "gpt", "u", "sk-abcdefghijklmnop", 0, null, null);

        // when
        List<ModelConfigDTO> list = ModelConfigDTOAssembler.toDTOList(List.of(info1, info2));

        // then
        assertEquals(2, list.size());
        assertEquals("sk-1****cdef", list.get(0).apiKeyMasked());
        assertTrue(list.get(0).isDefault());
        assertEquals("sk-a****mnop", list.get(1).apiKeyMasked());
        assertFalse(list.get(1).isDefault());
    }
}