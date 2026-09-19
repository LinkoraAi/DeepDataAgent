package com.linkroa.deepdataagent.auth.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserTest {

    @Test
    void should_registerUser_when_register_given_validFields() {
        // given // when
        User user = User.register(1L, "user@example.com", "hash");

        // then
        assertEquals(1L, user.userId());
        assertEquals("user@example.com", user.email());
    }

    @Test
    void should_throwException_when_register_given_invalidEmail() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> User.register(1L, "not-an-email", "hash"));
    }

    @Test
    void should_throwException_when_register_given_blankEmail() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> User.register(1L, " ", "hash"));
    }

    @Test
    void should_throwException_when_register_given_blankPasswordHash() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> User.register(1L, "user@example.com", " "));
    }

    @Test
    void should_normalizeEmailToLowercase_when_register_given_mixedCaseEmail() {
        // given
        User user = User.register(1L, "  John@Example.COM ", "hash");

        // when
        String email = user.email();

        // then（trim + 小写归一化，保证同一邮箱不同写法视为同一账号）
        assertEquals("john@example.com", email);
    }
}