package com.linkroa.deepdataagent.agent.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 沙箱执行规格请求（值对象协议层表示）
 */
public record SandboxSpecRequest(

        @NotBlank(message = "沙箱镜像不能为空")
        @Size(max = 255, message = "沙箱镜像标识长度不能超过255个字符")
        String image,

        @NotNull(message = "内存大小不能为空")
        Integer memoryMb,

        @NotNull(message = "CPU大小不能为空")
        Double cpu,

        @NotBlank(message = "工作目录模式不能为空")
        String workspaceMode,

        @NotNull(message = "超时时间不能为空")
        Integer timeoutSeconds
) {
}