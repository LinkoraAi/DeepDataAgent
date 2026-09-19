package com.linkroa.deepdataagent.file.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link FileScope} 值对象不变量单测（会话作用域白名单）。
 */
class FileScopeTest {

    @Test
    void should_buildSessionScope_when_ofSession_given_sessionId() {
        // given // when
        FileScope scope = FileScope.ofSession("sess_1");

        // then
        assertEquals("sess_1", scope.id());
        assertEquals(FileScope.TYPE_SESSION, scope.type());
    }

    @Test
    void should_throwException_when_constructor_given_blankId() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new FileScope(" ", FileScope.TYPE_SESSION));
    }

    @Test
    void should_throwException_when_constructor_given_unsupportedType() {
        // given // when // then（当前仅支持 session 作用域）
        assertThrows(IllegalArgumentException.class,
                () -> new FileScope("sess_1", "workspace"));
        assertThrows(IllegalArgumentException.class,
                () -> new FileScope("sess_1", null));
    }
}
