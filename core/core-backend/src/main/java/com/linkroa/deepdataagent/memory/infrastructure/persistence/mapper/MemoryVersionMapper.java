package com.linkroa.deepdataagent.memory.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.memory.infrastructure.persistence.entity.MemoryVersionEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 记忆版本 Mapper（不可变快照 + 版本级 redact）。
 */
@Mapper
public interface MemoryVersionMapper extends BaseMapper<MemoryVersionEntity> {

    default MemoryVersionEntity selectByVersionId(String versionId) {
        return selectOne(Wrappers.<MemoryVersionEntity>lambdaQuery()
                .eq(MemoryVersionEntity::getVersionId, versionId)
                .last("LIMIT 1"));
    }

    default MemoryVersionEntity selectByVersionIdForUpdate(String versionId) {
        return selectOne(Wrappers.<MemoryVersionEntity>lambdaQuery()
                .eq(MemoryVersionEntity::getVersionId, versionId)
                .last("LIMIT 1 FOR UPDATE"));
    }

    /**
     * 按版本号升序（时间正序）列出条目全部版本。
     * <p>兑现版本历史「时间正序」读取契约（集成测试 D6）；删除后的历史含 deleted 墓碑行，
     * 仍经此查询可见（版本表不随条目物理删除）。</p>
     *
     * @param entryId 记忆条目业务 ID
     * @return 版本实体列表（version 升序）
     */
    default List<MemoryVersionEntity> selectByEntryIdOrderByVersionAsc(String entryId) {
        return selectList(Wrappers.<MemoryVersionEntity>lambdaQuery()
                .eq(MemoryVersionEntity::getEntryId, entryId)
                .orderByAsc(MemoryVersionEntity::getVersion));
    }

    default MemoryVersionEntity selectByEntryIdAndVersion(String entryId, int version) {
        return selectOne(Wrappers.<MemoryVersionEntity>lambdaQuery()
                .eq(MemoryVersionEntity::getEntryId, entryId)
                .eq(MemoryVersionEntity::getVersion, version)
                .last("LIMIT 1"));
    }

    /** 版本级脱敏（清除内容与校验值、置脱敏标记；幂等由 redacted=FALSE 条件保证）。 */
    @Update("UPDATE memory_versions SET content = NULL, content_sha256 = NULL, "
            + "redacted = TRUE, redacted_at = #{redactedAt} "
            + "WHERE version_id = #{versionId} AND redacted = FALSE")
    int redactByVersionId(@Param("versionId") String versionId, @Param("redactedAt") OffsetDateTime redactedAt);

    /** 硬删某记忆库全部版本历史（删除记忆库级联用）。 */
    @Delete("DELETE FROM memory_versions WHERE store_id = #{storeId}")
    int deleteByStoreId(@Param("storeId") String storeId);
}
