package com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.ModelProfileEntity;
import org.apache.ibatis.annotations.Mapper;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 模型配置 Mapper
 *
 * <p>约束：LambdaQueryWrapper / LambdaUpdateWrapper 的列引用一律使用方法引用
 * （{@code ModelProfileEntity::getX}），不得写成 lambda 表达式（{@code e -> e.getX()}）——
 * 后者编译为合成方法 {@code lambda$N}，MyBatis-Plus 的 PropertyNamer 无法解析属性名，
 * 真实库运行期抛 ReflectionException。</p>
 */
@Mapper
public interface ModelProfileMapper extends BaseMapper<ModelProfileEntity> {

    default ModelProfileEntity selectByProfileId(String profileId) {
        return selectOne(Wrappers.<ModelProfileEntity>lambdaQuery()
                .eq(ModelProfileEntity::getProfileId, profileId)
                .last("LIMIT 1"));
    }

    /**
     * 按业务 ID 查询并锁定该行（FOR UPDATE），供删除等 check-then-act 场景在事务内串行化。
     * PostgreSQL READ COMMITTED 下锁定目标行，可阻止并发删除导致悬空引用。
     */
    default ModelProfileEntity selectByProfileIdForUpdate(String profileId) {
        return selectOne(Wrappers.<ModelProfileEntity>lambdaQuery()
                .eq(ModelProfileEntity::getProfileId, profileId)
                .last("FOR UPDATE"));
    }

    default ModelProfileEntity selectByDisplayName(String displayName) {
        return selectOne(Wrappers.<ModelProfileEntity>lambdaQuery()
                .eq(ModelProfileEntity::getDisplayName, displayName)
                .last("LIMIT 1"));
    }

    default List<ModelProfileEntity> selectByCondition(Long ownerId, String keyword, String status, long offset, int size) {
        return selectList(buildCondition(ownerId, keyword, status)
                .orderByAsc(ModelProfileEntity::getCreatedAt)
                .last("LIMIT " + size + " OFFSET " + offset));
    }

    default long countByCondition(Long ownerId, String keyword, String status) {
        return selectCount(buildCondition(ownerId, keyword, status));
    }

    default int updateStatus(String profileId, String status) {
        return update(null, Wrappers.<ModelProfileEntity>lambdaUpdate()
                .set(ModelProfileEntity::getStatus, status)
                .set(ModelProfileEntity::getUpdatedAt, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")))
                .eq(ModelProfileEntity::getProfileId, profileId));
    }

    private LambdaQueryWrapper<ModelProfileEntity> buildCondition(Long ownerId, String keyword, String status) {
        return Wrappers.<ModelProfileEntity>lambdaQuery()
                .eq(ModelProfileEntity::getOwnerId, ownerId)
                .like(keyword != null && !keyword.isBlank(), ModelProfileEntity::getDisplayName, keyword)
                .eq(status != null && !status.isBlank(), ModelProfileEntity::getStatus, status);
    }
}