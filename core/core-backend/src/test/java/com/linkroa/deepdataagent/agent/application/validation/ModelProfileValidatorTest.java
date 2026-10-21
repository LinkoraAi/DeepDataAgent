package com.linkroa.deepdataagent.agent.application.validation;

import com.linkroa.deepdataagent.agent.domain.model.ModelProfile;
import com.linkroa.deepdataagent.agent.domain.model.enums.ApiFormat;
import com.linkroa.deepdataagent.agent.domain.model.enums.ModelProfileStatus;
import com.linkroa.deepdataagent.agent.domain.model.enums.ModelType;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModelProfileValidator} 单元测试：删除守卫、引用合法性（存在/状态/类型）、类型变更守卫。
 */
class ModelProfileValidatorTest {

    private static final Set<ModelType> AGENT_TYPES = ModelProfileValidator.AGENT_MODEL_TYPES;

    private ModelProfile buildProfile(ModelProfileStatus status, ModelType modelType) {
        return ModelProfile.restore(
                "p1", "chat-profile", null, ApiFormat.OPENAI, "https://example.com/v1", "gpt-4",
                "encrypted", "gpt", 8192, 2048, 10, modelType, null,
                status, 1L, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), null, null);
    }

    private ModelProfile enabledProfile(ModelType modelType) {
        return buildProfile(ModelProfileStatus.ENABLED, modelType);
    }

    @Test
    void should_throwConflict_when_validateDelete_given_referenced() {
        // given // when
        ResourceConflictException ex = assertThrows(ResourceConflictException.class,
                () -> ModelProfileValidator.validateDelete(enabledProfile(ModelType.CHAT_LLM), 3L));

        // then
        assertTrue(ex.getMessage().contains("被 3 个"));
    }

    @Test
    void should_notThrow_when_validateDelete_given_noReference() {
        // given // when // then
        assertDoesNotThrow(() -> ModelProfileValidator.validateDelete(enabledProfile(ModelType.CHAT_LLM), 0L));
    }

    @Test
    void should_throwNotFound_when_validateReferable_given_nullProfile() {
        // given // when // then
        assertThrows(ResourceNotFoundException.class,
                () -> ModelProfileValidator.validateReferable(null, AGENT_TYPES));
    }

    @Test
    void should_throwNotFound_when_validateReferable_given_disabledProfile() {
        // given
        ModelProfile disabled = buildProfile(ModelProfileStatus.DISABLED, ModelType.CHAT_LLM);

        // when // then
        assertThrows(ResourceNotFoundException.class,
                () -> ModelProfileValidator.validateReferable(disabled, AGENT_TYPES));
    }

    @Test
    void should_notThrow_when_validateReferable_given_enabledChatLlmProfile() {
        // given // when // then
        assertDoesNotThrow(() -> ModelProfileValidator.validateReferable(
                enabledProfile(ModelType.CHAT_LLM), AGENT_TYPES));
    }

    @Test
    void should_notThrow_when_validateReferable_given_enabledChatVlmProfileInAgentSlot() {
        // given（智能体槽位接受视觉对话模型）
        ModelProfile vlm = enabledProfile(ModelType.CHAT_VLM);

        // when // then
        assertDoesNotThrow(() -> ModelProfileValidator.validateReferable(vlm, AGENT_TYPES));
    }

    @Test
    void should_throwBadRequest_when_validateReferable_given_embeddingInAgentSlot() {
        // given（嵌入类型需向量维度才满足领域不变量）
        ModelProfile embedding = ModelProfile.restore(
                "p2", "embed-profile", null, ApiFormat.OPENAI, "https://example.com/v1", "bge-m3",
                null, null, 0, 0, 1, ModelType.EMBEDDING, 1024,
                ModelProfileStatus.ENABLED, 1L, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), null, null);

        // when
        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class,
                () -> ModelProfileValidator.validateReferable(embedding, AGENT_TYPES));

        // then
        assertTrue(ex.getMessage().contains("嵌入"));
        assertTrue(ex.getMessage().contains("视觉对话"));
    }

    @Test
    void should_notThrow_when_validateType_given_emptyExpectedTypes() {
        // given
        ModelProfile rerank = enabledProfile(ModelType.RERANK);

        // when // then
        assertDoesNotThrow(() -> ModelProfileValidator.validateType(rerank, Set.of()));
    }

    @Test
    void should_throwBadRequest_when_validateType_given_mismatchedType() {
        // given
        ModelProfile rerank = enabledProfile(ModelType.RERANK);

        // when
        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class,
                () -> ModelProfileValidator.validateType(rerank, Set.of(ModelType.CHAT_VLM)));

        // then
        assertTrue(ex.getMessage().contains("重排(4)"));
        assertTrue(ex.getMessage().contains("视觉对话(2)"));
    }

    @Test
    void should_notThrow_when_validateTypeChange_given_sameTypeWithReferences() {
        // given
        ModelProfile existing = enabledProfile(ModelType.CHAT_LLM);

        // when // then
        assertDoesNotThrow(() -> ModelProfileValidator.validateTypeChange(existing, ModelType.CHAT_LLM, 5L));
    }

    @Test
    void should_throwConflict_when_validateTypeChange_given_changedTypeWithReferences() {
        // given
        ModelProfile existing = enabledProfile(ModelType.CHAT_LLM);

        // when
        ResourceConflictException ex = assertThrows(ResourceConflictException.class,
                () -> ModelProfileValidator.validateTypeChange(existing, ModelType.RERANK, 2L));

        // then
        assertTrue(ex.getMessage().contains("被 2 个"));
        assertTrue(ex.getMessage().contains("不可变更模型类型"));
    }

    @Test
    void should_notThrow_when_validateTypeChange_given_changedTypeWithoutReferences() {
        // given
        ModelProfile existing = enabledProfile(ModelType.CHAT_LLM);

        // when // then
        assertDoesNotThrow(() -> ModelProfileValidator.validateTypeChange(existing, ModelType.RERANK, 0L));
    }

    @Test
    void should_containAllAiModelTypes_when_readAgentModelTypes() {
        // given // when // then（智能体槽位口径：对话LLM + 视觉对话）
        assertEquals(Set.of(ModelType.CHAT_LLM, ModelType.CHAT_VLM), AGENT_TYPES);
    }
}
