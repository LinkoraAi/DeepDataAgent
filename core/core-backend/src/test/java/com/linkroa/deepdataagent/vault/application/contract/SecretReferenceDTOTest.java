package com.linkroa.deepdataagent.vault.application.contract;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecretReferenceDTOTest {

    @Test
    void should_createDto_when_constructed_given_validFields() {
        // given // when
        SecretReferenceDTO dto = new SecretReferenceDTO("sec-1", "plaintext-value");

        // then
        assertEquals("sec-1", dto.secretId());
        assertEquals("plaintext-value", dto.value());
    }

    @Test
    void should_throwException_when_constructed_given_blankSecretId() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new SecretReferenceDTO(" ", "plaintext-value"));
    }

    @Test
    void should_maskPlaintext_when_toString_given_containsSecretValue() {
        // given
        SecretReferenceDTO dto = new SecretReferenceDTO("sec-1", "sk-super-secret-value");

        // when
        String string = dto.toString();

        // then
        assertFalse(string.contains("sk-super-secret-value"));
        assertEquals("SecretReferenceDTO[secretId=sec-1, value=****]", string);
    }
}