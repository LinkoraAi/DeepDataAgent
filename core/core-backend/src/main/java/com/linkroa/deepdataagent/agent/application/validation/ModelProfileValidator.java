package com.linkroa.deepdataagent.agent.application.validation;

import com.linkroa.deepdataagent.agent.domain.model.ModelProfile;
import com.linkroa.deepdataagent.agent.domain.model.enums.ModelProfileStatus;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;

/**
 * 模型配置应用级校验器
 */
public class ModelProfileValidator {

    /**
     * 校验删除冲突：仍被未删除的 Agent 版本引用时不可删除
     *
     * @param profile  目标配置
     * @param refCount 引用数量
     */
    public static void validateDelete(ModelProfile profile, long refCount) {
        if (refCount > 0) {
            throw new ResourceConflictException("模型配置「" + profile.displayName()
                    + "」仍被 " + refCount + " 个 Agent 版本引用，无法删除");
        }
    }

    /**
     * 校验引用合法性：配置存在且状态为 ENABLED 才可被新 Agent 版本引用
     *
     * @param profile 被引用配置
     */
    public static void validateReferable(ModelProfile profile) {
        if (profile == null) {
            throw new ResourceNotFoundException("模型配置不存在");
        }
        if (profile.status() == ModelProfileStatus.DISABLED) {
            throw new ResourceNotFoundException("模型配置已禁用，不可被新引用");
        }
    }
}