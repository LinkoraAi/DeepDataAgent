package com.linkroa.deepdataagent.skill.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.skill.infrastructure.persistence.entity.SkillContentVersionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 技能内容版本 Mapper（数据库访问器）。
 * <p>版本键为 epoch 微秒字符串，排序语义依赖「等长十进制串字典序 == 数值序」这一约定。</p>
 */
@Mapper
public interface SkillContentVersionMapper extends BaseMapper<SkillContentVersionEntity> {

    /**
     * 物理存在性查询（<b>绕过逻辑删除过滤</b>）：统计包含 {@code is_deleted=1} 在内的物理行数。
     * <p>MyBatis-Plus 内置方法自动追加 {@code is_deleted=0}，无法用于「已逻辑删除版本的内容
     * 对象法定保留」对账口径，故以自定义 {@code @Select} 直查；供启动孤儿对象对账判定
     * （仅物理行从不存在的前缀才回收）。</p>
     *
     * @param skillId 技能业务 ID
     * @param version 版本键（epoch 微秒字符串）
     * @return 物理行数（0 / 1，业务上 (skill_id, version) 唯一）
     */
    @Select("SELECT COUNT(1) FROM skill_content_version WHERE skill_id = #{skillId} AND version = #{version}")
    int countPhysicalBySkillIdAndVersion(@Param("skillId") String skillId, @Param("version") String version);

    default List<SkillContentVersionEntity> selectBySkillId(String skillId) {
        return selectList(Wrappers.<SkillContentVersionEntity>lambdaQuery()
                .eq(SkillContentVersionEntity::getSkillId, skillId)
                .orderByDesc(SkillContentVersionEntity::getVersion));
    }

    default SkillContentVersionEntity selectBySkillIdAndVersion(String skillId, String version) {
        return selectOne(Wrappers.<SkillContentVersionEntity>lambdaQuery()
                .eq(SkillContentVersionEntity::getSkillId, skillId)
                .eq(SkillContentVersionEntity::getVersion, version)
                .last("LIMIT 1"));
    }

    default SkillContentVersionEntity selectLatestVersion(String skillId) {
        return selectOne(Wrappers.<SkillContentVersionEntity>lambdaQuery()
                .eq(SkillContentVersionEntity::getSkillId, skillId)
                .orderByDesc(SkillContentVersionEntity::getVersion)
                .last("LIMIT 1"));
    }

    default SkillContentVersionEntity selectFirstVersion(String skillId) {
        return selectOne(Wrappers.<SkillContentVersionEntity>lambdaQuery()
                .eq(SkillContentVersionEntity::getSkillId, skillId)
                .orderByAsc(SkillContentVersionEntity::getVersion)
                .last("LIMIT 1"));
    }
}