package com.linkroa.deepdataagent.datasource.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity.DatasourceConnectionEntity;
import org.apache.ibatis.annotations.Mapper;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

@Mapper
public interface DatasourceConnectionMapper extends BaseMapper<DatasourceConnectionEntity> {

    default DatasourceConnectionEntity selectByName(String name) {
        return selectOne(Wrappers.<DatasourceConnectionEntity>lambdaQuery()
                .eq(e -> e.getName(), name)
                .last("LIMIT 1"));
    }

    default List<DatasourceConnectionEntity> selectAll() {
        return selectList(Wrappers.<DatasourceConnectionEntity>lambdaQuery()
                .orderByDesc(e -> e.getUpdatedAt()));
    }

    default List<DatasourceConnectionEntity> selectByCondition(String keyword, String type, String status,
                                                               long offset, int size) {
        return selectList(buildCondition(keyword, type, status)
                .orderByAsc(e -> e.getCreatedAt())
                .last("LIMIT " + size + " OFFSET " + offset));
    }

    default long countByCondition(String keyword, String type, String status) {
        return selectCount(buildCondition(keyword, type, status));
    }

    default int updateStatus(Long id, String status) {
        return update(null, Wrappers.<DatasourceConnectionEntity>lambdaUpdate()
                .set(e -> e.getStatus(), status)
                .set(e -> e.getUpdatedAt(), OffsetDateTime.now(ZoneId.of("Asia/Shanghai")))
                .eq(e -> e.getId(), id));
    }

    private LambdaQueryWrapper<DatasourceConnectionEntity> buildCondition(String keyword, String type, String status) {
        return Wrappers.<DatasourceConnectionEntity>lambdaQuery()
                .like(keyword != null && !keyword.isBlank(), e -> e.getName(), keyword)
                .eq(type != null && !type.isBlank(), e -> e.getType(), type)
                .eq(status != null && !status.isBlank(), e -> e.getStatus(), status);
    }
}