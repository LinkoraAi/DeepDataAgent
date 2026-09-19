package com.linkroa.deepdataagent.agent.domain.model;

import com.linkroa.deepdataagent.agent.domain.model.enums.EnvironmentType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EnvironmentConfig} 值对象不变量单测。
 */
class EnvironmentConfigTest {

    @Test
    void should_holdAllFields_when_construct_given_cloudFullConfig() {
        // given
        EnvironmentPackages packages = new EnvironmentPackages(
                List.of("git"), null, null, null, List.of("typescript@5.0.0"), null);

        // when
        EnvironmentConfig config = new EnvironmentConfig(EnvironmentType.CLOUD, packages, "echo ready");

        // then
        assertEquals(EnvironmentType.CLOUD, config.type());
        assertEquals(List.of("git"), config.packages().apt());
        assertEquals("echo ready", config.setupScript());
    }

    @Test
    void should_defaultToEmptyPackages_when_construct_given_nullPackages() {
        // given // when
        EnvironmentConfig config = new EnvironmentConfig(EnvironmentType.CLOUD, null, null);

        // then
        assertTrue(config.packages().isEmpty());
    }

    @Test
    void should_normalizeBlankScript_when_construct_given_whitespaceScript() {
        // given // when
        EnvironmentConfig config = new EnvironmentConfig(EnvironmentType.CLOUD, null, "   ");

        // then
        assertNull(config.setupScript());
    }

    @Test
    void should_throwException_when_construct_given_nullType() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new EnvironmentConfig(null, null, null));
    }

    @Test
    void should_throwException_when_construct_given_scriptExceeds64Kb() {
        // given
        String oversized = "a".repeat(EnvironmentConfig.MAX_SETUP_SCRIPT_BYTES + 1);

        // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new EnvironmentConfig(EnvironmentType.CLOUD, null, oversized));
    }

    @Test
    void should_acceptScriptOnly_when_construct_given_selfHostedType() {
        // given // when
        EnvironmentConfig config = new EnvironmentConfig(
                EnvironmentType.SELF_HOSTED, null, "apt-get update");

        // then
        assertEquals(EnvironmentType.SELF_HOSTED, config.type());
        assertEquals("apt-get update", config.setupScript());
    }

    @Test
    void should_throwException_when_construct_given_selfHostedWithPackages() {
        // given
        EnvironmentPackages packages = new EnvironmentPackages(
                null, null, null, null, null, List.of("requests==2.31.0"));

        // when // then（self_hosted 仅支持 type 与可选 setup_script）
        assertThrows(IllegalArgumentException.class,
                () -> new EnvironmentConfig(EnvironmentType.SELF_HOSTED, packages, null));
    }

    @Test
    void should_returnCloudDefaults_when_cloudDefault_given_noArgs() {
        // given // when
        EnvironmentConfig config = EnvironmentConfig.cloudDefault();

        // then
        assertEquals(EnvironmentType.CLOUD, config.type());
        assertTrue(config.packages().isEmpty());
        assertNull(config.setupScript());
    }
}
