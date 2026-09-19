package com.linkroa.deepdataagent.auth.infrastructure.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthRateLimiterTest {

    private final AuthRateLimiter rateLimiter = new AuthRateLimiter();

    @Test
    void should_allowWithinLimit_when_tryAcquire_given_limitThree() {
        // given // when
        boolean first = rateLimiter.tryAcquire("login:ip:1.2.3.4", 3);
        boolean second = rateLimiter.tryAcquire("login:ip:1.2.3.4", 3);
        boolean third = rateLimiter.tryAcquire("login:ip:1.2.3.4", 3);

        // then
        assertTrue(first);
        assertTrue(second);
        assertTrue(third);
    }

    @Test
    void should_rejectOverLimit_when_tryAcquire_given_fourthCallWithLimitThree() {
        // given
        for (int i = 0; i < 3; i++) {
            rateLimiter.tryAcquire("login:ip:1.2.3.4", 3);
        }

        // when
        boolean fourth = rateLimiter.tryAcquire("login:ip:1.2.3.4", 3);

        // then
        assertFalse(fourth);
    }

    @Test
    void should_trackKeysIsolation_when_tryAcquire_given_differentKeys() {
        // given
        for (int i = 0; i < 3; i++) {
            rateLimiter.tryAcquire("login:ip:1.2.3.4", 3);
        }

        // when
        boolean otherKeyCall = rateLimiter.tryAcquire("login:email:other@example.com", 3);

        // then（不同 key 互不影响）
        assertTrue(otherKeyCall);
    }
}