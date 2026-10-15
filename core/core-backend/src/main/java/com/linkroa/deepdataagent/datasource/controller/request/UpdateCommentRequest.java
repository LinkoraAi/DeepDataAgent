package com.linkroa.deepdataagent.datasource.controller.request;

import jakarta.validation.constraints.Size;

/**
 * 表 / 字段自定义注释更新请求体。
 *
 * @param comment 注释内容（0-150 字符）
 * @author DeepDataAgent
 */
public record UpdateCommentRequest(
        @Size(max = 150, message = "注释内容不能超过150个字符")
        String comment
) {
}