package com.linkroa.deepdataagent.runtime.application.command;

/**
 * 更新会话资料命令（契约端点 {@code POST /sessions/{session_id}}）。
 * <p>字段语义（对齐 sessions 规格）：{@code titlePresent=true} 时 {@code title} 生效——
 * 非 null 覆盖、{@code null} 清空；{@code titlePresent=false}（请求省略）时保留原标题。
 * {@code metadataJson} 为 <b>patch</b>：值为 {@code null} 的键被删除、其余键覆盖，顶层
 * {@code null}（字段省略）为 no-op；{@code environmentVariablesJson} 非 null 时整体替换
 * （未提交键移除，{@code {}} 清空）、{@code null} 时保留原值。不可更新字段
 * （agent / environment_id / status）在接口层拦截（显式提交非空值 → 400）。</p>
 *
 * @param sessionId                 会话业务 ID（必填）
 * @param titlePresent              请求是否显式提交了 {@code title} 字段（false=省略=不改）
 * @param title                     新标题（{@code titlePresent=true} 时生效；null=清空）
 * @param metadataJson              元数据 patch JSON 文本（null=不改；值为 null 的键删除）
 * @param environmentVariablesJson  环境变量 JSON 文本（null=不改；整体替换）
 */
public record UpdateSessionCommand(
        String sessionId,
        boolean titlePresent,
        String title,
        String metadataJson,
        String environmentVariablesJson
) {

    public UpdateSessionCommand {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
    }
}
