package com.linkroa.deepdataagent.runtime.infrastructure.client;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 以平台运行时实名注册的 MCP 工具包装体（align-qoder-vault-credential-capabilities D16 路径 B）。
 * <p>框架 2.0.3 的 {@code McpClientManager} 以服务端<b>原始工具名</b>注册 MCP 工具，而平台全链路的
 * 工具身份（权限规则键、{@code agent.mcp_tool_use} 投影、执行侧 MCP 判定）均建立在
 * {@code mcp__{server}__{tool}} 约定上。包装体持有一个改名后的 MCP 工具：对外呈现实名，
 * 调用时原样委派被包装工具（其 {@code callAsync} 以自身原始名向服务器发起 RPC，服务器侧工具名不受影响）。</p>
 * <p><b>为何必须仍是 {@link ToolBase} 子类</b>：框架对非 {@code ToolBase} 的 {@code AgentTool}
 * 在权限门控处直接放行（{@code ReActAgent.evaluateOne}），版本 {@code always_ask}/{@code always_deny}
 * 会被绕过；本类继承 {@code ToolBase} 后规则表、工具自检与 BYPASS 兜底的求值序原样生效，
 * 且继续携带 {@code getMcpName()} 归属（服务器名）与只读标记。</p>
 * <p>描述 / 入参 schema / 输出 schema / strict / 只读 / 并发安全标记均取自被包装工具，
 * 模型可见的工具面除名称外与框架注册结果一致。</p>
 */
class McpRuntimeNamedTool extends ToolBase {

    /** 被包装的框架注册工具（MCP 工具，原名向服务器发起调用）。 */
    private final ToolBase delegate;

    private McpRuntimeNamedTool(String runtimeName, ToolBase delegate) {
        super(ToolBase.builder()
                .name(runtimeName)
                .description(delegate.getDescription())
                .inputSchema(delegate.getParameters())
                .readOnly(delegate.isReadOnly())
                .concurrencySafe(delegate.isConcurrencySafe())
                .mcp(delegate.getMcpName()));
        this.delegate = delegate;
    }

    /**
     * 构造实名包装体。
     *
     * @param runtimeName 平台运行时实名（{@code mcp__{server_name}__{tool_name}}）
     * @param delegate    框架已注册的 MCP 工具（携带服务器归属）
     * @return 实名包装体
     */
    static McpRuntimeNamedTool of(String runtimeName, ToolBase delegate) {
        return new McpRuntimeNamedTool(runtimeName, delegate);
    }

    @Override
    public Boolean getStrict() {
        return delegate.getStrict();
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return delegate.getOutputSchema();
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> toolInput, PermissionContextState context) {
        return delegate.checkPermissions(toolInput, context);
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return delegate.callAsync(param);
    }
}