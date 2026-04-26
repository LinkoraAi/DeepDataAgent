package com.linkroa.deepdataagent.memory.model;

/**
 * 单次记忆检索的运行参数。
 *
 * <p>由配置转换而来，用于控制召回数量、最大返回字符数、RRF 参数和是否启用时间衰减。</p>
 */
public record RetrieveOptions(
        int maxResults,
        int maxChars,
        int rrfK,
        double minScore,
        boolean enableTemporalDecay
) {
}
