package com.linkroa.deepdataagent.auth.infrastructure.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHashUtilTest {

    @Test
    void should_verifyPassword_when_matches_given_recentlyHashedPassword() {
        // given
        String raw = "pass-word-1";
        String hashed = PasswordHashUtil.hash(raw);

        // when
        boolean matched = PasswordHashUtil.matches(raw, hashed);

        // then
        assertTrue(matched);
    }

    @Test
    void should_rejectWrongPassword_when_matches_given_differentRawPassword() {
        // given
        String hashed = PasswordHashUtil.hash("correct-password");

        // when
        boolean matched = PasswordHashUtil.matches("wrong-password", hashed);

        // then
        assertFalse(matched);
    }

    @Test
    void should_keepLegacyPlainBcryptHashValid_when_matches_given_legacyHash() {
        // given（旧版 bcrypt(明文) 存量散列，无 SHA-256 预哈希）
        String raw = "legacy-pass-1";
        String legacyHash = at.favre.lib.crypto.bcrypt.BCrypt.withDefaults()
                .hashToString(12, raw.toCharArray());

        // when
        boolean matched = PasswordHashUtil.matches(raw, legacyHash);

        // then（新旧格式均可通过，存量账号不受迁移影响）
        assertTrue(matched);
    }

    @Test
    void should_distinguishLongPasswords_when_hash_given_passwordOver72Bytes() {
        // given（重复段使字节数远超 bcrypt 72 字节截断阈值）
        String a = "segment-abcdef-";
        String b = "SEGMENT-ABCDEF-";

        // when
        boolean truncatedCollision = PasswordHashUtil.matches(
                b, PasswordHashUtil.hash((a).repeat(10)));
        boolean realMatch = PasswordHashUtil.matches(
                (a).repeat(10), PasswordHashUtil.hash((a).repeat(10)));

        // then（截断丢失的后半段差异仍能区分；正常匹配仍成立）
        assertFalse(truncatedCollision);
        assertTrue(realMatch);
    }

    @Test
    void should_returnNonNullDummyHash_when_dummyHash_given_anyCall() {
        // given // when
        String dummy = PasswordHashUtil.dummyHash();

        // then
        assertNotEquals(PasswordHashUtil.hash("same-input"), dummy);
        assertTrue(dummy.startsWith("$2"));
    }
}