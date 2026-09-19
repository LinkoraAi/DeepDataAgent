package com.linkroa.deepdataagent.skill.application.command;

import org.apache.commons.lang3.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 技能发版命令（multipart 技能包上传，生成新的不可变 epoch 微秒版本）。
 * <p>发版包 frontmatter name 必须与首版一致（跨版本名称一致规则在应用服务裁决）。</p>
 *
 * @param skillId 技能业务 ID
 * @param files   multipart files part（单个 {@code .zip} 压缩包或裸文件树）
 */
public record CreateSkillVersionCommand(
        String skillId,
        List<MultipartFile> files
) {

    /**
     * 紧凑构造器：技能 ID 非空校验。
     *
     * @throws IllegalArgumentException 技能 ID 为空
     */
    public CreateSkillVersionCommand {
        if (StringUtils.isBlank(skillId)) {
            throw new IllegalArgumentException("技能ID不能为空");
        }
    }
}