package com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.SkillResourceEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 技能资源 Mapper
 */
@Mapper
public interface SkillResourceMapper extends BaseMapper<SkillResourceEntity> {

    /**
     * 按技能 + 版本号查询单行。
     */
    @Select("""
            SELECT * FROM skill_resource
            WHERE skill_id = #{skillId} AND version_number = #{versionNumber} AND is_deleted = 0
            """)
    SkillResourceEntity selectBySkillIdAndVersion(@Param("skillId") String skillId,
                                                  @Param("versionNumber") int versionNumber);

    /**
     * 按技能查询全部版本（版本号倒序）。
     */
    @Select("""
            SELECT * FROM skill_resource
            WHERE skill_id = #{skillId} AND is_deleted = 0
            ORDER BY version_number DESC
            """)
    List<SkillResourceEntity> selectBySkillId(@Param("skillId") String skillId);

    /**
     * 按技能查询最大版本号行（不存在返回 null）。
     */
    @Select("""
            SELECT * FROM skill_resource
            WHERE skill_id = #{skillId} AND is_deleted = 0
            ORDER BY version_number DESC
            LIMIT 1
            """)
    SkillResourceEntity selectMaxVersion(@Param("skillId") String skillId);

    /**
     * 按技能锁定最大版本号行（FOR UPDATE，供发布事务内串行化版本号计算）。
     */
    @Select("""
            SELECT * FROM skill_resource
            WHERE skill_id = #{skillId} AND is_deleted = 0
            ORDER BY version_number DESC
            LIMIT 1
            FOR UPDATE
            """)
    SkillResourceEntity selectMaxVersionForUpdate(@Param("skillId") String skillId);

    /**
     * 技能列表（每个技能仅返回最新版本）：按 skill_id 分组取 MAX(version_number)，再回查该行完整信息。
     */
    @Select("""
            SELECT sr.*
            FROM skill_resource sr
            JOIN (
                SELECT skill_id, MAX(version_number) AS max_version
                FROM skill_resource
                WHERE is_deleted = 0
                GROUP BY skill_id
            ) mv ON sr.skill_id = mv.skill_id AND sr.version_number = mv.max_version
            WHERE sr.is_deleted = 0
            AND (#{keyword} IS NULL OR #{keyword} = '' OR sr.name LIKE CONCAT('%', #{keyword}, '%'))
            ORDER BY sr.created_at DESC
            LIMIT #{size} OFFSET #{offset}
            """)
    List<SkillResourceEntity> selectLatestByCondition(@Param("keyword") String keyword,
                                                      @Param("offset") long offset,
                                                      @Param("size") int size);

    /**
     * 技能列表统计（按技能去重）
     */
    @Select("""
            SELECT COUNT(1)
            FROM (
                SELECT skill_id
                FROM skill_resource
                WHERE is_deleted = 0
                AND (#{keyword} IS NULL OR #{keyword} = '' OR name LIKE CONCAT('%', #{keyword}, '%'))
                GROUP BY skill_id
            ) skills
            """)
    long countSkillsByCondition(@Param("keyword") String keyword);
}