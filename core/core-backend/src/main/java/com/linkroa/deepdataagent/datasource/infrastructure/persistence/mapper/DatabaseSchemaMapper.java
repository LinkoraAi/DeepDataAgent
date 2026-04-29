package com.linkroa.deepdataagent.datasource.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity.DatabaseSchemaEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DatabaseSchemaMapper extends BaseMapper<DatabaseSchemaEntity> {

    List<DatabaseSchemaEntity> selectByConnectionId(@Param("connectionId") Long connectionId);

    int deleteByConnectionId(@Param("connectionId") Long connectionId);

    int softDeleteByConnectionId(@Param("connectionId") Long connectionId);

    DatabaseSchemaEntity selectByConnectionIdAndSchemaName(@Param("connectionId") Long connectionId,
                                                           @Param("schemaName") String schemaName);
}
