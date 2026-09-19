package com.linkroa.deepdataagent.agent.controller.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * Agent 配置请求（创建与发布版本复用同一请求体「配置即版本」）。
 * <p>模型引用遵循模型目录契约：{@code model} 支持字符串简写（目录模型 id）与对象形态
 * {@code {id, effort?, context_window?}} 两种等价提交形态，响应按提交形态回显；
 * 内部供应商映射（model_profile）不在对外契约出现。</p>
 * <p>{@code tools} / {@code mcp_servers} / {@code skills} 以<b>结构化 JSON 值</b>提交
 * （{@code list} / 对象），由应用层转换器序列化为落库配方；{@code agents_md} 已废止，
 * 提交即 400（指令统一由 {@code system} 承载）。</p>
 */
public record AgentConfigRequest(

        /** Agent/版本名称（1-256 字符，仅长度校验，无字符集正则、无 owner 内唯一约束） */
        @NotBlank(message = "名称不能为空")
        @Size(max = 256, message = "名称不能超过256个字符")
        String name,

        /** 描述（上限 2048 字符，对齐公开契约与 V1 列宽） */
        @Size(max = 2048, message = "描述不能超过2048个字符")
        String description,

        /** 系统提示词（版本快照唯一指令载体；对外字段名 `system`，上限 100000 字符） */
        @Size(max = 100000, message = "系统提示词不能超过100000个字符")
        String system,

        /** 模型引用（目录模型 id 字符串简写，或 {id, effort?, context_window?} 对象形态；须存在于模型目录）。
         *  对象形态提交已废止字段 {@code speed} 一律 400（提示改用 {@code effort}）。 */
        @NotNull(message = "模型引用不能为空")
        Object model,

        /** 工具配方（公开四类 type；browser_toolset 本期 400） */
        List<Object> tools,

        /** 外部 MCP 工具源配方（{name, type:"url", url}） */
        @JsonProperty("mcp_servers") List<Object> mcpServers,

        /** 技能绑定配方（{type:"catalog"|"custom", skill_id, version?}） */
        List<Object> skills,

        /** 多智能体编排配置（本期非空提交 400，响应恒 null） */
        Object multiagent,

        /** 业务自定义元数据键值对象（可空） */
        Map<String, Object> metadata,

        /** 已废止字段：AGENTS.md 指令文件（提交即 400，指令统一由 system 承载） */
        @JsonProperty("agents_md")
        @JsonAlias("agentsMd")
        String agentsMd
) {
}