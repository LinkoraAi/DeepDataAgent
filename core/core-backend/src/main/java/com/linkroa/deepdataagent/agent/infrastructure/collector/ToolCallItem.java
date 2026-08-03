package com.linkroa.deepdataagent.agent.infrastructure.collector;

/**
 * 工具调用项
 * <p>字段名与前端 {@code ToolCallItem} 接口对齐，确保 JSON 序列化后前端可直接解析。</p>
 *
 * @param name 工具名称
 * @param input 输入参数（JSON 字符串）
 * @param result 工具结果文本
 * @param startTime 开始时间戳
 * @param endTime 结束时间戳
 * @param status 状态：running / success / error
 * @param toolCallId 工具调用 ID（用于内部匹配，前端可忽略）
 */
public record ToolCallItem(
        String name,
        String input,
        String result,
        long startTime,
        long endTime,
        String status,
        String toolCallId
) {
    /** 默认状态：运行中 */
    private static final String STATUS_RUNNING = "running";
    /** 成功状态 */
    private static final String STATUS_SUCCESS = "success";
    /** 错误状态 */
    private static final String STATUS_ERROR = "error";

    /**
     * 简化的构造器（向后兼容）
     */
    public ToolCallItem(String name, String input, long startTime) {
        this(name, input, null, startTime, 0, STATUS_RUNNING, null);
    }

    /**
     * 带工具调用ID的构造器
     */
    public ToolCallItem(String name, String input, long startTime, String toolCallId) {
        this(name, input, null, startTime, 0, STATUS_RUNNING, toolCallId);
    }

    /**
     * 根据成功标志创建带结果的工具调用项
     *
     * @param result 工具结果
     * @param endTime 结束时间
     * @param success 是否成功
     * @return 新的工具调用项
     */
    public ToolCallItem withResult(String result, long endTime, boolean success) {
        return new ToolCallItem(this.name, this.input, result, this.startTime, endTime,
                success ? STATUS_SUCCESS : STATUS_ERROR, this.toolCallId);
    }
}