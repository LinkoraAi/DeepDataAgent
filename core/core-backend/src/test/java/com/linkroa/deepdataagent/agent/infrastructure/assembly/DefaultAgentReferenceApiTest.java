package com.linkroa.deepdataagent.agent.infrastructure.assembly;

import com.linkroa.deepdataagent.agent.domain.repository.AgentVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultAgentReferenceApi} 跨 BC 引用计数单测。
 * <p>核心语义：技能绑定引用计数委托 {@link AgentVersionRepository}，
 * 统计 skills_json 中绑定指定技能的未删除 Agent 版本数。</p>
 */
@ExtendWith(MockitoExtension.class)
class DefaultAgentReferenceApiTest {

    /** 钉版技能版本键（创建时刻 epoch 微秒字符串）。 */
    private static final String EPOCH = "1759178010641129";

    @Mock private AgentVersionRepository agentVersionRepository;

    private DefaultAgentReferenceApi api;

    @BeforeEach
    void setUp() {
        api = new DefaultAgentReferenceApi();
        ReflectionTestUtils.setField(api, "agentVersionRepository", agentVersionRepository);
    }

    @Test
    void should_delegateCountToRepository_when_countSkillBindings_given_bindingsExist() {
        // given（skills_json 中绑定 skill-1 的未删除版本共 3 个）
        when(agentVersionRepository.countSkillBindings("skill-1")).thenReturn(3L);

        // when
        long count = api.countSkillBindings("skill-1");

        // then
        assertEquals(3L, count);
        verify(agentVersionRepository).countSkillBindings("skill-1");
    }

    @Test
    void should_returnZero_when_countSkillBindings_given_noBindings() {
        // given（技能无任何绑定，可安全删除）
        when(agentVersionRepository.countSkillBindings("skill-x")).thenReturn(0L);

        // when
        long count = api.countSkillBindings("skill-x");

        // then
        assertEquals(0L, count);
        verify(agentVersionRepository).countSkillBindings("skill-x");
    }

    @Test
    void should_delegateVersionScopedCount_when_countSkillVersionBindings_given_deleteVersionCheck() {
        // given（6.4 delete-version：按 {skill_id, version} 两键约束计数，版本键为 epoch 微秒字符串）
        when(agentVersionRepository.countSkillVersionBindings("skill-x", EPOCH)).thenReturn(1L);

        // when
        long count = api.countSkillVersionBindings("skill-x", EPOCH);

        // then
        assertEquals(1L, count);
        verify(agentVersionRepository).countSkillVersionBindings("skill-x", EPOCH);
    }
}
