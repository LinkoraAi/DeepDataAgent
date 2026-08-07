package com.linkroa.deepdataagent.agent.application.command;

import jakarta.validation.constraints.NotBlank;

/**
 * 数据分析命令
 * <p>当 {@code resumeOnly} 为 true 时仅用于续流运行中会话，只需 {@code sessionId}；
 * 其余参数字段由服务层在非续流路径校验必填。enableWebSearch 与 resumeOnly 均使用可空 {@link Boolean}，
 * 由服务层以 {@code Boolean.TRUE.equals(...)} 兜底解析。</p>
 */
public record DataAnalysisCommand(
    @NotBlank(message = "会话 ID 不能为空")
    String sessionId,
    Long modelConfigId,
    String connectionId,
    String userQuestion,
    Boolean enableWebSearch,
    Boolean resumeOnly
) {}