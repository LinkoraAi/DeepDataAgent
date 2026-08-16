package com.linkroa.deepdataagent.agent.infrastructure.util;

import com.linkroa.deepdataagent.agent.infrastructure.config.ModelEncryptionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelCredentialEncryptionUtilTest {

    private ModelCredentialEncryptionUtil encryptionUtil;

    @BeforeEach
    void setUp() {
        ModelEncryptionProperties properties = new ModelEncryptionProperties("model-secret-test-key");
        encryptionUtil = new ModelCredentialEncryptionUtil(properties);
    }

    @Test
    void should_returnEncryptedText_when_encrypt_given_plainCredential() {
        // given
        String plainCredential = "sk-test-123456";

        // when
        String encrypted = encryptionUtil.encrypt(plainCredential);

        // then
        assertNotNull(encrypted);
        assertNotEquals(plainCredential, encrypted);
        assertTrue(encrypted.length() > plainCredential.length());
    }

    @Test
    void should_returnOriginalCredential_when_decrypt_given_encryptedCredential() {
        // given
        String plainCredential = "sk-test-123456";
        String encrypted = encryptionUtil.encrypt(plainCredential);

        // when
        String decrypted = encryptionUtil.decrypt(encrypted);

        // then
        assertEquals(plainCredential, decrypted);
    }

    @Test
    void should_returnEmptyInputAsIs_when_encrypt_given_blankCredential() {
        // given
        String empty = "";

        // when
        String encrypted = encryptionUtil.encrypt(empty);

        // then
        assertEquals(empty, encrypted);
    }

    @Test
    void should_returnNullAsIs_when_encrypt_given_nullCredential() {
        // when
        String encrypted = encryptionUtil.encrypt(null);

        // then
        assertNull(encrypted);
    }

    @Test
    void should_generateDifferentCiphers_when_encrypt_given_samePlaintextTwice() {
        // given
        String plainCredential = "sk-test-123456";

        // when
        String encrypted1 = encryptionUtil.encrypt(plainCredential);
        String encrypted2 = encryptionUtil.encrypt(plainCredential);

        // then
        assertNotEquals(encrypted1, encrypted2);
        assertEquals(plainCredential, encryptionUtil.decrypt(encrypted1));
        assertEquals(plainCredential, encryptionUtil.decrypt(encrypted2));
    }

    @Test
    void should_returnPlaintextAsIs_when_decrypt_given_nonEncryptedText() {
        // given
        String plaintext = "not-an-encrypted-credential";

        // when
        String decrypted = encryptionUtil.decrypt(plaintext);

        // then
        assertEquals(plaintext, decrypted);
    }

    @Test
    void should_throwException_when_encrypt_given_missingKey() {
        // given
        ModelEncryptionProperties emptyProperties = new ModelEncryptionProperties("");
        ModelCredentialEncryptionUtil emptyKeyUtil = new ModelCredentialEncryptionUtil(emptyProperties);

        // when / then
        assertThrows(IllegalStateException.class, () -> emptyKeyUtil.encrypt("sk-test-123456"));
    }

    @Test
    void should_returnFalse_when_isEncrypted_given_shortOrPlainText() {
        // given
        String shortText = "abc";
        String plainText = "plain-credential-no-base64!";

        // when
        boolean shortResult = encryptionUtil.isEncrypted(shortText);
        boolean plainResult = encryptionUtil.isEncrypted(plainText);

        // then
        assertFalse(shortResult);
        assertFalse(plainResult);
    }
}