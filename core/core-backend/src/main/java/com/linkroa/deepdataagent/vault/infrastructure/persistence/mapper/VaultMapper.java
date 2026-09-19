package com.linkroa.deepdataagent.vault.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.vault.domain.model.VaultListFilter;
import com.linkroa.deepdataagent.vault.infrastructure.persistence.entity.VaultEntity;
import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 保管库 Mapper（数据库访问器，MyBatis-Plus）。
 */
@Mapper
public interface VaultMapper extends BaseMapper<VaultEntity> {

    default VaultEntity selectByVaultId(String vaultId) {
        return selectOne(Wrappers.<VaultEntity>lambdaQuery()
                .eq(VaultEntity::getVaultId, vaultId)
                .last("LIMIT 1"));
    }

    default VaultEntity selectByVaultIdForUpdate(String vaultId) {
        return selectOne(Wrappers.<VaultEntity>lambdaQuery()
                .eq(VaultEntity::getVaultId, vaultId)
                .last("FOR UPDATE"));
    }

    /**
     * 按 owner + 业务 ID 批量查询（owner 隔离 + 排除已归档，供运行时解密注入防越权）。
     */
    default List<VaultEntity> selectByIds(Long ownerId, List<String> vaultIds) {
        if (vaultIds == null || vaultIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<VaultEntity>lambdaQuery()
                .eq(VaultEntity::getOwnerId, ownerId)
                .isNull(VaultEntity::getArchivedAt)
                .in(VaultEntity::getVaultId, vaultIds));
    }

    /**
     * 游标分页查询（owner 隔离；名称模糊 + metadata JSONB 包含过滤；归档态三态过滤；
     * {@code (created_at, id)} keyset，默认创建时间降序，reverse=before 方向升序读取）。
     */
    default List<VaultEntity> selectByCursor(Long ownerId, VaultListFilter filter, int limit) {
        LambdaQueryWrapper<VaultEntity> wrapper = filterWrapper(ownerId, filter);
        if (filter.cursorCreatedAt() != null && filter.cursorRowId() != null) {
            if (filter.reverse()) {
                wrapper.apply("(created_at, id) > ({0}, {1})",
                        filter.cursorCreatedAt(), filter.cursorRowId());
                wrapper.orderByAsc(VaultEntity::getCreatedAt).orderByAsc(VaultEntity::getId);
            } else {
                wrapper.apply("(created_at, id) < ({0}, {1})",
                        filter.cursorCreatedAt(), filter.cursorRowId());
                wrapper.orderByDesc(VaultEntity::getCreatedAt).orderByDesc(VaultEntity::getId);
            }
        } else if (filter.reverse()) {
            wrapper.orderByAsc(VaultEntity::getCreatedAt).orderByAsc(VaultEntity::getId);
        } else {
            wrapper.orderByDesc(VaultEntity::getCreatedAt).orderByDesc(VaultEntity::getId);
        }
        return selectList(wrapper.last("LIMIT " + limit));
    }

    /**
     * 统计满足过滤条件的保管库总数（搜索端点首页 {@code total}；游标位置不参与条件）。
     */
    default long countByFilter(Long ownerId, VaultListFilter filter) {
        return selectCount(filterWrapper(ownerId, filter));
    }

    /** 过滤条件装配（owner 隔离 + 名称模糊 + metadata JSONB 包含 + 归档态三态）。 */
    @SuppressWarnings("null")
    private static LambdaQueryWrapper<VaultEntity> filterWrapper(Long ownerId, VaultListFilter filter) {
        LambdaQueryWrapper<VaultEntity> wrapper = Wrappers.<VaultEntity>lambdaQuery()
                .eq(VaultEntity::getOwnerId, ownerId);
        if (filter.archived() != null) {
            if (filter.archived()) {
                wrapper.isNotNull(VaultEntity::getArchivedAt);
            } else {
                wrapper.isNull(VaultEntity::getArchivedAt);
            }
        }
        if (StringUtils.isNotBlank(filter.name())) {
            // 显示名称模糊匹配（不区分大小写；通配符按字面量转义，避免用户输入改变匹配语义）
            wrapper.apply("lower(display_name) like lower({0})", likePattern(filter.name().trim()));
        }
        if (StringUtils.isNotBlank(filter.metadataJson())) {
            wrapper.apply("metadata_json @> cast({0} as jsonb)", filter.metadataJson());
        }
        return wrapper;
    }

    /** 模糊匹配模式（前后通配 + {@code \ % _} 字面量转义，PostgreSQL LIKE 默认以反斜杠为转义符）。 */
    static String likePattern(String raw) {
        return "%" + raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    /** 归档（软删）：置 archived_at（仅限未逻辑删除行），并刷新审计时间戳（自定义 SQL 绕过 MetaObjectHandler，需手动带 updated_at）。 */
    @Update("UPDATE vaults SET archived_at = #{archivedAt}, updated_at = now() WHERE vault_id = #{vaultId} AND is_deleted = 0")
    int updateArchivedAt(@Param("vaultId") String vaultId, @Param("archivedAt") OffsetDateTime archivedAt);
}