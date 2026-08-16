package com.linkroa.deepdataagent.agent.domain.model;

import com.linkroa.deepdataagent.agent.domain.model.enums.ApiFormat;
import com.linkroa.deepdataagent.agent.domain.model.enums.ModelType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelProfileTest {

    @Test
    void should_throwException_when_createEmbeddingProfile_given_missingVectorDimension() {
        // given
        // EMBEDDING 类型未提供 vectorDimension

        // when / then
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ModelProfile.create(
                        "p1", "embedding-profile", null, ApiFormat.OPENAI,
                        "https://example.com/v1", "text-embedding-3", null,
                        null, null, null, 10, ModelType.EMBEDDING, null));

        assertEquals("向量嵌入模型必须配置向量维度(vectorDimension)", ex.getMessage());
    }

    @Test
    void should_createProfile_when_createEmbeddingProfile_given_vectorDimensionProvided() {
        // given
        // EMBEDDING 类型提供 vectorDimension

        // when
        ModelProfile profile = ModelProfile.create(
                "p1", "embedding-profile", null, ApiFormat.OPENAI,
                "https://example.com/v1", "text-embedding-3", null,
                null, null, null, 10, ModelType.EMBEDDING, 1536);

        // then
        assertEquals("p1", profile.profileId());
        assertEquals(1536, profile.vectorDimension());
    }

    @Test
    void should_throwException_when_createProfile_given_blankName() {
        // given
        // 名称为空

        // when / then
        assertThrows(IllegalArgumentException.class,
                () -> ModelProfile.create(
                        "p1", "", null, ApiFormat.OPENAI,
                        "https://example.com/v1", "gpt-4", null,
                        null, null, null, 10, ModelType.CHAT, null));
    }

    @Test
    void should_throwException_when_createProfile_given_nameExceeds32Chars() {
        // given
        String longName = "a".repeat(33);

        // when / then
        assertThrows(IllegalArgumentException.class,
                () -> ModelProfile.create(
                        "p1", longName, null, ApiFormat.OPENAI,
                        "https://example.com/v1", "gpt-4", null,
                        null, null, null, 10, ModelType.CHAT, null));
    }

    @Test
    void should_throwException_when_createProfile_given_nullApiFormat() {
        // given
        // apiFormat 为空

        // when / then
        assertThrows(IllegalArgumentException.class,
                () -> ModelProfile.create(
                        "p1", "chat-profile", null, null,
                        "https://example.com/v1", "gpt-4", null,
                        null, null, null, 10, ModelType.CHAT, null));
    }

    @Test
    void should_throwException_when_createProfile_given_blankEndpoint() {
        // given
        // endpoint 为空

        // when / then
        assertThrows(IllegalArgumentException.class,
                () -> ModelProfile.create(
                        "p1", "chat-profile", null, ApiFormat.OPENAI,
                        "", "gpt-4", null,
                        null, null, null, 10, ModelType.CHAT, null));
    }

    @Test
    void should_throwException_when_createProfile_given_blankModelName() {
        // given
        // modelName 为空

        // when / then
        assertThrows(IllegalArgumentException.class,
                () -> ModelProfile.create(
                        "p1", "chat-profile", null, ApiFormat.OPENAI,
                        "https://example.com/v1", "", null,
                        null, null, null, 10, ModelType.CHAT, null));
    }

    @Test
    void should_createProfile_when_createChatProfile_given_validFields() {
        // given
        // 合法 CHAT 配置

        // when
        ModelProfile profile = ModelProfile.create(
                "p1", "chat-profile", "desc", ApiFormat.OPENAI,
                "https://example.com/v1", "gpt-4", null,
                "gpt", 8192, 2048, 10, ModelType.CHAT, null);

        // then
        assertEquals("chat-profile", profile.displayName());
        assertEquals(ModelType.CHAT, profile.modelType());
    }
}