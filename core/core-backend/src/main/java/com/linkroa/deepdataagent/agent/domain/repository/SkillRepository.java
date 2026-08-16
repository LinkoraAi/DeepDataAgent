package com.linkroa.deepdataagent.agent.domain.repository;

import com.linkroa.deepdataagent.agent.domain.model.SkillResource;

import java.util.List;
import java.util.Optional;

/**
 * 技能资源仓储接口（每版本一行）
 */
public interface SkillRepository {

    /**
     * 保存技能版本（新增）
     */
    SkillResource save(SkillResource skillResource);

    /**
     * 按 技能ID + 版本号 查询
     */
    Optional<SkillResource> findBySkillIdAndVersion(String skillId, int versionNumber);

    /**
     * 查询某技能的全部版本（按版本号升序）
     */
    List<SkillResource> listBySkillId(String skillId);

    /**
     * 查询某技能当前最大版本号（无版本时为 0）
     */
    int findMaxVersionNumber(String skillId);

    /**
     * 查询并锁定某技能的最大版本行（发布事务内串行化 MAX+1 计算，无版本时为空）
     */
    Optional<SkillResource> findMaxVersionForUpdate(String skillId);

    /**
     * 技能列表（每个技能仅返回最新版本一行）
     */
    List<SkillResource> findLatestByCondition(String keyword, int page, int size);

    /**
     * 技能列表统计（按技能去重）
     */
    long countSkillsByCondition(String keyword);

    /**
     * 逻辑删除某技能的全部版本
     */
    void deleteBySkillId(String skillId);
}