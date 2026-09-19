package com.linkroa.deepdataagent.skill.controller.response;

import java.util.List;

/**
 * 技能详情响应（壳对象 + 全部版本信息）。
 *
 * @param skill    技能壳
 * @param versions 内容版本列表（版本键倒序，最新在前）
 */
public record SkillDetailResponse(
        SkillResponse skill,
        List<SkillVersionResponse> versions
) {
}