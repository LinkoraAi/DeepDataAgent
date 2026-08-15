package com.linkroa.deepdataagent.datasource.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity.ApiFieldEntity;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ApiFieldMapper extends BaseMapper<ApiFieldEntity> {

    default List<ApiFieldEntity> selectByApiSchemaId(Long apiSchemaId) {
        return selectList(Wrappers.<ApiFieldEntity>lambdaQuery()
                .eq(e -> e.getApiSchemaId(), apiSchemaId)
                .orderByAsc(e -> e.getId()));
    }
}