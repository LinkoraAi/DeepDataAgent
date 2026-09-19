package com.linkroa.deepdataagent.agent.controller.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * 更新运行环境请求（全量替换：name + description + config + metadata）。
 * <p>{@code name} 仅做非空校验（公开契约不设长度上限）；{@code config} 可选，
 * 缺省 {@code {"type":"cloud"}}；更新 MUST NOT 影响已运行的 Session——新配置仅对
 * 后续创建的 Session 生效。</p>
 */
public record UpdateEnvironmentRequest(

        @NotBlank(message = "运行环境名称不能为空")
        String name,

        String description,

        @Valid
        EnvironmentConfigRequest config,

        Map<String, Object> metadata
) {
}