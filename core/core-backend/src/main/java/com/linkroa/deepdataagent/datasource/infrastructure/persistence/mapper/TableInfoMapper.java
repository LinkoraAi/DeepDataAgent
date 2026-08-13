package com.linkroa.deepdataagent.datasource.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity.TableInfoEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TableInfoMapper extends BaseMapper<TableInfoEntity> {

    List<TableInfoEntity> selectByDatabaseSchemaId(@Param("databaseSchemaId") Long databaseSchemaId);

    int updateTableCustomComment(@Param("id") Long id, @Param("tableCustomComment") String tableCustomComment);

    int softDeleteByDatabaseSchemaId(@Param("databaseSchemaId") Long databaseSchemaId);

    int softDeleteById(@Param("id") Long id);

    List<TableInfoEntity> selectByDatabaseSchemaIdAndKeyword(@Param("databaseSchemaId") Long databaseSchemaId,
                                                              @Param("keyword") String keyword,
                                                              @Param("offset") long offset,
                                                              @Param("size") int size);

    long countByDatabaseSchemaIdAndKeyword(@Param("databaseSchemaId") Long databaseSchemaId,
                                           @Param("keyword") String keyword);

    TableInfoEntity selectByDatabaseSchemaIdAndTableName(@Param("databaseSchemaId") Long databaseSchemaId,
                                                         @Param("tableName") String tableName);
}
