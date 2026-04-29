package com.linkroa.deepdataagent.datasource.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity.ApiFieldEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ApiFieldMapper extends BaseMapper<ApiFieldEntity> {

    List<ApiFieldEntity> selectByApiSchemaId(@Param("apiSchemaId") Long apiSchemaId);

    int deleteByApiSchemaId(@Param("apiSchemaId") Long apiSchemaId);
}
