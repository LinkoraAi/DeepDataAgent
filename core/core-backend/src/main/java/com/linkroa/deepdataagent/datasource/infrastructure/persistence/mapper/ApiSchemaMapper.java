package com.linkroa.deepdataagent.datasource.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity.ApiSchemaEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ApiSchemaMapper extends BaseMapper<ApiSchemaEntity> {

    ApiSchemaEntity selectByConnectionIdAndName(@Param("connectionId") Long connectionId, @Param("name") String name);

    List<ApiSchemaEntity> selectByConnectionId(@Param("connectionId") Long connectionId);

    int deleteByConnectionId(@Param("connectionId") Long connectionId);
}
