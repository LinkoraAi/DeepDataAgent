package com.linkroa.deepdataagent.vault.application.validation;

import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.vault.domain.model.Secret;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretValidatorTest {

    private Secret buildSecret() {
        return Secret.create("s-1", "模型密钥", "encrypted", "default");
    }

    @Test
    void should_throwConflict_when_validateDelete_given_referenced() {
        // given // when
        ResourceConflictException ex = assertThrows(ResourceConflictException.class,
                () -> SecretValidator.validateDelete(buildSecret(), 3L));

        // then
        assertTrue(ex.getMessage().contains("被 3 个模型配置引用"));
    }

    @Test
    void should_notThrow_when_validateDelete_given_noReference() {
        // given // when // then
        assertDoesNotThrow(() -> SecretValidator.validateDelete(buildSecret(), 0L));
    }
}