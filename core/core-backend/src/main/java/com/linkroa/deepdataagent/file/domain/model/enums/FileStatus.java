package com.linkroa.deepdataagent.file.domain.model.enums;

import org.apache.commons.lang3.StringUtils;

/**
 * 文件状态领域枚举（status 契约词汇）。
 * <p>上传成功即 {@code ready}（内容落盘 + 元数据登记完成）；
 * 会话挂载文件时 MUST 校验状态为 ready（ready 门禁）。</p>
 */
public enum FileStatus {

    /** 内容已就绪，可挂载 / 可查询 */
    READY("ready");

    /** 契约词汇（API 传输值） */
    private final String code;

    FileStatus(String code) {
        this.code = code;
    }

    /**
     * 返回 API 传输层使用的状态词汇（契约取值，非 Java 枚举名）。
     *
     * @return 契约词汇
     */
    public String code() {
        return code;
    }

    /**
     * 按契约词汇解析状态枚举。
     *
     * @param code 状态字符串
     * @return 匹配的枚举
     * @throws IllegalArgumentException 空值或非法状态
     */
    public static FileStatus fromCode(String code) {
        if (StringUtils.isBlank(code)) {
            throw new IllegalArgumentException("文件状态不能为空");
        }
        for (FileStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("文件状态非法: " + code);
    }
}
