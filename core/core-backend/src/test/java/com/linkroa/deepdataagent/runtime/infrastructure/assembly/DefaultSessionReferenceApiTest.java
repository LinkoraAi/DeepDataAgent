package com.linkroa.deepdataagent.runtime.infrastructure.assembly;

import com.linkroa.deepdataagent.runtime.domain.repository.AgentSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultSessionReferenceApi} 单测：跨 BC 服务契约三方法对
 * 会话仓储引用统计的纯委托语义（环境 / 记忆库 / 保管库删除引用守卫的计数来源）。
 */
@ExtendWith(MockitoExtension.class)
class DefaultSessionReferenceApiTest {

    @Mock
    private AgentSessionRepository agentSessionRepository;

    @InjectMocks
    private DefaultSessionReferenceApi api;

    @Test
    void should_delegateToRepository_when_countSessionsByEnvironmentId_given_environmentId() {
        // given
        when(agentSessionRepository.countByEnvironmentId("env_1")).thenReturn(3L);

        // when & then
        assertEquals(3L, api.countSessionsByEnvironmentId("env_1"));
        verify(agentSessionRepository).countByEnvironmentId("env_1");
    }

    @Test
    void should_delegateToRepository_when_countSessionsByMemoryStoreId_given_storeId() {
        // given
        when(agentSessionRepository.countByMemoryStoreId("ms_1")).thenReturn(2L);

        // when & then
        assertEquals(2L, api.countSessionsByMemoryStoreId("ms_1"));
        verify(agentSessionRepository).countByMemoryStoreId("ms_1");
    }

    @Test
    void should_delegateToRepository_when_countSessionsByVaultId_given_vaultId() {
        // given
        when(agentSessionRepository.countByVaultId("vault_1")).thenReturn(0L);

        // when & then
        assertEquals(0L, api.countSessionsByVaultId("vault_1"));
        verify(agentSessionRepository).countByVaultId("vault_1");
    }
}
