package com.linkroa.deepdataagent.datasource.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity.ApiSchemaEntity;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ApiSchemaMapper extends BaseMapper<ApiSchemaEntity> {

    default ApiSchemaEntity selectByConnectionIdAndName(Long connectionId, String name) {
        return selectOne(Wrappers.<ApiSchemaEntity>lambdaQuery()
                .eq(e -> e.getConnectionId(), connectionId)
                .eq(e -> e.getName(), name)
                .last("LIMIT 1"));
    }

    default List<ApiSchemaEntity> selectByConnectionId(Long connectionId) {
        return selectList(Wrappers.<ApiSchemaEntity>lambdaQuery()
                .eq(e -> e.getConnectionId(), connectionId)
                .orderByAsc(e -> e.getName()));
    }
}