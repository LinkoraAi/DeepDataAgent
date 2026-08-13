package com.linkroa.deepdataagent.datasource.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity.DatasourceConnectionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DatasourceConnectionMapper extends BaseMapper<DatasourceConnectionEntity> {

    DatasourceConnectionEntity selectByName(@Param("name") String name);

    List<DatasourceConnectionEntity> selectAll();

    List<DatasourceConnectionEntity> selectByCondition(@Param("keyword") String keyword,
                                                       @Param("type") String type,
                                                       @Param("status") String status,
                                                       @Param("offset") long offset,
                                                       @Param("size") int size);

    long countByCondition(@Param("keyword") String keyword,
                          @Param("type") String type,
                          @Param("status") String status);

    int updateStatus(@Param("id") Long id, @Param("status") String status, @Param("updatedAt") String updatedAt);

    DatasourceConnectionEntity selectByIdAndNotDeleted(@Param("id") Long id);

    int softDeleteById(@Param("id") Long id, @Param("updatedAt") String updatedAt);
}
