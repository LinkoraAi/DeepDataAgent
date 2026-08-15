package com.linkroa.deepdataagent.datasource.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity.ColumnInfoEntity;
import org.apache.ibatis.annotations.Mapper;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

@Mapper
public interface ColumnInfoMapper extends BaseMapper<ColumnInfoEntity> {

    default List<ColumnInfoEntity> selectByTableId(Long tableId) {
        return selectList(Wrappers.<ColumnInfoEntity>lambdaQuery()
                .eq(e -> e.getTableId(), tableId)
                .orderByAsc(e -> e.getId()));
    }

    default int updateColumnCustomComment(Long id, String columnCustomComment) {
        return update(null, Wrappers.<ColumnInfoEntity>lambdaUpdate()
                .set(e -> e.getColumnCustomComment(), columnCustomComment)
                .set(e -> e.getUpdatedAt(), OffsetDateTime.now(ZoneId.of("Asia/Shanghai")))
                .eq(e -> e.getId(), id));
    }
}