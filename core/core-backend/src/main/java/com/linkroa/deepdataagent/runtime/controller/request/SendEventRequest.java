package com.linkroa.deepdataagent.runtime.controller.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 发送事件请求。
 * <p>首版仅支持 {@code type=message}（用户消息）；后续可扩展 tool_call 等类型。</p>
 *
 * @param type    事件类型（message / tool_call）
 * @param content 事件内容（用户消息全文）
 */
public record SendEventRequest(
        @NotBlank(message = "事件类型不能为空")
        String type,

        @NotBlank(message = "事件内容不能为空")
        String content
) {
}