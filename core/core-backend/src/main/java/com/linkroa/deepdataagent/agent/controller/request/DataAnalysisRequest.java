package com.linkroa.deepdataagent.agent.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 数据分析请求 DTO
 * <p>当 {@code resumeOnly} 为 true 时，仅用于续流运行中会话，只需 {@code sessionId} 与 {@code clientId}，
 * 其余分析参数字段（modelConfigId/connectionId/text/enableWebSearch）可省略；
 * 由服务层校验非续流必填参数。enableWebSearch 与 resumeOnly 均使用可空 {@link Boolean}，
 * 续流省略 enableWebSearch、正常启动省略 resumeOnly 时均为 null，不触发反序列化异常。</p>
 */
public record DataAnalysisRequest(
    @NotBlank(message = "会话 ID 不能为空")
    String sessionId,
    Long modelConfigId,
    String connectionId,
    @Size(max = 5000, message = "用户问题长度不能超过 5000 字符")
    String userQuestion,
    Boolean enableWebSearch,
    Boolean resumeOnly,
    @NotBlank(message = "客户端 ID 不能为空")
    String clientId
) {}