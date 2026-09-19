package com.linkroa.deepdataagent.vault.domain.repository;

import com.linkroa.deepdataagent.vault.domain.model.VaultCredential;
import com.linkroa.deepdataagent.vault.domain.model.VaultCredentialListFilter;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 凭证仓储接口（依赖倒置，领域语义方法）。
 */
public interface VaultCredentialRepository {

    /** 保存凭证（新增，密文落库）。 */
    VaultCredential save(VaultCredential credential);

    /** 更新凭证（按主键整行更新秘密材料密文 / 到期时间 / 元数据；身份字段不变）。 */
    VaultCredential update(VaultCredential credential);

    /**
     * 条件更新秘密材料（CAS，见 design D6）：仅当行内密文仍等于 {@code expectedCiphertext} 时
     * 写入轮换后的密文与到期时间；更新范围不含身份字段与元数据。
     *
     * @param credential         轮换后的凭证（取密文与到期时间）
     * @param expectedCiphertext 读到的当前密文字节（并发接管者已改写时不再匹配）
     * @return 是否本次写入胜出（false = 已被并发接管者改写，本次结果不得覆盖）
     */
    boolean updateIfCipherUnchanged(VaultCredential credential, byte[] expectedCiphertext);

    /** 按保管库 + 凭证业务 ID 查询。 */
    Optional<VaultCredential> findByVaultIdAndCredentialId(String vaultId, String credentialId);

    /** 按保管库集合批量查询（供运行时解密注入）。 */
    List<VaultCredential> findByVaultIds(List<String> vaultIds);

    /** 列出一保管库下全部凭证（按创建时间升序）。 */
    List<VaultCredential> listByVaultId(String vaultId);

    /** 游标分页查询某保管库下凭证（URL 模糊 / 归档态过滤；创建时间降序 keyset）。 */
    List<VaultCredential> findByCursor(String vaultId, VaultCredentialListFilter filter, int limit);

    /** 统计保管库下活跃（未归档）凭证数（用于「单库活跃凭证数上限」校验）。 */
    long countActiveByVaultId(String vaultId);

    /** 判定保管库下是否已存在绑定同一 MCP 服务器 URL 的活跃凭证（用于「一个连接一种认证方式」校验）。 */
    boolean existsActiveByVaultIdAndUrl(String vaultId, String mcpServerUrl);

    /** 归档凭证（软删，置 archived_at；已归档行不重复归档，返回影响行数）。 */
    int archive(String vaultId, String credentialId, OffsetDateTime archivedAt);

    /**
     * 逻辑删除单条凭证（删除后从列表 / 详情不可见，不再参与任何 Session 挂载鉴权）。
     *
     * @return 影响行数（0 = 凭证不存在）
     */
    int delete(String vaultId, String credentialId);

    /** 逻辑删除某保管库下全部凭证（删除保管库的级联动作，历史数据保留）。 */
    void deleteByVaultId(String vaultId);
}