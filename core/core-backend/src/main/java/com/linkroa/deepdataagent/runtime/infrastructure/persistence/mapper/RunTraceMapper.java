package com.linkroa.deepdataagent.runtime.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity.RunTraceEntity;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 链路追踪 Span Mapper。
 */
@Mapper
public interface RunTraceMapper extends BaseMapper<RunTraceEntity> {

    default List<RunTraceEntity> findByRound(String roundId) {
        return selectList(Wrappers.<RunTraceEntity>lambdaQuery()
                .eq(RunTraceEntity::getRoundId, roundId)
                .orderByAsc(RunTraceEntity::getStartTime));
    }
}