package com.linkroa.deepdataagent.vault.infrastructure.repository;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.linkroa.deepdataagent.vault.domain.model.VaultCredential;
import com.linkroa.deepdataagent.vault.domain.model.enums.VaultCredentialAuthType;
import com.linkroa.deepdataagent.vault.infrastructure.persistence.entity.VaultCredentialEntity;
import com.linkroa.deepdataagent.vault.infrastructure.persistence.mapper.VaultCredentialMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JdbcVaultCredentialRepository} 单测：保存清主键回读、轮换更新回读、
 * 条件写入（CAS 基线透传）、归档置位透传与级联逻辑删条件装配
 * （6.6 凭证 archive / rotate 改造后的仓储语义）。
 */
@ExtendWith(MockitoExtension.class)
class JdbcVaultCredentialRepositoryTest {

    static {
        // 初始化实体 TableInfo：delete 条件包装器 lambda 列名解析所需
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, VaultCredentialEntity.class);
    }

    @Mock
    private VaultCredentialMapper mapper;

    @InjectMocks
    private JdbcVaultCredentialRepository repository;

    private final OffsetDateTime now = OffsetDateTime.parse("2026-09-04T10:00:00+08:00");

    private VaultCredentialEntity buildEntity(String credentialId) {
        VaultCredentialEntity entity = new VaultCredentialEntity();
        entity.setId(8L);
        entity.setCredentialId(credentialId);
        entity.setVaultId("vault_1");
        entity.setAuthType("static_bearer");
        entity.setMcpServerUrl("https://mcp.example.com/sse");
        entity.setCiphertext("cipher".getBytes(StandardCharsets.UTF_8));
        entity.setExpiresAt(now);
        entity.setMetadata("{\"env\":\"prod\"}");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private VaultCredential buildCredential() {
        return VaultCredential.restore(8L, "cr_1", "vault_1", VaultCredentialAuthType.STATIC_BEARER,
                "https://mcp.example.com/sse", "cipher".getBytes(StandardCharsets.UTF_8),
                now, "{\"env\":\"prod\"}", null, now, now, null, null);
    }

    @Test
    void should_insertWithClearedIdAndReadBack_when_save_given_newCredential() {
        // given
        when(mapper.selectByVaultIdAndCredentialId("vault_1", "cr_1")).thenReturn(buildEntity("cr_1"));

        // when
        VaultCredential saved = repository.save(buildCredential());

        // then（主键清空后插入，落库后回读数据库快照）
        ArgumentCaptor<VaultCredentialEntity> captor = ArgumentCaptor.forClass(VaultCredentialEntity.class);
        verify(mapper).insert(captor.capture());
        assertNull(captor.getValue().getId());
        assertEquals("cr_1", saved.credentialId());
        assertEquals(8L, saved.id());
    }

    @Test
    void should_updateByIdAndReadBack_when_update_given_rotatedCredential() {
        // given（轮换后回读返回新密文快照）
        VaultCredentialEntity rotated = buildEntity("cr_1");
        rotated.setCiphertext("newcipher".getBytes(StandardCharsets.UTF_8));
        when(mapper.selectByVaultIdAndCredentialId("vault_1", "cr_1")).thenReturn(rotated);

        // when
        VaultCredential updated = repository.update(buildCredential());

        // then（按主键更新 + 回读新快照）
        ArgumentCaptor<VaultCredentialEntity> captor = ArgumentCaptor.forClass(VaultCredentialEntity.class);
        verify(mapper).updateById(captor.capture());
        assertEquals(8L, captor.getValue().getId());
        assertArrayEquals("newcipher".getBytes(StandardCharsets.UTF_8), updated.ciphertext());
    }

    @Test
    void should_passThroughArchiveConditionAndAffectedRows_when_archive_given_ids() {
        // given（归档自定义 SQL：仅未归档行置位，返回影响行数）
        OffsetDateTime archivedAt = OffsetDateTime.parse("2026-09-05T09:00:00+08:00");
        when(mapper.updateArchivedAt(eq("vault_1"), eq("cr_1"), eq(archivedAt))).thenReturn(1);

        // when & then
        assertEquals(1, repository.archive("vault_1", "cr_1", archivedAt));
        verify(mapper).updateArchivedAt("vault_1", "cr_1", archivedAt);
    }

    @Test
    void should_passRotatedCipherWithRowBaseline_when_updateIfCipherUnchanged_given_rotatedCredential() {
        // given（刷新轮换的条件写入：比对基准是行内原始密文本体，写入轮换后密文与到期时间）
        byte[] expected = "cipher".getBytes(StandardCharsets.UTF_8);
        byte[] rotated = "newcipher".getBytes(StandardCharsets.UTF_8);
        OffsetDateTime newExpiry = OffsetDateTime.parse("2026-09-05T09:00:00+08:00");
        VaultCredential credential = VaultCredential.restore(8L, "cr_1", "vault_1",
                VaultCredentialAuthType.MCP_OAUTH, "https://mcp.example.com/sse", rotated,
                newExpiry, null, null, now, now, null, null);
        when(mapper.updateCipherIfUnchanged(anyString(), anyString(), any(), any(), any())).thenReturn(1);

        // when
        boolean applied = repository.updateIfCipherUnchanged(credential, expected);

        // then（基线原样透传 mapper，命中即视为本次写入胜出）
        assertTrue(applied);
        ArgumentCaptor<byte[]> rotatedCaptor = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<byte[]> baselineCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(mapper).updateCipherIfUnchanged(eq("vault_1"), eq("cr_1"),
                rotatedCaptor.capture(), eq(newExpiry), baselineCaptor.capture());
        assertArrayEquals(rotated, rotatedCaptor.getValue());
        assertArrayEquals(expected, baselineCaptor.getValue());
    }

    @Test
    void should_returnFalse_when_updateIfCipherUnchanged_given_noRowMatched() {
        // given（并发接管者已写入：基线失配，改动零行）
        VaultCredential credential = VaultCredential.restore(8L, "cr_1", "vault_1",
                VaultCredentialAuthType.MCP_OAUTH, "https://mcp.example.com/sse",
                "newcipher".getBytes(StandardCharsets.UTF_8), now, null, null, now, now, null, null);
        when(mapper.updateCipherIfUnchanged(anyString(), anyString(), any(), any(), any())).thenReturn(0);

        // when & then
        assertFalse(repository.updateIfCipherUnchanged(credential, "cipher".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void should_logicalDeleteByVaultIdCondition_when_deleteByVaultId_given_vaultId() {
        // when（@TableLogic：级联删除实为逻辑删，按所属保管库装配条件）
        repository.deleteByVaultId("vault_1");

        // then
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<VaultCredentialEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).delete(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("vault_id ="),
                captor.getValue().getSqlSegment());
    }

    @Test
    void should_mapDomainList_when_listByVaultId_given_entities() {
        // given
        when(mapper.selectByVaultId("vault_1")).thenReturn(List.of(buildEntity("cr_1"), buildEntity("cr_2")));

        // when
        List<VaultCredential> credentials = repository.listByVaultId("vault_1");

        // then（鉴权类型字符串 ⇄ 领域枚举转换；到期时间与元数据列随行映射）
        assertEquals(2, credentials.size());
        assertEquals(VaultCredentialAuthType.STATIC_BEARER, credentials.get(0).authType());
        assertEquals("cr_2", credentials.get(1).credentialId());
        assertEquals(now, credentials.get(0).expiresAt());
        assertEquals("{\"env\":\"prod\"}", credentials.get(0).metadata());
    }
}
