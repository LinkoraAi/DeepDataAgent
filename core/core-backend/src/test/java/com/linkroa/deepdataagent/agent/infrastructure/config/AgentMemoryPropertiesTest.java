package com.linkroa.deepdataagent.agent.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentMemoryProperties 单元测试
 * <p>覆盖默认值、workspace 路径解析与 setter 行为。</p>
 */
class AgentMemoryPropertiesTest {

    @Test
    void should_haveDefaults_when_created() {
        // when
        AgentMemoryProperties props = new AgentMemoryProperties();

        // then
        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getConsolidationMaxTokens()).isEqualTo(4000);
        assertThat(props.getConsolidationMinGap()).isEqualTo(Duration.ofHours(1));
        assertThat(props.getFlushTrigger()).isEqualTo("throttled");
        assertThat(props.getWorkspace()).endsWith("/data/agentscope");
    }

    @Test
    void should_useDefaultWorkspace_when_setWorkspace_given_blank() {
        // given
        AgentMemoryProperties props = new AgentMemoryProperties();
        String defaultWorkspace = props.getWorkspace();

        // when
        props.setWorkspace("   ");

        // then
        assertThat(props.getWorkspace()).isEqualTo(defaultWorkspace);
    }

    @Test
    void should_useCustomWorkspace_when_setWorkspace_given_validPath() {
        // given
        AgentMemoryProperties props = new AgentMemoryProperties();

        // when
        props.setWorkspace("custom/data/agentscope");

        // then
        assertThat(props.getWorkspace()).isEqualTo("custom/data/agentscope");
    }

    @Test
    void should_applySetters_when_set() {
        // given
        AgentMemoryProperties props = new AgentMemoryProperties();

        // when
        props.setEnabled(false);
        props.setConsolidationMaxTokens(2000);
        props.setConsolidationMinGap(Duration.ofMinutes(30));
        props.setFlushTrigger("always");

        // then
        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getConsolidationMaxTokens()).isEqualTo(2000);
        assertThat(props.getConsolidationMinGap()).isEqualTo(Duration.ofMinutes(30));
        assertThat(props.getFlushTrigger()).isEqualTo("always");
    }
}