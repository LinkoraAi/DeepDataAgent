package com.linkroa.deepdataagent.agent.application.validation;

import com.linkroa.deepdataagent.agent.domain.model.Environment;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;

/**
 * 运行环境应用级校验器
 */
public class EnvironmentValidator {

    /**
     * 校验删除冲突：仍被未删除的会话挂载引用时不可删除
     *
     * @param environment 目标环境
     * @param refCount    引用数量
     */
    public static void validateDelete(Environment environment, long refCount) {
        if (refCount > 0) {
            throw new ResourceConflictException("运行环境「" + environment.name()
                    + "」仍被 " + refCount + " 个会话引用，无法删除");
        }
    }

    /**
     * 校验归档冲突：仍被未删除的会话挂载引用时不可归档（已归档环境不可被新 Session 引用）
     *
     * @param environment 目标环境
     * @param refCount    引用数量
     */
    public static void validateArchive(Environment environment, long refCount) {
        if (refCount > 0) {
            throw new ResourceConflictException("运行环境「" + environment.name()
                    + "」仍被 " + refCount + " 个会话引用，无法归档");
        }
    }
}