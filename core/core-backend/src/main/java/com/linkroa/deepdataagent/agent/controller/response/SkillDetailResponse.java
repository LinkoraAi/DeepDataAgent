package com.linkroa.deepdataagent.agent.controller.response;

import java.util.List;

/**
 * 技能详情响应 DTO（技能元数据 + 全部版本，不含二进制）
 */
public record SkillDetailResponse(
        String skillId,
        List<SkillResourceResponse> versions
) {
}