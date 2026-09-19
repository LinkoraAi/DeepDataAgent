package com.linkroa.deepdataagent.vault.infrastructure.assembly;

import com.linkroa.deepdataagent.vault.api.VaultReferenceApi;
import com.linkroa.deepdataagent.vault.api.dto.VaultReferenceDTO;
import com.linkroa.deepdataagent.vault.domain.model.Vault;
import com.linkroa.deepdataagent.vault.domain.repository.VaultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultVaultReferenceApi} 服务契约进程内实现单测（批量引用解析）。
 * <p>仅映射业务 ID 与显示名称（发布语言 DTO），不触碰凭证聚合；
 * 缺失 / 越权保管库由仓储 owner 隔离查询自然缺席，差集判定由消费方完成。</p>
 */
@ExtendWith(MockitoExtension.class)
class DefaultVaultReferenceApiTest {

    @Mock private VaultRepository vaultRepository;

    private VaultReferenceApi vaultReferenceApi;

    @BeforeEach
    void setUp() {
        DefaultVaultReferenceApi api = new DefaultVaultReferenceApi();
        ReflectionTestUtils.setField(api, "vaultRepository", vaultRepository);
        vaultReferenceApi = api;
    }

    @Test
    void should_mapIdAndDisplayName_when_resolveByIds_given_ownedVaults() {
        // given
        when(vaultRepository.findByIds(1L, List.of("vault_1", "vault_2")))
                .thenReturn(List.of(
                        Vault.create("vault_1", "密钥库一", null, 1L),
                        Vault.create("vault_2", "密钥库二", null, 1L)));

        // when
        List<VaultReferenceDTO> refs = vaultReferenceApi.resolveByIds(1L, List.of("vault_1", "vault_2"));

        // then（DTO 仅携带 ID 与显示名称）
        assertEquals(2, refs.size());
        assertEquals("vault_1", refs.get(0).vaultId());
        assertEquals("密钥库一", refs.get(0).displayName());
        assertEquals("vault_2", refs.get(1).vaultId());
    }

    @Test
    void should_returnPartialResult_when_resolveByIds_given_missingVaults() {
        // given（仓储 owner 隔离查询仅命中一个，缺席项由消费方差集判定）
        when(vaultRepository.findByIds(1L, List.of("vault_1", "vault_gone")))
                .thenReturn(List.of(Vault.create("vault_1", "密钥库一", null, 1L)));

        // when
        List<VaultReferenceDTO> refs = vaultReferenceApi.resolveByIds(1L, List.of("vault_1", "vault_gone"));

        // then
        assertEquals(List.of(new VaultReferenceDTO("vault_1", "密钥库一")), refs);
    }

    @Test
    void should_returnEmpty_when_resolveByIds_given_nullOwnerOrEmptyIds() {
        // when & then（缺参短路，不触碰仓储）
        assertTrue(vaultReferenceApi.resolveByIds(null, List.of("vault_1")).isEmpty());
        assertTrue(vaultReferenceApi.resolveByIds(1L, null).isEmpty());
        assertTrue(vaultReferenceApi.resolveByIds(1L, List.of()).isEmpty());
    }
}
