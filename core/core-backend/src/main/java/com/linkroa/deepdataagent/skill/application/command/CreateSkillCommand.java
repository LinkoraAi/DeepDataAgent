package com.linkroa.deepdataagent.skill.application.command;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 创建技能命令（multipart 技能包上传，首个不可变版本随创建落库）。
 *
 * @param files        multipart files part（单个 {@code .zip} 压缩包或裸文件树）
 * @param displayTitle 展示名（可空 = 缺省取 zip 文件名去 {@code .zip} 后缀，再回落 frontmatter name；
 *                     创建后不可修改）
 */
public record CreateSkillCommand(
        List<MultipartFile> files,
        String displayTitle
) {
}