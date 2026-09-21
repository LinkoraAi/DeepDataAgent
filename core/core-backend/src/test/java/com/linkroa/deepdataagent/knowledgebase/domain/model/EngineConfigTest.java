package com.linkroa.deepdataagent.knowledgebase.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EngineConfig} 单元测试。
 * <p>覆盖空配置共享单例语义（converge-rag-hot-path-object-creation / 5.6）：
 * {@code empty()} 多次调用返回同一不可变实例，热路径零重复分配。</p>
 *
 * @author DeepDataAgent
 */
class EngineConfigTest {

    /**
     * 场景：多次调用 {@link EngineConfig#empty()}。
     * 预期：每次返回同一实例，且参数映射为空。
     */
    @Test
    void should_returnSameInstance_when_empty_given_repeatedCalls() {
        // when
        EngineConfig first = EngineConfig.empty();
        EngineConfig second = EngineConfig.empty();

        // then
        assertSame(first, second, "空配置应共享单例而非每次新建");
        assertTrue(first.params().isEmpty(), "空配置的参数映射应为空");
    }
}
