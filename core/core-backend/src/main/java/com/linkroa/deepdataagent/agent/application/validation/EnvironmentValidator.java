package com.linkroa.deepdataagent.agent.application.validation;

import com.linkroa.deepdataagent.agent.domain.model.Environment;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;

/**
 * 运行环境应用级校验器
 */
public class EnvironmentValidator {

    /**
     * 校验删除冲突：仍被未删除的 Agent 版本引用时不可删除
     *
     * @param environment 目标环境
     * @param refCount    引用数量
     */
    public static void validateDelete(Environment environment, long refCount) {
        if (refCount > 0) {
            throw new ResourceConflictException("运行环境「" + environment.name()
                    + "」仍被 " + refCount + " 个 Agent 版本引用，无法删除");
        }
    }
}