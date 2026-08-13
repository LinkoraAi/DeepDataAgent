package com.linkroa.deepdataagent.datasource.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity.ColumnInfoEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ColumnInfoMapper extends BaseMapper<ColumnInfoEntity> {

    List<ColumnInfoEntity> selectByTableId(@Param("tableId") Long tableId);

    int updateColumnCustomComment(@Param("id") Long id, @Param("columnCustomComment") String columnCustomComment);

    int softDeleteByTableId(@Param("tableId") Long tableId);

    int softDeleteById(@Param("id") Long id);
}
