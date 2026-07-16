package com.linkroa.deepdataagent.agent.domain.model;

/**
 * 搜索结果值对象
 * <p>封装从互联网搜索返回的单条结果信息。</p>
 */
public record SearchResult(
    /**
     * 结果标题
     */
    String title,

    /**
     * 结果 URL
     */
    String url,

    /**
     * 结果摘要/片段
     */
    String snippet,

    /**
     * 完整内容（可选，API 未返回时为空字符串）
     * <p>从 Tavily API 返回的原始内容，未经截断处理。
     * 如果 API 未返回该字段或字段为 null，值为空字符串 ""。</p>
     */
    String content
) {
    /**
     * 创建仅包含基本信息的搜索结果
     */
    public static SearchResult of(String title, String url, String snippet) {
        return new SearchResult(title, url, snippet, "");
    }

    /**
     * 创建包含完整内容的搜索结果
     */
    public static SearchResult of(String title, String url, String snippet, String content) {
        return new SearchResult(title, url, snippet, content);
    }
}
