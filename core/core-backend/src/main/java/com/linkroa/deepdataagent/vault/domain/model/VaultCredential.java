package com.linkroa.deepdataagent.vault.domain.model;

import com.linkroa.deepdataagent.vault.domain.model.enums.VaultCredentialAuthType;
import org.apache.commons.lang3.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Objects;

/**
 * 凭证领域模型（VaultCredential，对应 vault_credentials 表）。
 * <p>两层级模型的下层：归属于某保管库（{@link Vault}），以 AES-GCM 加密后的
 * {@code ciphertext}（字节数组，BYTEA 落库）持有秘密值；明文仅在应用 / 基础设施层
 * 内存中出现，不落库、不进响应、不进日志。凭证绑定目标 MCP 服务器
 * （{@code mcpServerUrl}），运行时按该 URL 匹配注入鉴权；列表 / 详情仅返回元数据，
 * 归档凭证（{@code archivedAt} 非空）不再用于新 Session 挂载</p>
 *
 * @param id           数据库主键
 * @param credentialId 凭证业务唯一ID（新写入前缀 vcred_；存量 cr_ 不回填，响应原样返回）
 * @param vaultId      所属保管库业务ID
 * @param authType     凭证鉴权类型（领域枚举，必填，身份字段不可变）
 * @param mcpServerUrl 绑定的 MCP 服务器 URL（必填，≤2048字符，运行时按此匹配注入鉴权；身份字段不可变）
 * @param ciphertext   AES-GCM 加密后的密文（iv + ciphertext，BYTEA 落库；{@code mcp_oauth}
 *                     为整包加密的秘密材料信封，见 design D5）
 * @param expiresAt    访问令牌到期时间（非密列，null=无到期；命中刷新判定无需解密）
 * @param metadata    元数据 JSON（可选，有效 JSON，≤4000字符；JSONB 落库）
 * @param archivedAt   归档时间（软删，NULL=未归档）
 * @param createdAt    创建时间
 * @param updatedAt    更新时间
 * @param createdBy    创建人
 * @param updatedBy    更新人
 */
public record VaultCredential(
        Long id,
        String credentialId,
        String vaultId,
        VaultCredentialAuthType authType,
        String mcpServerUrl,
        byte[] ciphertext,
        OffsetDateTime expiresAt,
        String metadata,
        OffsetDateTime archivedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String createdBy,
        String updatedBy
) {

    /** MCP 服务器 URL 长度上限（与公开契约及 V1 列宽 VARCHAR(2048) 一致）。 */
    private static final int MAX_MCP_SERVER_URL_LENGTH = 2048;
    /** 元数据长度上限（与 {@link Vault#metadata()} 同口径，共用 JSONB 列语义）。 */
    private static final int MAX_METADATA_LENGTH = 4000;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 紧凑构造器：不变量校验（标识必填 + 鉴权类型值域 + URL 长度边界 + 密文非空）。
     */
    public VaultCredential {
        if (StringUtils.isBlank(credentialId)) {
            throw new IllegalArgumentException("凭证ID不能为空");
        }
        if (StringUtils.isBlank(vaultId)) {
            throw new IllegalArgumentException("凭证所属保管库ID不能为空");
        }
        if (authType == null) {
            throw new IllegalArgumentException("凭证鉴权类型不能为空");
        }
        if (StringUtils.isBlank(mcpServerUrl)) {
            throw new IllegalArgumentException("凭证绑定的 MCP 服务器URL不能为空");
        }
        if (mcpServerUrl.length() > MAX_MCP_SERVER_URL_LENGTH) {
            throw new IllegalArgumentException("MCP 服务器URL长度不能超过" + MAX_MCP_SERVER_URL_LENGTH + "个字符");
        }
        if (ciphertext == null || ciphertext.length == 0) {
            throw new IllegalArgumentException("凭证密文不能为空");
        }
        if (metadata != null && metadata.length() > MAX_METADATA_LENGTH) {
            throw new IllegalArgumentException("凭证元数据长度不能超过" + MAX_METADATA_LENGTH + "个字符");
        }
        validateMetadataFormat(metadata);
    }

    /**
     * 创建新的凭证（密文传入；明文加密在应用层完成，领域模型只承载密文）。
     */
    public static VaultCredential create(String credentialId, String vaultId, VaultCredentialAuthType authType,
                                         String mcpServerUrl, byte[] ciphertext) {
        return createWithExpiry(credentialId, vaultId, authType, mcpServerUrl, ciphertext, null);
    }

    /**
     * 创建新的凭证（携带到期时间；OAuth 回调落库时访问令牌自带 {@code expires_in}）。
     *
     * @param expiresAt 访问令牌到期时间（null = 无到期，命中刷新判定时按「不临期」处理）
     */
    public static VaultCredential createWithExpiry(String credentialId, String vaultId,
                                                   VaultCredentialAuthType authType, String mcpServerUrl,
                                                   byte[] ciphertext, OffsetDateTime expiresAt) {
        return new VaultCredential(
                null, credentialId, vaultId, authType, mcpServerUrl, ciphertext, expiresAt, null, null,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                null, null
        );
    }

    /**
     * 从数据库恢复（查询场景）。
     */
    public static VaultCredential restore(
            Long id,
            String credentialId,
            String vaultId,
            VaultCredentialAuthType authType,
            String mcpServerUrl,
            byte[] ciphertext,
            OffsetDateTime expiresAt,
            String metadata,
            OffsetDateTime archivedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String createdBy,
            String updatedBy
    ) {
        return new VaultCredential(id, credentialId, vaultId, authType, mcpServerUrl, ciphertext,
                expiresAt, metadata, archivedAt, createdAt, updatedAt, createdBy, updatedBy);
    }

    /**
     * 凭证是否已归档（归档不再用于新 Session 挂载注入）。
     */
    public boolean archived() {
        return archivedAt != null;
    }

    /**
     * 原地更新凭证（merge 补丁语义的落点）：以<b>已解析的最终值</b>替换秘密材料密文、
     * 到期时间与元数据——三态裁决（缺省不改 / 显式 null 清除）在应用层完成，领域模型
     * 只承载结果，不做存在性判断。
     *
     * <p>身份字段（{@code authType} / {@code mcpServerUrl}）不可变：本方法不提供替换入口，
     * 归档时间与创建时间保持，更新时间刷新为当前时刻。</p>
     *
     * @param newCiphertext 新秘密材料密文（null=保持原密文）
     * @param newExpiresAt  到期时间最终值（null=无到期，即显式清除或本就无值）
     * @param newMetadata   元数据最终值（null=无元数据；显式清除由应用层收敛为 {@code "{}"}）
     */
    public VaultCredential withUpdated(byte[] newCiphertext, OffsetDateTime newExpiresAt, String newMetadata) {
        return new VaultCredential(
                id, credentialId, vaultId, authType, mcpServerUrl,
                newCiphertext == null ? this.ciphertext : newCiphertext,
                newExpiresAt, newMetadata, archivedAt, createdAt,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                createdBy, updatedBy
        );
    }

    /**
     * metadata 填值时必须是合法 JSON（空白等价于未提供），防脏数据落 JSONB 列。
     */
    private static void validateMetadataFormat(String metadata) {
        if (StringUtils.isBlank(metadata)) {
            return;
        }
        try {
            OBJECT_MAPPER.readTree(metadata);
        } catch (Exception e) {
            throw new IllegalArgumentException("凭证元数据必须是合法的JSON文本", e);
        }
    }

    /**
     * 相等性按内容比较：{@code ciphertext} 为 {@code byte[]}，record 自动生成的
     * {@code equals} / {@code hashCode} 对数组按引用（身份）比较，故显式重写以按内容等价。
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof VaultCredential that)) {
            return false;
        }
        return Objects.equals(id, that.id)
                && Objects.equals(credentialId, that.credentialId)
                && Objects.equals(vaultId, that.vaultId)
                && authType == that.authType
                && Objects.equals(mcpServerUrl, that.mcpServerUrl)
                && Arrays.equals(ciphertext, that.ciphertext)
                && Objects.equals(expiresAt, that.expiresAt)
                && Objects.equals(metadata, that.metadata)
                && Objects.equals(archivedAt, that.archivedAt)
                && Objects.equals(createdAt, that.createdAt)
                && Objects.equals(updatedAt, that.updatedAt)
                && Objects.equals(createdBy, that.createdBy)
                && Objects.equals(updatedBy, that.updatedBy);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(id, credentialId, vaultId, authType, mcpServerUrl,
                expiresAt, metadata, archivedAt, createdAt, updatedAt, createdBy, updatedBy);
        result = 31 * result + Arrays.hashCode(ciphertext);
        return result;
    }
}
