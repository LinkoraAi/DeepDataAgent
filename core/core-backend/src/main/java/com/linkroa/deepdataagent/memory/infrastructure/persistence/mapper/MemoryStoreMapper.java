package com.linkroa.deepdataagent.memory.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.memory.infrastructure.persistence.entity.MemoryStoreEntity;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 记忆库 Mapper
 */
@Mapper
public interface MemoryStoreMapper extends BaseMapper<MemoryStoreEntity> {

    default MemoryStoreEntity selectByMemoryId(String memoryId) {
        return selectOne(Wrappers.<MemoryStoreEntity>lambdaQuery()
                .eq(e -> e.getMemoryId(), memoryId)
                .last("LIMIT 1"));
    }

    default List<MemoryStoreEntity> selectByIds(List<String> memoryIds) {
        if (memoryIds == null || memoryIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<MemoryStoreEntity>lambdaQuery()
                .in(e -> e.getMemoryId(), memoryIds));
    }

    default MemoryStoreEntity selectByMemoryIdForUpdate(String memoryId) {
        return selectOne(Wrappers.<MemoryStoreEntity>lambdaQuery()
                .eq(e -> e.getMemoryId(), memoryId)
                .last("FOR UPDATE"));
    }

    default List<MemoryStoreEntity> selectPage(long offset, int size) {
        return selectList(Wrappers.<MemoryStoreEntity>lambdaQuery()
                .orderByAsc(e -> e.getCreatedAt())
                .orderByAsc(e -> e.getId())
                .last("LIMIT " + size + " OFFSET " + offset));
    }

    default long countAll() {
        return selectCount(Wrappers.<MemoryStoreEntity>lambdaQuery());
    }
}