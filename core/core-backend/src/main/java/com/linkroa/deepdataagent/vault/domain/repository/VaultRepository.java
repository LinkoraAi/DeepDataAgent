package com.linkroa.deepdataagent.vault.domain.repository;

import com.linkroa.deepdataagent.vault.domain.model.Vault;
import com.linkroa.deepdataagent.vault.domain.model.VaultListFilter;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 保管库仓储接口（依赖倒置，领域语义方法）。
 */
public interface VaultRepository {

    /** 保存保管库（新增）。 */
    Vault save(Vault vault);

    /** 按业务ID查询。 */
    Optional<Vault> findByVaultId(String vaultId);

    /** 按业务ID查询并锁定该行（FOR UPDATE），用于删除等 check-then-act 场景的事务内串行化。 */
    Optional<Vault> findByVaultIdForUpdate(String vaultId);

    /** 按 owner + 业务 ID 批量查询（owner 隔离 + 排除已归档，供运行时解密注入防越权）。 */
    List<Vault> findByIds(Long ownerId, List<String> vaultIds);

    /** 游标分页查询（owner 隔离；名称模糊 / metadata 包含 / 归档态过滤；创建时间降序 keyset）。 */
    List<Vault> findByCursor(Long ownerId, VaultListFilter filter, int limit);

    /**
     * 统计满足过滤条件的保管库总数（搜索端点首页 {@code total}；游标位置不计入条件）。
     */
    long countByFilter(Long ownerId, VaultListFilter filter);

    /** 归档（软删，置 archived_at）。 */
    int archive(String vaultId, OffsetDateTime archivedAt);

    /** 逻辑删除保管库（凭证级联删除由应用服务协调，历史数据保留）。 */
    void deleteByVaultId(String vaultId);
}