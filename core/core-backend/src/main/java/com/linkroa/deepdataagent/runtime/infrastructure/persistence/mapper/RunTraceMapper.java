package com.linkroa.deepdataagent.runtime.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity.RunTraceEntity;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 链路追踪 Span Mapper。
 * <p>此处以 {@code @SuppressWarnings("null")} 压制 JDT 空指针静态分析对 MyBatis-Plus
 * {@code SFunction} 方法引用（{@code Entity::getXxx}）的误报：该写法是 MP lambda 包装器
 * 解析列名的标准形式（改写为普通 lambda 会导致列名解析失败），运行时与 null 语义无关。</p>
 */
@SuppressWarnings("null")
@Mapper
public interface RunTraceMapper extends BaseMapper<RunTraceEntity> {

    default List<RunTraceEntity> findByRound(String roundId) {
        return selectList(Wrappers.<RunTraceEntity>lambdaQuery()
                .eq(RunTraceEntity::getRoundId, roundId)
                .orderByAsc(RunTraceEntity::getStartTime));
    }
}