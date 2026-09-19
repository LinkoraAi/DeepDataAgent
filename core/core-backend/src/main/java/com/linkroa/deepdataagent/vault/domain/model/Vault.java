package com.linkroa.deepdataagent.vault.domain.model;

import org.apache.commons.lang3.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.regex.Pattern;

/**
 * 保管库领域模型（Vault 聚合根，对应 vaults 表）。
 * <p>两层级模型的上层：承载 {@link VaultCredential}（凭证）的容器，仅以 {@link #ownerId()}
 * 隔离归属；归档（软删）置 {@link #archivedAt()}，硬删由应用服务级联删除其下全部凭证。</p>
 *
 * @param id          数据库主键
 * @param vaultId     保管库业务唯一ID（前缀 vault_）
 * @param displayName 显示名称（必填，≤255字符，与公开契约及 V1 列宽一致）
 * @param metadata    元数据 JSON（可选，有效 JSON，≤4000字符；JSONB 落库）
 * @param ownerId     归属用户 ID（数字）
 * @param archivedAt  归档时间（软删，null=未归档）
 * @param createdAt   创建时间
 * @param updatedAt   更新时间
 * @param createdBy   创建人
 * @param updatedBy   更新人
 */
public record Vault(
        Long id,
        String vaultId,
        String displayName,
        String metadata,
        Long ownerId,
        OffsetDateTime archivedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String createdBy,
        String updatedBy
) {

    private static final int MAX_DISPLAY_NAME_LENGTH = 255;
    private static final Pattern NAME_PATTERN = Pattern.compile("^[\\p{IsHan}a-zA-Z][\\p{IsHan}a-zA-Z0-9_\\-]{0,254}$");
    private static final int MAX_METADATA_LENGTH = 4000;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 紧凑构造器：不变量校验（名称格式 / metadata 合法性 / owner 必填）。
     */
    public Vault {
        if (StringUtils.isBlank(vaultId)) {
            throw new IllegalArgumentException("保管库ID不能为空");
        }
        if (StringUtils.isBlank(displayName)) {
            throw new IllegalArgumentException("保管库显示名称不能为空");
        }
        if (displayName.length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new IllegalArgumentException("保管库显示名称长度不能超过" + MAX_DISPLAY_NAME_LENGTH + "个字符");
        }
        if (!NAME_PATTERN.matcher(displayName).matches()) {
            throw new IllegalArgumentException("保管库显示名称只能包含中文、英文字母、数字、下划线和连字符，且不能以数字或特殊字符开头");
        }
        if (metadata != null && metadata.length() > MAX_METADATA_LENGTH) {
            throw new IllegalArgumentException("保管库元数据长度不能超过" + MAX_METADATA_LENGTH + "个字符");
        }
        validateMetadataFormat(metadata);
        if (ownerId == null) {
            throw new IllegalArgumentException("保管库归属用户不能为空");
        }
    }

    /**
     * 创建新的保管库（未归档）。
     */
    public static Vault create(String vaultId, String displayName, String metadata, Long ownerId) {
        return new Vault(
                null, vaultId, displayName, metadata, ownerId, null,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                null, null
        );
    }

    /**
     * 从数据库恢复（查询场景）。
     */
    public static Vault restore(
            Long id,
            String vaultId,
            String displayName,
            String metadata,
            Long ownerId,
            OffsetDateTime archivedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String createdBy,
            String updatedBy
    ) {
        return new Vault(id, vaultId, displayName, metadata, ownerId, archivedAt, createdAt, updatedAt, createdBy, updatedBy);
    }

    /**
     * 归档（软删）：返回置 archived_at 的副本。
     */
    public Vault archive() {
        return new Vault(id, vaultId, displayName, metadata, ownerId,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                createdAt, updatedAt, createdBy, updatedBy);
    }

    /**
     * 是否已归档。
     */
    public boolean archived() {
        return archivedAt != null;
    }

    /**
     * metadata 填值时必须是合法 JSON（空串等价于未提供），防脏数据落 JSONB 列。
     */
    private static void validateMetadataFormat(String metadata) {
        if (StringUtils.isBlank(metadata)) {
            return;
        }
        try {
            OBJECT_MAPPER.readTree(metadata);
        } catch (Exception e) {
            throw new IllegalArgumentException("保管库元数据必须是合法的JSON文本", e);
        }
    }
}