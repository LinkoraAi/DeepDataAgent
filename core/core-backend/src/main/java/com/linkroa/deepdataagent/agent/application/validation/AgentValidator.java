package com.linkroa.deepdataagent.agent.application.validation;

import com.linkroa.deepdataagent.agent.domain.model.AgentDefinition;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;

/**
 * Agent 定义/版本应用级校验器
 */
public class AgentValidator {

    /**
     * 校验 Agent 可发布新版本：归档后拒绝发布
     *
     * @param definition 目标 Agent
     */
    public static void validatePublishable(AgentDefinition definition) {
        if (definition.archived()) {
            throw new ResourceConflictException("Agent「" + definition.name() + "」已归档，无法发布新版本");
        }
    }
}