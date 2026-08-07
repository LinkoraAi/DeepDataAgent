package com.linkroa.deepdataagent.agent.infrastructure.util;

/**
 * Agent 工具统一返回协议工具类
 * <p>集中定义工具返回的三态前缀（{@code [DATA]/[EMPTY]/[ERROR]}）及分页提示（{@code [PAGING]}），
 * 并提供统一的工厂方法与前缀剥离方法，保证各工具返回格式一致、可维护。</p>
 */
public final class AgentToolResponse {

    /** 数据状态前缀：有数据 */
    public static final String DATA = "[DATA]";

    /** 空结果状态前缀：查询成功但无数据 */
    public static final String EMPTY = "[EMPTY]";

    /** 失败状态前缀：执行失败 */
    public static final String ERROR = "[ERROR]";

    /** 分页提示前缀：结果达到单次上限，可能被截断 */
    public static final String PAGING = "[PAGING]";

    private AgentToolResponse() {
    }

    /**
     * 生成数据状态结果：{@code [DATA] message}
     *
     * @param message 数据描述（通常为行数说明 + 换行 + JSON 数据）
     * @return 带 {@code [DATA]} 前缀的结果字符串
     */
    public static String data(String message) {
        return DATA + " " + message;
    }

    /**
     * 生成空结果结果：{@code [EMPTY] message}
     *
     * @param message 空结果说明
     * @return 带 {@code [EMPTY]} 前缀的结果字符串
     */
    public static String empty(String message) {
        return EMPTY + " " + message;
    }

    /**
     * 生成失败结果：{@code [ERROR] message}
     *
     * @param message 失败原因
     * @return 带 {@code [ERROR]} 前缀的结果字符串
     */
    public static String error(String message) {
        return ERROR + " " + message;
    }

    /**
     * 生成分页提示文本（前置换行，用于追加在 {@code [DATA]} 行之后）
     * <p>达到单次上限时提示可能被截断，并引导 LLM 采用聚合/采样而非拉取全量。</p>
     *
     * @param maxRows 单次返回上限
     * @return 带前置换行的 {@code [PAGING]} 提示文本
     */
    public static String pagingHint(int maxRows) {
        return String.format(
                "\n[PAGING] 结果达到单次上限 %d 行，可能仍有更多数据。如需统计性结论，请改写为聚合" +
                "（COUNT/SUM/GROUP BY/AVG）或采样缩小范围，不要拉取全量原始行。",
                maxRows);
    }

    /**
     * 剥离工具返回的 {@code [DATA]/[EMPTY]/[ERROR]} 前缀及首行行数说明，仅保留数据部分。
     * <p>execute_sql / execute_api_query 返回形如 {@code "[DATA] 查询成功，共 N 行：\n[json]"}，
     * 数据在首个换行之后。供下游工具（generate_chart、generate_analysis）解析前调用。</p>
     *
     * @param result 可能带前缀的工具返回结果
     * @return 剥离前缀后的数据部分；若结果为空返回空字符串，无前缀时原样返回去首尾空白后的文本
     */
    public static String stripPrefix(String result) {
        if (result == null) {
            return "";
        }
        String trimmed = result.trim();
        if ((trimmed.startsWith(DATA) || trimmed.startsWith(EMPTY) || trimmed.startsWith(ERROR))
                && trimmed.contains("\n")) {
            return trimmed.substring(trimmed.indexOf('\n') + 1).trim();
        }
        return trimmed;
    }
}