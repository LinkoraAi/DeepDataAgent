package com.linkroa.deepdataagent.agent.infrastructure.assembly;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link DefaultSessionEnvironmentVariablesValidationPort} 单测：纯委托 runtime 侧
 * {@code SessionEnvironmentVariablesValidator}（agent BC 消费该判定的唯一装配适配点），
 * 判定结果与其 400 语义（{@code IllegalArgumentException}）原样透传、不另立规则。
 */
class DefaultSessionEnvironmentVariablesValidationPortTest {

    private final DefaultSessionEnvironmentVariablesValidationPort port =
            new DefaultSessionEnvironmentVariablesValidationPort();

    @Test
    void should_passThroughRuntimeRule_when_validate_given_legalVariables() {
        // given（合法形态：名字匹配 [A-Za-z_][A-Za-z0-9_]*、值为字符串；未提供=空白）
        // when & then（不抛即通过）
        assertDoesNotThrow(() -> port.validate("{\"API_HOST\":\"example.com\"}"));
        assertDoesNotThrow(() -> port.validate(null));
    }

    @Test
    void should_propagateIllegalArgument_when_validate_given_nonStringValue() {
        // given（值非字符串：判定权威在 runtime 校验器）
        // when & then（异常类型原样透传，agent 侧不复制判定）
        assertThrows(IllegalArgumentException.class, () -> port.validate("{\"COUNT\":5}"));
    }
}