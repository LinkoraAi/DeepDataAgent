package com.linkroa.deepdataagent.datasource.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity.DatabaseSchemaEntity;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface DatabaseSchemaMapper extends BaseMapper<DatabaseSchemaEntity> {

    default List<DatabaseSchemaEntity> selectByConnectionId(Long connectionId) {
        return selectList(Wrappers.<DatabaseSchemaEntity>lambdaQuery()
                .eq(e -> e.getConnectionId(), connectionId)
                .orderByAsc(e -> e.getSchemaName()));
    }

    default DatabaseSchemaEntity selectByConnectionIdAndSchemaName(Long connectionId, String schemaName) {
        return selectOne(Wrappers.<DatabaseSchemaEntity>lambdaQuery()
                .eq(e -> e.getConnectionId(), connectionId)
                .eq(e -> e.getSchemaName(), schemaName)
                .last("LIMIT 1"));
    }
}