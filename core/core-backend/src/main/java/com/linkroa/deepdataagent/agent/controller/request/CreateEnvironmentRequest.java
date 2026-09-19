package com.linkroa.deepdataagent.agent.controller.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * 创建运行环境请求（对外形状：name + description + config + metadata）。
 * <p>{@code name} 仅做非空校验（公开契约不设长度上限）；{@code description} 可选，
 * 缺省空串；{@code config} 可选，缺省 {@code {"type":"cloud"}}；{@code metadata} 为
 * 可选 key/value 对象。</p>
 */
public record CreateEnvironmentRequest(

        @NotBlank(message = "运行环境名称不能为空")
        String name,

        String description,

        @Valid
        EnvironmentConfigRequest config,

        Map<String, Object> metadata
) {
}