package com.linkroa.deepdataagent.vault.application.dto;

/**
 * 诊断用外部 HTTP 响应（仅进程内，永不过网络，见 design D4）。
 *
 * <p>校验诊断会把第三方响应体带回给用户，这是唯一一处「外部内容进入我方响应」的地方，
 * 故响应体在离开出网执行器时即已按形态脱敏并截断（{@code body} 有长度上限，
 * {@code bodyTruncated} 标记是否被截断）。</p>
 *
 * @param statusCode    HTTP 状态码
 * @param contentType   响应 Content-Type（缺失为 null）
 * @param body          已脱敏 + 已截断的响应体（缺失为 null）
 * @param bodyTruncated 响应体是否因超长被截断
 */
public record HttpDiagnosticDTO(
        int statusCode,
        String contentType,
        String body,
        boolean bodyTruncated
) {
}