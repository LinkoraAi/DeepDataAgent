package com.linkroa.deepdataagent.skill.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linkroa.deepdataagent.skill.domain.model.SkillListFilter;
import com.linkroa.deepdataagent.skill.infrastructure.persistence.entity.SkillEntity;
import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 技能壳 Mapper（数据库访问器）。
 * <p>此处以 {@code @SuppressWarnings("null")} 压制 JDT 空指针静态分析对 MyBatis-Plus
 * {@code SFunction} 方法引用（{@code Entity::getXxx}）的误报：该写法是 MP lambda 包装器
 * 解析列名的标准形式，运行时与 null 语义无关。</p>
 */
@SuppressWarnings("null")
@Mapper
public interface SkillMapper extends BaseMapper<SkillEntity> {

    default SkillEntity selectBySkillId(String skillId) {
        return selectOne(Wrappers.<SkillEntity>lambdaQuery()
                .eq(SkillEntity::getSkillId, skillId)
                .last("LIMIT 1"));
    }

    /**
     * 游标分页查询技能（创建时间降序 keyset 行值比较）。
     * <p>after 方向 {@code (created_at, id) < (?,?)} 降序读取更旧页；before 方向反向比较后
     * 升序读取更新页（应用层翻转回降序）；来源 / 展示名模糊过滤可缺省。</p>
     */
    default List<SkillEntity> selectByCursor(Long ownerId, SkillListFilter filter, int limit) {
        LambdaQueryWrapper<SkillEntity> wrapper = Wrappers.<SkillEntity>lambdaQuery()
                .eq(SkillEntity::getOwnerId, ownerId)
                .eq(filter.source() != null, SkillEntity::getSource,
                        filter.source() == null ? null : filter.source().value())
                .like(StringUtils.isNotBlank(filter.keyword()), SkillEntity::getDisplayTitle, filter.keyword());
        if (filter.cursorCreatedAt() != null) {
            wrapper.apply(filter.reverse(), "(created_at, id) > ({0}, {1})",
                    filter.cursorCreatedAt(), filter.cursorRowId());
            wrapper.apply(!filter.reverse(), "(created_at, id) < ({0}, {1})",
                    filter.cursorCreatedAt(), filter.cursorRowId());
        }
        if (filter.reverse()) {
            wrapper.orderByAsc(SkillEntity::getCreatedAt).orderByAsc(SkillEntity::getId);
        } else {
            wrapper.orderByDesc(SkillEntity::getCreatedAt).orderByDesc(SkillEntity::getId);
        }
        return selectList(wrapper.last("LIMIT " + limit));
    }
}