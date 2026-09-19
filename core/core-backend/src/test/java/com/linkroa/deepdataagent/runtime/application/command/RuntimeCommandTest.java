package com.linkroa.deepdataagent.runtime.application.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 应用层命令不变量单测（CreateSessionCommand / SendMessageCommand / UpdateSessionCommand）。
 */
class RuntimeCommandTest {

    // ===== CreateSessionCommand =====

    @Test
    void should_buildCommand_when_construct_given_validInputs() {
        // when
        CreateSessionCommand command = new CreateSessionCommand("u-1", "agent-a", "1.0.0", "会话", "{}");

        // then
        assertEquals("u-1", command.userId());
        assertEquals("agent-a", command.agentId());
        assertEquals("1.0.0", command.agentVersion());
        assertEquals("会话", command.title());
        assertEquals("{}", command.metadata());
    }

    @Test
    void should_throw_when_construct_given_blankUserId() {
        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new CreateSessionCommand("", "agent-a", "1.0.0", null, null));
    }

    @Test
    void should_throw_when_construct_given_blankAgentId() {
        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new CreateSessionCommand("u-1", " ", "1.0.0", null, null));
    }

    @Test
    void should_defaultResources_when_construct_given_noResources() {
        // when（5 参便捷构造：普通用户会话，无触发标记、无挂载资源）
        CreateSessionCommand command = new CreateSessionCommand("u-1", "agent-a", "1.0.0", "会话", "{}");

        // then
        assertEquals(List.of(), command.resources());
    }

    @Test
    void should_normalizeNullResources_when_construct_given_nullResources() {
        // when（8 参全量构造：null 挂载资源收敛为空列表）
        CreateSessionCommand command = new CreateSessionCommand(
                "u-1", "agent-a", "1.0.0", null, null, null, null, null);

        // then
        assertEquals(List.of(), command.resources());
    }

    @Test
    void should_normalizeNullVaultIds_when_construct_given_nullVaultIds() {
        // when（11 参全量构造：null 保管库列表收敛为空列表）
        CreateSessionCommand command = new CreateSessionCommand(
                "u-1", "agent-a", "1.0.0", null, null, null, null, null, "env-1", null, "{\"K\":\"V\"}");

        // then（环境 / 保管库 / 环境变量随命令承载，环境 ID 透传）
        assertEquals(List.of(), command.vaultIds());
        assertEquals("env-1", command.environmentId());
        assertEquals("{\"K\":\"V\"}", command.environmentVariables());
    }

    @Test
    void should_defaultNewMountColumns_when_convenienceConstructors_given_legacyArity() {
        // when（5 / 7 / 8 参便捷构造：新列走默认值兜底）
        CreateSessionCommand legacy = new CreateSessionCommand("u-1", "agent-a", "1.0.0", "会话", "{}");

        // then
        assertNull(legacy.environmentId());
        assertEquals(List.of(), legacy.vaultIds());
        assertNull(legacy.environmentVariables());
    }

    @Test
    void should_allowNullVersion_when_construct_given_blankAgentVersion() {
        // when（agentVersion 可空：省略时由服务端解析激活版本物化到会话）
        CreateSessionCommand command = new CreateSessionCommand("u-1", "agent-a", null, null, null);

        // then
        assertNull(command.agentVersion());
    }

    @Test
    void should_throw_when_construct_given_titleTooLong() {
        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new CreateSessionCommand("u-1", "agent-a", "1.0.0", "t".repeat(256), null));
    }

    // ===== SendMessageCommand =====

    @Test
    void should_buildCommandWithGeneratedRunId_when_twoArgConstructor_given_validInputs() {
        // when
        SendMessageCommand command = new SendMessageCommand("s-1", "你好");

        // then
        assertEquals("s-1", command.sessionId());
        assertEquals("你好", command.message());
        assertNull(command.runId());
    }

    @Test
    void should_keepRunId_when_threeArgConstructor_given_runId() {
        // when
        SendMessageCommand command = new SendMessageCommand("s-1", "你好", "run-9");

        // then
        assertEquals("run-9", command.runId());
    }

    @Test
    void should_throw_when_construct_given_blankSessionId() {
        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new SendMessageCommand(" ", "你好"));
    }

    @Test
    void should_throw_when_construct_given_blankMessage() {
        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new SendMessageCommand("s-1", ""));
    }

    // ===== UpdateSessionCommand =====

    @Test
    void should_buildCommand_when_constructUpdateCommand_given_sessionIdAndOptionalFields() {
        // when（可更新字段全部可空：null=不改；titlePresent=true 表示本次显式提交 title）
        UpdateSessionCommand command = new UpdateSessionCommand("s-1", true, "新标题", null, null);

        // then
        assertEquals("s-1", command.sessionId());
        assertEquals("新标题", command.title());
        assertNull(command.metadataJson());
        assertNull(command.environmentVariablesJson());
    }

    @Test
    void should_throwForUpdateCommand_when_construct_given_blankSessionId() {
        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new UpdateSessionCommand(" ", false, null, null, null));
    }

    // ===== ResolveHumanConfirmationCommand =====

    @Test
    void should_buildCommand_when_constructConfirmCommand_given_sessionIdAndConfirmed() {
        // when
        ResolveHumanConfirmationCommand command = new ResolveHumanConfirmationCommand("s-1", true);

        // then
        assertEquals("s-1", command.sessionId());
        assertEquals(true, command.confirmed());
    }

    @Test
    void should_throwForConfirmCommand_when_construct_given_blankSessionId() {
        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new ResolveHumanConfirmationCommand(" ", false));
    }
}