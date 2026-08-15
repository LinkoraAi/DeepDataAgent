package com.linkroa.deepdataagent.runtime.domain.gateway;

import java.util.Map;

/**
 * 跨 BC 工具网关出向端口。
 * <p>runtime 上下文不直接调用 datasource 等服务，经本端口隔离；实现位于
 * infrastructure.client。端口保持框架无关注：仅暴露工具名集合、声明性描述
 * 与同步 invoke，具体 AgentScope {@code AgentTool} 包装由装配工厂在基础设施层完成。</p>
 */
public interface AgentToolGateway {

    /**
     * 已注册工具名集合。
     */
    java.util.Set<String> availableToolNames();

    /**
     * 工具声明性描述（供模型识别调用签名）。
     *
     * @param toolName 工具名
     * @return 工具描述；未知工具抛 {@link IllegalArgumentException}
     */
    ToolDescriptor describe(String toolName);

    /**
     * 执行工具调用。
     *
     * @param toolName  工具名
     * @param arguments JSON 参数对象
     * @return 执行结果 JSON 文本（已脱敏/截断）
     */
    String invoke(String toolName, Map<String, Object> arguments);

    /**
     * 工具描述（声明性，框架无关注）：名字 / 描述 / JSON Schema 参数定义。
     *
     * @param name        工具名
     * @param description 工具能力描述
     * @param parameters  JSON Schema（type=object，properties=参数定义）
     */
    record ToolDescriptor(String name, String description, Map<String, Object> parameters) {
    }
}