package com.linkroa.deepdataagent.knowledgebase.domain.model;

import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DedupConflictAction;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DedupMatchRule;
import org.apache.commons.lang3.ObjectUtils;

/**
 * 去重策略配置值对象（两轴模型）。
 * <p>matchRule 决定「拿什么判重」，conflictAction 决定「命中后怎么处置」。
 * 当 matchRule=NONE 时，conflictAction 不生效。</p>
 *
 * @param matchRule      判定依据
 * @param conflictAction 冲突动作
 */
public record DedupPolicyConfig(
        DedupMatchRule matchRule,
        DedupConflictAction conflictAction
) {

    /**
     * 判断去重检测是否启用。
     *
     * @return matchRule 非 NONE 时返回 true
     */
    public boolean isDetectionEnabled() {
        return matchRule != null && matchRule != DedupMatchRule.NONE;
    }

    /**
     * 校验两轴模型不变式：matchRule=NONE 时 conflictAction 无意义。
     */
    public DedupPolicyConfig {
        if (ObjectUtils.isEmpty(matchRule)) {
            throw new IllegalArgumentException("去重判定依据不能为空");
        }
        if (matchRule == DedupMatchRule.NONE) {
            // matchRule=NONE 时 conflictAction 强制为 null（不生效）
            conflictAction = null;
        }
    }
}
