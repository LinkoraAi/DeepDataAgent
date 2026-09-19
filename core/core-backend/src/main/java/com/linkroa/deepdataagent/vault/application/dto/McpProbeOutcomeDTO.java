package com.linkroa.deepdataagent.vault.application.dto;

import org.apache.commons.lang3.StringUtils;

/**
 * MCP 探测调用的结果（仅进程内，由 {@code McpInitializeProbePort} 返回，见 design D4）。
 *
 * @param method       本次探测发起的 MCP 调用名（失败诊断回传该名，如 {@code initialize}）
 * @param httpResponse 收到的 HTTP 响应（null = 未收到任何响应：连接错误 / 超时 / 超出信任边界）
 */
public record McpProbeOutcomeDTO(
        String method,
        HttpDiagnosticDTO httpResponse
) {

    /**
     * 紧凑构造器：探测调用名必填（失败诊断必须能指明失败的调用）。
     */
    public McpProbeOutcomeDTO {
        if (StringUtils.isBlank(method)) {
            throw new IllegalArgumentException("探测调用名不能为空");
        }
    }
}