package com.linkroa.deepdataagent.vault.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.vault.domain.model.VaultCredentialListFilter;
import com.linkroa.deepdataagent.vault.infrastructure.persistence.entity.VaultCredentialEntity;
import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 凭证 Mapper（数据库访问器，MyBatis-Plus）。
 */
@Mapper
public interface VaultCredentialMapper extends BaseMapper<VaultCredentialEntity> {

    default VaultCredentialEntity selectByVaultIdAndCredentialId(String vaultId, String credentialId) {
        return selectOne(Wrappers.<VaultCredentialEntity>lambdaQuery()
                .eq(VaultCredentialEntity::getVaultId, vaultId)
                .eq(VaultCredentialEntity::getCredentialId, credentialId)
                .last("LIMIT 1"));
    }

    /** 按保管库批量查询（供运行时解密注入）。 */
    default List<VaultCredentialEntity> selectByVaultIds(List<String> vaultIds) {
        if (vaultIds == null || vaultIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<VaultCredentialEntity>lambdaQuery()
                .in(VaultCredentialEntity::getVaultId, vaultIds));
    }

    /** 列出一保管库下全部凭证（按创建时间升序）。 */
    default List<VaultCredentialEntity> selectByVaultId(String vaultId) {
        return selectList(Wrappers.<VaultCredentialEntity>lambdaQuery()
                .eq(VaultCredentialEntity::getVaultId, vaultId)
                .orderByAsc(VaultCredentialEntity::getCreatedAt)
                .orderByAsc(VaultCredentialEntity::getId));
    }

    /**
     * 游标分页查询某保管库下凭证（URL 模糊 + 归档态三态过滤；{@code (created_at, id)} keyset，
     * 默认创建时间降序，reverse=before 方向升序读取）。
     */
    default List<VaultCredentialEntity> selectByCursor(String vaultId, VaultCredentialListFilter filter,
                                                       int limit) {
        LambdaQueryWrapper<VaultCredentialEntity> wrapper = Wrappers.<VaultCredentialEntity>lambdaQuery()
                .eq(VaultCredentialEntity::getVaultId, vaultId);
        if (filter.archived() != null) {
            if (filter.archived()) {
                wrapper.isNotNull(VaultCredentialEntity::getArchivedAt);
            } else {
                wrapper.isNull(VaultCredentialEntity::getArchivedAt);
            }
        }
        if (StringUtils.isNotBlank(filter.urlSearch())) {
            // 按绑定的 MCP 服务器 URL 模糊过滤（通配符按字面量转义）
            wrapper.apply("mcp_server_url like {0}", VaultMapper.likePattern(filter.urlSearch().trim()));
        }
        if (filter.cursorCreatedAt() != null && filter.cursorRowId() != null) {
            if (filter.reverse()) {
                wrapper.apply("(created_at, id) > ({0}, {1})",
                        filter.cursorCreatedAt(), filter.cursorRowId());
                wrapper.orderByAsc(VaultCredentialEntity::getCreatedAt).orderByAsc(VaultCredentialEntity::getId);
            } else {
                wrapper.apply("(created_at, id) < ({0}, {1})",
                        filter.cursorCreatedAt(), filter.cursorRowId());
                wrapper.orderByDesc(VaultCredentialEntity::getCreatedAt).orderByDesc(VaultCredentialEntity::getId);
            }
        } else if (filter.reverse()) {
            wrapper.orderByAsc(VaultCredentialEntity::getCreatedAt).orderByAsc(VaultCredentialEntity::getId);
        } else {
            wrapper.orderByDesc(VaultCredentialEntity::getCreatedAt).orderByDesc(VaultCredentialEntity::getId);
        }
        return selectList(wrapper.last("LIMIT " + limit));
    }

    /** 统计保管库下活跃（未归档）凭证数（逻辑删除行由 @TableLogic 自动排除）。 */
    default long countActiveByVaultId(String vaultId) {
        return selectCount(Wrappers.<VaultCredentialEntity>lambdaQuery()
                .eq(VaultCredentialEntity::getVaultId, vaultId)
                .isNull(VaultCredentialEntity::getArchivedAt));
    }

    /** 判定保管库下是否已存在绑定同一 MCP 服务器 URL 的活跃凭证。 */
    default boolean existsActiveByVaultIdAndUrl(String vaultId, String mcpServerUrl) {
        return selectCount(Wrappers.<VaultCredentialEntity>lambdaQuery()
                .eq(VaultCredentialEntity::getVaultId, vaultId)
                .eq(VaultCredentialEntity::getMcpServerUrl, mcpServerUrl)
                .isNull(VaultCredentialEntity::getArchivedAt)) > 0;
    }

    /**
     * 归档凭证（软删）：置 archived_at（仅限未归档且未逻辑删除行，天然幂等），
     * 并刷新审计时间戳（自定义 SQL 绕过 MetaObjectHandler，需手动带 updated_at）。
     */
    @Update("UPDATE vault_credentials SET archived_at = #{archivedAt}, updated_at = now() "
            + "WHERE vault_id = #{vaultId} AND credential_id = #{credentialId} "
            + "AND is_deleted = 0 AND archived_at IS NULL")
    int updateArchivedAt(@Param("vaultId") String vaultId,
                         @Param("credentialId") String credentialId,
                         @Param("archivedAt") OffsetDateTime archivedAt);

    /**
     * 条件更新秘密材料（CAS，见 design D6）：比对行内当前密文，仅匹配时写入轮换结果；
     * 自定义 SQL 绕过 MetaObjectHandler，需手动带 {@code updated_at}。
     *
     * @return 影响行数（0 = 已被并发接管者改写，本次轮换不得覆盖）
     */
    @Update("UPDATE vault_credentials SET ciphertext = #{ciphertext}, expires_at = #{expiresAt}, "
            + "updated_at = now() WHERE vault_id = #{vaultId} AND credential_id = #{credentialId} "
            + "AND ciphertext = #{expectedCiphertext} AND is_deleted = 0")
    int updateCipherIfUnchanged(@Param("vaultId") String vaultId,
                                @Param("credentialId") String credentialId,
                                @Param("ciphertext") byte[] ciphertext,
                                @Param("expiresAt") OffsetDateTime expiresAt,
                                @Param("expectedCiphertext") byte[] expectedCiphertext);
}