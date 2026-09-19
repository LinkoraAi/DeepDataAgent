package com.linkroa.deepdataagent.vault.controller.response;

import com.linkroa.deepdataagent.vault.application.dto.OAuthCallbackResultDTO;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OAuth 回调端点响应体（HTML 页面，非 JSON 契约）。
 *
 * <p>回调由浏览器跳转触发，终点必须是可渲染页面：页面以 {@code oauth_callback} 消息把
 * 「授权完成、在哪个保管库下建了哪条凭证」回传 opener，随后关闭自身。消息目标源取 state
 * 记录的发起来源（origin 校验），<b>不得以 {@code *} 广播</b>。</p>
 *
 * <p>页面只承载标识（保管库 / 凭证业务 ID），不含任何令牌材料；内联 JSON 经 HTML 敏感字符
 * 转义，避免标识内容突破 {@code <script>} 边界。</p>
 */
public final class OAuthCallbackPage {

    /** 回调消息类型（前端以此识别授权完成）。 */
    private static final String MESSAGE_TYPE = "oauth_callback";

    /** 回调成功状态标识。 */
    private static final String STATUS_SUCCESS = "success";

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

    private OAuthCallbackPage() {
    }

    /**
     * 渲染授权完成页面。
     *
     * @param result 回调落库结果（保管库 / 凭证标识 + 消息目标源）
     * @return HTML 文档
     */
    public static String render(OAuthCallbackResultDTO result) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", MESSAGE_TYPE);
        message.put("status", STATUS_SUCCESS);
        message.put("vault_id", result.vaultId());
        message.put("credential_id", result.credentialId());
        return document(inlineLiteral(message), inlineLiteral(result.origin()));
    }

    /** 内联进 {@code <script>} 的字面量：JSON 编码后转义 HTML 敏感字符（不突破脚本边界）。 */
    private static String inlineLiteral(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value)
                    .replace("<", "\\u003c")
                    .replace(">", "\\u003e")
                    .replace("&", "\\u0026");
        } catch (Exception e) {
            throw new IllegalStateException("OAuth 回调页面消息序列化失败: " + e.getMessage(), e);
        }
    }

    /** 页面骨架：回传消息给 opener → 关闭窗口。 */
    private static String document(String messageJson, String targetOriginJson) {
        return "<!DOCTYPE html>\n"
                + "<html lang=\"zh-CN\">\n"
                + "<head><meta charset=\"UTF-8\"><title>MCP OAuth 授权</title></head>\n"
                + "<body>\n"
                + "<p>授权已完成，正在返回…</p>\n"
                + "<script>\n"
                + "window.opener && window.opener.postMessage("
                + messageJson + ", " + targetOriginJson + ");\n"
                + "window.close();\n"
                + "</script>\n"
                + "</body>\n"
                + "</html>\n";
    }
}