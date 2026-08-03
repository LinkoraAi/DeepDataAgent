package com.linkroa.deepdataagent.agent.controller.request;

/**
 * 更新模型配置请求
 * <p>支持更新 API Key 和 base_url，非空字段才会覆盖现有值。
 * 配置 ID 通过 URL 路径参数传递，不包含在请求体中。</p>
 */
public record UpdateModelConfigRequest(
    /** API Key（加密存储，留空表示不修改） */
    String apiKey,

    /** API 基础地址（留空表示不修改） */
    String baseUrl
) {}
