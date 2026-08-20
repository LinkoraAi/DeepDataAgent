package com.linkroa.deepdataagent.agent.application.validation;

import com.linkroa.deepdataagent.agent.domain.model.AgentVersion;
import com.linkroa.deepdataagent.agent.domain.repository.SkillRepository;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import org.apache.commons.lang3.StringUtils;

/**
 * 技能挂载引用应用级校验器。
 * <p>发布 / 创建 Agent 版本时校验挂载的每个（skillId + version）均存在，
 * 任一缺失即拒绝（不产生数据变更），对齐 {@code agent/skills} 规格
 * 「挂载不存在的技能或版本 → 校验错误」。</p>
 */
public final class SkillMountValidator {

    private SkillMountValidator() {
    }

    /**
     * 校验技能挂载引用合法：每个引用（skillId + version）在技能仓储中存在。
     *
     * @param skillIds        挂载技能 JSON（[{skillId, version}]，可为空）
     * @param skillRepository 技能仓储
     */
    public static void validateReferable(String skillIds, SkillRepository skillRepository) {
        for (AgentVersion.SkillRef ref : AgentVersion.parseSkillRefs(skillIds)) {
            if (StringUtils.isBlank(ref.skillId())) {
                throw new ResourceNotFoundException("技能引用缺少 skillId");
            }
            if (ref.version() == null || ref.version() < 1) {
                throw new ResourceNotFoundException("技能「" + ref.skillId() + "」版本引用非法");
            }
            if (skillRepository.findBySkillIdAndVersion(ref.skillId(), ref.version()).isEmpty()) {
                throw new ResourceNotFoundException("技能「" + ref.skillId() + "」版本 " + ref.version() + " 不存在");
            }
        }
    }
}