package com.linkroa.deepdataagent.agent.application.query;

/**
 * 技能分页查询
 */
public record ListSkillQuery(
        String keyword,
        int page,
        int size
) {
}