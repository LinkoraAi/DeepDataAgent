package com.linkroa.deepdataagent.rag.infrastructure.prompts.catalog;

import java.util.Map;

/**
 * Prompt 渲染变量表测试夹具（迁移自旧 {@code PromptLoaderTest}）。
 * <p>收录既有模板渲染所需的真实变量表：多模态用户模板、LightRAG 非抽取模板超集变量表、
 * 抽取系模板变量表与查询改写变量表；目录渲染冒烟优先取本夹具取值，
 * 夹具未覆盖的占位符由调用方按正文占位符集合补最小值。</p>
 */
final class PromptVarsFixtures {

    /** 多模态用户模板 {@code generic_prompt} 变量表（旧 PromptLoaderTest 夹具） */
    static final Map<String, String> GENERIC_PROMPT_VARS = Map.of(
            "content_type", "PDF",
            "entity_name", "DeepData",
            "content", "Chapter 1");

    /**
     * 4 个 LightRAG 非抽取模板占位符的超集变量表（旧 PromptLoaderTest 夹具）：
     * summarize（summary_length/description_type/description_name/description_list）、
     * keywords（examples/query）、rag_response（response_type/user_prompt/context_data）、
     * naive_rag_response（content_data），公共 language 一份取值同时注入双套渲染。
     * 值不含花括号，避免触发残留占位符误报。
     */
    static final Map<String, String> LIGHTRAG_SUPERSET_VARS = Map.ofEntries(
            Map.entry("language", "English"),
            Map.entry("query", "TEST QUERY"),
            Map.entry("examples", "TEST EXAMPLES"),
            Map.entry("summary_length", "1200"),
            Map.entry("description_type", "Entity"),
            Map.entry("description_name", "DeepData"),
            Map.entry("description_list", "data point A\ndata point B"),
            Map.entry("response_type", "Multi-Paragraph"),
            Map.entry("user_prompt", "TEST USER INSTRUCTIONS"),
            Map.entry("context_data", "TEST CONTEXT DATA"),
            Map.entry("content_data", "TEST CONTENT DATA"));

    /** 首轮抽取用户提示词（text/JSON 两变体）变量表（旧 PromptLoaderTest 夹具） */
    static final Map<String, String> ENTITY_USER_PROMPT_VARS = Map.of(
            "max_total_records", "200",
            "max_entity_records", "100",
            "language", "Chinese",
            "heading_context_block", "",
            "input_text", "TEST INPUT");

    /** 续抽用户提示词（text/JSON 两变体）变量表（旧 PromptLoaderTest 夹具） */
    static final Map<String, String> CONTINUE_PROMPT_VARS = Map.of(
            "max_total_records", "200",
            "max_entity_records", "100",
            "language", "Chinese");

    /** 抽取系统提示词（text/JSON 两变体）变量表（旧 PromptLoaderTest 夹具） */
    static final Map<String, String> SYSTEM_PROMPT_VARS = Map.of(
            "entity_types_guidance", "- Person: ...\n- Other",
            "max_total_records", "100",
            "max_entity_records", "40",
            "language", "Chinese",
            "examples", "{\"entities\": [], \"relationships\": []}");

    /** 问题改写模板变量表（旧 PromptLoaderTest 夹具） */
    static final Map<String, String> QUERY_REWRITE_VARS = Map.of(
            "language", "Chinese",
            "query", "订单发货超时的处理规则是什么");

    /**
     * 全部已知占位符变量名的代表取值（目录渲染冒烟按占位符名套用；
     * 值统一不含花括号，避免注入后触发残留占位符误报）。
     */
    static final Map<String, String> KNOWN_VARS = Map.ofEntries(
            Map.entry("content_type", "PDF"),
            Map.entry("entity_name", "DeepData"),
            Map.entry("content", "Chapter 1"),
            Map.entry("language", "English"),
            Map.entry("query", "TEST QUERY"),
            Map.entry("examples", "TEST EXAMPLES"),
            Map.entry("summary_length", "1200"),
            Map.entry("description_type", "Entity"),
            Map.entry("description_name", "DeepData"),
            Map.entry("description_list", "data point A\ndata point B"),
            Map.entry("response_type", "Multi-Paragraph"),
            Map.entry("user_prompt", "TEST USER INSTRUCTIONS"),
            Map.entry("context_data", "TEST CONTEXT DATA"),
            Map.entry("content_data", "TEST CONTENT DATA"),
            Map.entry("max_total_records", "200"),
            Map.entry("max_entity_records", "100"),
            Map.entry("heading_context_block", "TEST HEADING"),
            Map.entry("input_text", "TEST INPUT"),
            Map.entry("entity_types_guidance", "- Person: test\n- Other"));

    /**
     * 夹具常量类禁止实例化。
     */
    private PromptVarsFixtures() {
        // 测试夹具类不允许构造实例
    }
}
