package com.linkroa.deepdataagent.agent.application.validation;

import com.linkroa.deepdataagent.agent.infrastructure.util.Sha256Util;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import org.apache.commons.lang3.StringUtils;

/**
 * 技能上传内容校验器（内容缺失 / 空内容 / 校验值不匹配）
 */
public class SkillValidator {

    /**
     * 校验上传内容：缺失抛 IllegalStateException（500），空内容抛 IllegalArgumentException（400）
     *
     * @param content 上传内容
     */
    public static void validateContentPresent(byte[] content) {
        if (content == null) {
            throw new IllegalStateException("技能包内容缺失");
        }
    }

    /**
     * 校验内容非空
     */
    public static void validateNonEmpty(byte[] content) {
        if (content.length == 0) {
            throw new IllegalArgumentException("技能包内容不能为空");
        }
    }

    /**
     * 校验内容大小不超过上限
     *
     * @param content 上传内容
     * @param maxSize 大小上限（字节）
     */
    public static void validateMaxSize(byte[] content, long maxSize) {
        if (content.length > maxSize) {
            throw new IllegalArgumentException("技能包内容超过大小上限: " + maxSize + " 字节");
        }
    }

    /**
     * 校验客户端声明的 SHA-256 与服务端实际计算一致（不一致不落任何数据）
     *
     * @param content        上传内容
     * @param declaredSha256 客户端声明值（可空，空则跳过）
     */
    public static void validateSha256(byte[] content, String declaredSha256) {
        if (StringUtils.isBlank(declaredSha256)) {
            return;
        }
        String actual = Sha256Util.hex(content);
        if (!declaredSha256.equalsIgnoreCase(actual)) {
            throw new IllegalArgumentException("内容SHA256校验值不匹配：声明 " + declaredSha256
                    + "，实际 " + actual);
        }
    }

    /**
     * 校验技能存在（查询路径统一 404）
     */
    public static void validateExists(boolean exists, String skillId) {
        if (!exists) {
            throw new ResourceNotFoundException("技能不存在");
        }
    }
}