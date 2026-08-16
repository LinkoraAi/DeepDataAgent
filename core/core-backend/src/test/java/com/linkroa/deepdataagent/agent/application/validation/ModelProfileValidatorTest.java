package com.linkroa.deepdataagent.agent.application.validation;

import com.linkroa.deepdataagent.agent.domain.model.ModelProfile;
import com.linkroa.deepdataagent.agent.domain.model.enums.ApiFormat;
import com.linkroa.deepdataagent.agent.domain.model.enums.ModelProfileStatus;
import com.linkroa.deepdataagent.agent.domain.model.enums.ModelType;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelProfileValidatorTest {

    private ModelProfile buildProfile(ModelProfileStatus status) {
        return ModelProfile.restore(
                "p1", "chat-profile", null, ApiFormat.OPENAI, "https://example.com/v1", "gpt-4",
                "encrypted", "gpt", 8192, 2048, 10, ModelType.CHAT, null,
                status, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), null, null);
    }

    @Test
    void should_throwConflict_when_validateDelete_given_referenced() {
        // given // when
        ResourceConflictException ex = assertThrows(ResourceConflictException.class,
                () -> ModelProfileValidator.validateDelete(buildProfile(ModelProfileStatus.ENABLED), 3L));

        // then
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("被 3 个"));
    }

    @Test
    void should_notThrow_when_validateDelete_given_noReference() {
        // given // when // then
        assertDoesNotThrow(() -> ModelProfileValidator.validateDelete(buildProfile(ModelProfileStatus.ENABLED), 0L));
    }

    @Test
    void should_throwNotFound_when_validateReferable_given_nullProfile() {
        // given // when // then
        assertThrows(ResourceNotFoundException.class, () -> ModelProfileValidator.validateReferable(null));
    }

    @Test
    void should_throwNotFound_when_validateReferable_given_disabledProfile() {
        // given // when // then
        assertThrows(ResourceNotFoundException.class,
                () -> ModelProfileValidator.validateReferable(buildProfile(ModelProfileStatus.DISABLED)));
    }

    @Test
    void should_notThrow_when_validateReferable_given_enabledProfile() {
        // given // when // then
        assertDoesNotThrow(() -> ModelProfileValidator.validateReferable(buildProfile(ModelProfileStatus.ENABLED)));
    }
}