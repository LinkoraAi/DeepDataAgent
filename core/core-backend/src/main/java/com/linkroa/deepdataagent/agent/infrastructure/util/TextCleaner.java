package com.linkroa.deepdataagent.agent.infrastructure.util;

/**
 * 文本清理工具类
 * <p>集中处理 LLM 输出文本中的推理过程前缀清理逻辑，避免多个组件重复实现。
 * 主要解决：LLM 有时会将"我来帮您分析...""现在我来生成..."这类过程性叙述
 * 混入最终回复，导致前端把推理过程也渲染成分析报告。</p>
 */
public final class TextCleaner {

    /** 常见的过程性叙述前缀 */
    private static final String[] NARRATIVE_PREFIXES = {
            "我将帮您", "让我", "现在", "好的", "首先", "接下来", "我已经",
            "我将", "我来", "请稍等", "正在分析", "我将为您", "我将进行"
    };

    private TextCleaner() {
        // 工具类禁止实例化
    }

    /**
     * 去除分析报告前的推理过程文本，只保留结构化报告内容。
     * <p>本方法通过定位首个 Markdown 标题行来截断前置推理文本；
     * 若未找到标题，则退回原文，避免误伤真正报告内容。</p>
     *
     * @param text 原始文本
     * @return 去除推理前缀后的文本
     */
    public static String stripReasoningPreamble(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        String cleaned = removeNarrativePrefixes(text);
        cleaned = removeSqlFragments(cleaned);

        int firstHeadingIndex = cleaned.indexOf("#");
        if (firstHeadingIndex >= 0) {
            return cleaned.substring(firstHeadingIndex);
        }
        return cleaned;
    }

    /**
     * 移除常见的叙述性前缀。
     * <p>如果文本以叙述关键词开头，尝试截断到下一个二级标题处。</p>
     *
     * @param text 原始文本
     * @return 清理后的文本
     */
    private static String removeNarrativePrefixes(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        String trimmed = text.trim();

        if (trimmed.startsWith("##")) {
            return text;
        }

        for (String prefix : NARRATIVE_PREFIXES) {
            if (trimmed.startsWith(prefix)) {
                int headingIndex = text.indexOf("#", prefix.length());
                if (headingIndex >= 0) {
                    return text.substring(headingIndex);
                }
                break;
            }
        }

        return text;
    }

    /**
     * 移除混入报告的 SQL 片段。
     * <p>如果检测到 SQL 片段且前方有非 SQL 内容，尝试截断到正式报告标题处。</p>
     *
     * @param text 原始文本
     * @return 清理后的文本
     */
    private static String removeSqlFragments(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        String upper = text.toUpperCase();
        boolean hasSelect = upper.contains("SELECT");
        boolean hasFrom = upper.contains("FROM");

        if (hasSelect && hasFrom && text.length() > 80) {
            int selectIndex = upper.indexOf("SELECT");
            if (selectIndex > 20) {
                String afterSelect = text.substring(selectIndex);
                int headingIndex = afterSelect.indexOf("#");
                if (headingIndex >= 0) {
                    return afterSelect.substring(headingIndex);
                }
            }
        }

        return text;
    }
}