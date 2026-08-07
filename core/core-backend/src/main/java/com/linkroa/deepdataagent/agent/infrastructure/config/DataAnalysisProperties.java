package com.linkroa.deepdataagent.agent.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 数据分析配置属性
 * <p>仅保留查询和安全相关配置，LLM 配置已改为运行时用户动态配置。</p>
 */
@ConfigurationProperties(prefix = "app.data-analysis")
public class DataAnalysisProperties {

    private QueryConfig query = new QueryConfig();
    private int maxRetryCount = 3;
    /** 写库间隔微调：首次 flush 延迟（秒），默认 1s，让前端尽早看到 RUNNING 内容 */
    private long initialFlushDelaySeconds = 1L;
    /** 写库间隔微调：后续固定 flush 间隔（秒），默认 5s */
    private long flushIntervalSeconds = 5L;
    private List<String> dangerousKeywords = List.of(
            "DROP", "ALTER", "CREATE", "TRUNCATE",
            "GRANT", "REVOKE", "EXEC", "EXECUTE",
            "DELETE", "UPDATE", "INSERT",
            "LOAD_FILE", "INTO OUTFILE", "INTO DUMPFILE",
            "REPLACE INTO"
    );

    public QueryConfig getQuery() { return query; }
    public void setQuery(QueryConfig query) { this.query = query; }
    public int getMaxRetryCount() { return maxRetryCount; }
    public void setMaxRetryCount(int maxRetryCount) { this.maxRetryCount = maxRetryCount; }
    public long getInitialFlushDelaySeconds() { return initialFlushDelaySeconds; }
    public void setInitialFlushDelaySeconds(long initialFlushDelaySeconds) { this.initialFlushDelaySeconds = initialFlushDelaySeconds; }
    public long getFlushIntervalSeconds() { return flushIntervalSeconds; }
    public void setFlushIntervalSeconds(long flushIntervalSeconds) { this.flushIntervalSeconds = flushIntervalSeconds; }
    public List<String> getDangerousKeywords() { return dangerousKeywords; }
    public void setDangerousKeywords(List<String> dangerousKeywords) { this.dangerousKeywords = dangerousKeywords; }

    public static class QueryConfig {
        private int maxRows = 500;
        private int timeoutSeconds = 30;

        public int getMaxRows() { return maxRows; }
        public void setMaxRows(int maxRows) { this.maxRows = maxRows; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }
}
