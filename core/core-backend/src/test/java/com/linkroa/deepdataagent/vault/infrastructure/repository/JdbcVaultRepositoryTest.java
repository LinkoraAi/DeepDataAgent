package com.linkroa.deepdataagent.vault.infrastructure.repository;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.linkroa.deepdataagent.vault.domain.model.Vault;
import com.linkroa.deepdataagent.vault.domain.model.VaultListFilter;
import com.linkroa.deepdataagent.vault.infrastructure.persistence.entity.VaultEntity;
import com.linkroa.deepdataagent.vault.infrastructure.persistence.mapper.VaultMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JdbcVaultRepository} 单测：保存清主键回读、游标查询委托映射、
 * 归档置位透传与逻辑删条件装配（6.6 Vault 改造后的仓储语义）。
 */
@ExtendWith(MockitoExtension.class)
class JdbcVaultRepositoryTest {

    static {
        // 初始化实体 TableInfo：delete 条件包装器 lambda 列名解析所需
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, VaultEntity.class);
    }

    @Mock
    private VaultMapper mapper;

    @InjectMocks
    private JdbcVaultRepository repository;

    private final OffsetDateTime now = OffsetDateTime.parse("2026-09-04T10:00:00+08:00");

    private VaultEntity buildEntity(String vaultId) {
        VaultEntity entity = new VaultEntity();
        entity.setId(5L);
        entity.setVaultId(vaultId);
        entity.setDisplayName("密钥库");
        entity.setMetadata("{}");
        entity.setOwnerId(1L);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private Vault buildVault() {
        return Vault.restore(5L, "vault_1", "密钥库", "{}", 1L, null, now, now, null, null);
    }

    @Test
    void should_insertWithClearedIdAndReadBack_when_save_given_newVault() {
        // given
        when(mapper.selectByVaultId("vault_1")).thenReturn(buildEntity("vault_1"));

        // when
        Vault saved = repository.save(buildVault());

        // then（主键清空后插入，落库后回读数据库快照）
        ArgumentCaptor<VaultEntity> captor = ArgumentCaptor.forClass(VaultEntity.class);
        verify(mapper).insert(captor.capture());
        assertNull(captor.getValue().getId());
        assertEquals("vault_1", saved.vaultId());
        assertEquals(5L, saved.id());
    }

    @Test
    void should_delegateAndMapDomain_when_findByCursor_given_filterAndLimit() {
        // given（游标条件对象原样透传，实体流经转换器映射为领域模型）
        VaultListFilter filter = new VaultListFilter(null, null, false, now, 5L, false);
        when(mapper.selectByCursor(eq(1L), same(filter), eq(21)))
                .thenReturn(List.of(buildEntity("vault_1"), buildEntity("vault_2")));

        // when
        List<Vault> page = repository.findByCursor(1L, filter, 21);

        // then
        assertEquals(2, page.size());
        assertEquals("vault_1", page.get(0).vaultId());
        assertEquals(5L, page.get(0).id());
    }

    @Test
    void should_passThroughArchivedAtAndAffectedRows_when_archive_given_vaultId() {
        // given
        OffsetDateTime archivedAt = OffsetDateTime.parse("2026-09-05T09:00:00+08:00");
        when(mapper.updateArchivedAt("vault_1", archivedAt)).thenReturn(1);

        // when & then
        assertEquals(1, repository.archive("vault_1", archivedAt));
        verify(mapper).updateArchivedAt("vault_1", archivedAt);
    }

    @Test
    void should_logicalDeleteByVaultIdCondition_when_deleteByVaultId_given_vaultId() {
        // when（@TableLogic：delete 为逻辑删，按业务 ID 装配条件）
        repository.deleteByVaultId("vault_1");

        // then
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<VaultEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).delete(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("vault_id ="),
                captor.getValue().getSqlSegment());
    }

    @Test
    void should_returnEmpty_when_findByVaultId_given_miss() {
        // given
        when(mapper.selectByVaultId("vault_x")).thenReturn(null);

        // when & then
        assertEquals(Optional.empty(), repository.findByVaultId("vault_x"));
    }
}
