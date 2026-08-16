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
 */
@Mapper
public interface ModelProfileMapper extends BaseMapper<ModelProfileEntity> {

    default ModelProfileEntity selectByProfileId(String profileId) {
        return selectOne(Wrappers.<ModelProfileEntity>lambdaQuery()
                .eq(e -> e.getProfileId(), profileId)
                .last("LIMIT 1"));
    }

    /**
     * 按业务 ID 查询并锁定该行（FOR UPDATE），供删除等 check-then-act 场景在事务内串行化。
     * PostgreSQL READ COMMITTED 下锁定目标行，可阻止并发删除导致悬空引用。
     */
    default ModelProfileEntity selectByProfileIdForUpdate(String profileId) {
        return selectOne(Wrappers.<ModelProfileEntity>lambdaQuery()
                .eq(e -> e.getProfileId(), profileId)
                .last("FOR UPDATE"));
    }

    default ModelProfileEntity selectByDisplayName(String displayName) {
        return selectOne(Wrappers.<ModelProfileEntity>lambdaQuery()
                .eq(e -> e.getDisplayName(), displayName)
                .last("LIMIT 1"));
    }

    default List<ModelProfileEntity> selectByCondition(String keyword, String status, long offset, int size) {
        return selectList(buildCondition(keyword, status)
                .orderByAsc(e -> e.getCreatedAt())
                .last("LIMIT " + size + " OFFSET " + offset));
    }

    default long countByCondition(String keyword, String status) {
        return selectCount(buildCondition(keyword, status));
    }

    default int updateStatus(String profileId, String status) {
        return update(null, Wrappers.<ModelProfileEntity>lambdaUpdate()
                .set(e -> e.getStatus(), status)
                .set(e -> e.getUpdatedAt(), OffsetDateTime.now(ZoneId.of("Asia/Shanghai")))
                .eq(e -> e.getProfileId(), profileId));
    }

    private LambdaQueryWrapper<ModelProfileEntity> buildCondition(String keyword, String status) {
        return Wrappers.<ModelProfileEntity>lambdaQuery()
                .like(keyword != null && !keyword.isBlank(), e -> e.getDisplayName(), keyword)
                .eq(status != null && !status.isBlank(), e -> e.getStatus(), status);
    }
}