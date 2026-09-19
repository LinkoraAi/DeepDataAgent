package com.linkroa.deepdataagent.shared.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.entity.IdempotencyRecordEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 幂等记录 Mapper（idempotency_record 表访问器）。
 */
@Mapper
public interface IdempotencyRecordMapper extends BaseMapper<IdempotencyRecordEntity> {

    /**
     * 按 owner + 作用域 + 幂等键读取（owner 隔离，未删除行）。
     */
    default IdempotencyRecordEntity selectByKey(Long ownerId, String scope, String idempotencyKey) {
        LambdaQueryWrapper<IdempotencyRecordEntity> wrapper = Wrappers.<IdempotencyRecordEntity>lambdaQuery()
                .eq(IdempotencyRecordEntity::getOwnerId, ownerId)
                .eq(IdempotencyRecordEntity::getScope, scope)
                .eq(IdempotencyRecordEntity::getIdempotencyKey, idempotencyKey)
                .last("LIMIT 1");
        return selectOne(wrapper);
    }
}