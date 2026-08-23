package com.linkroa.deepdataagent.vault.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecretTest {

    @Test
    void should_createSecret_when_create_given_validFields() {
        // given // when
        Secret secret = Secret.create("sec-1", "模型凭证", "encrypted-value", "ws-default");

        // then
        assertEquals("sec-1", secret.secretId());
        assertEquals("模型凭证", secret.name());
        assertEquals("encrypted-value", secret.encryptedValue());
        assertEquals("ws-default", secret.workspaceId());
    }

    @Test
    void should_throwException_when_create_given_blankName() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> Secret.create("sec-1", " ", "encrypted-value", "ws-default"));
    }

    @Test
    void should_throwException_when_create_given_nameExceeds255Chars() {
        // given
        String longName = "密".repeat(300);

        // when // then
        assertThrows(IllegalArgumentException.class,
                () -> Secret.create("sec-1", longName, "encrypted-value", "ws-default"));
    }

    @Test
    void should_throwException_when_create_given_invalidNamePattern() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> Secret.create("sec-1", "1abc!", "encrypted-value", "ws-default"));
    }

    @Test
    void should_throwException_when_create_given_blankEncryptedValue() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> Secret.create("sec-1", "模型凭证", " ", "ws-default"));
    }
}