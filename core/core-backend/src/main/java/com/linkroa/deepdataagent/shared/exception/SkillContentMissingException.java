package com.linkroa.deepdataagent.shared.exception;

/**
 * 技能内容缺失异常（对应 HTTP 500）。
 * <p>技能版本记录存在但实际存储内容缺失（存储损坏 / 物理文件被删），
 * 属于数据一致性问题，返回 500 而非空或损坏内容。</p>
 */
public class SkillContentMissingException extends RuntimeException {

    public SkillContentMissingException(String message) {
        super(message);
    }

    public SkillContentMissingException(String message, Throwable cause) {
        super(message, cause);
    }
}