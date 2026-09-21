package com.linkroa.deepdataagent.rag.infrastructure.prompts.catalog;

import java.util.Set;

/**
 * Prompt 模板名与抽取协议分隔符集中常量。
 * <p>本类是全部 Prompt 资产标识符的<b>唯一权威定义处</b>：模板正文由
 * {@link ZhPromptCatalog}/{@link EnPromptCatalog} 编译期静态目录承载，
 * 目录类的注册表键必须与本类常量逐字一致（由目录测试对账）；各消费服务不得再
 * 自行定义模板名字面量（仅允许保留指向本类的别名），消除双副本漂移风险。</p>
 * <p>收录范围：32 个既有模板名（25 摄入 + 7 检索）与
 * 9 个查询增强模板名（QUERY_* 族，《Prompt原文对照清单.md》第二部分），
 * 共 41 个，经 {@link #TEMPLATE_NAMES} 供目录键集合对账口径。</p>
 *
 * @author DeepDataAgent
 */
public final class PromptTemplates {

    // ------------------------------------------------------------------ 抽取协议分隔符（系统常量，渲染时优先注入、禁止用户覆盖）

    /** 元组分隔符：抽取协议行内字段分隔（对齐 LightRAG DEFAULT_TUPLE_DELIMITER） */
    public static final String TUPLE_DELIMITER = "<|#|>";

    /** 完成信号：模型输出该字面量表示补漏（gleaning）结束（对齐 LightRAG DEFAULT_COMPLETION_DELIMITER） */
    public static final String COMPLETION_DELIMITER = "<|COMPLETE|>";

    // ------------------------------------------------------------------ 多模态分析系统模板（RAG-Anything 7-1）

    /** 系统模板：图片分析（有图可看） */
    public static final String IMAGE_ANALYSIS_SYSTEM = "IMAGE_ANALYSIS_SYSTEM";

    /** 系统模板：图片分析（无图可看，基于文本信息推断） */
    public static final String IMAGE_ANALYSIS_FALLBACK_SYSTEM = "IMAGE_ANALYSIS_FALLBACK_SYSTEM";

    /** 系统模板：表格分析 */
    public static final String TABLE_ANALYSIS_SYSTEM = "TABLE_ANALYSIS_SYSTEM";

    /** 系统模板：公式分析 */
    public static final String EQUATION_ANALYSIS_SYSTEM = "EQUATION_ANALYSIS_SYSTEM";

    /** 系统模板：通用内容分析 */
    public static final String GENERIC_ANALYSIS_SYSTEM = "GENERIC_ANALYSIS_SYSTEM";

    // ------------------------------------------------------------------ 多模态分析用户模板（RAG-Anything 7-2/7-3/7-4/7-5）

    /** 用户模板：图片分析 */
    public static final String VISION_PROMPT = "vision_prompt";

    /** 用户模板：图片分析（含邻近上下文） */
    public static final String VISION_PROMPT_WITH_CONTEXT = "vision_prompt_with_context";

    /** 用户模板：表格分析 */
    public static final String TABLE_PROMPT = "table_prompt";

    /** 用户模板：表格分析（含邻近上下文） */
    public static final String TABLE_PROMPT_WITH_CONTEXT = "table_prompt_with_context";

    /** 用户模板：公式分析 */
    public static final String EQUATION_PROMPT = "equation_prompt";

    /** 用户模板：公式分析（含邻近上下文） */
    public static final String EQUATION_PROMPT_WITH_CONTEXT = "equation_prompt_with_context";

    /** 用户模板：通用内容分析 */
    public static final String GENERIC_PROMPT = "generic_prompt";

    /** 用户模板：通用内容分析（含邻近上下文） */
    public static final String GENERIC_PROMPT_WITH_CONTEXT = "generic_prompt_with_context";

    // ------------------------------------------------------------------ 多模态 chunk 装配模板（RAG-Anything 7-6）

    /** chunk 模板：图片 */
    public static final String IMAGE_CHUNK = "image_chunk";

    /** chunk 模板：表格 */
    public static final String TABLE_CHUNK = "table_chunk";

    /** chunk 模板：公式 */
    public static final String EQUATION_CHUNK = "equation_chunk";

    /** chunk 模板：通用内容 */
    public static final String GENERIC_CHUNK = "generic_chunk";

    // ------------------------------------------------------------------ 实体关系抽取模板（LightRAG 7-7）

    /** 抽取模板：章节上下文块 */
    public static final String ENTITY_EXTRACTION_SECTION_CONTEXT = "entity_extraction_section_context";

    /** 抽取模板：文本模式系统提示词 */
    public static final String ENTITY_EXTRACTION_SYSTEM_PROMPT = "entity_extraction_system_prompt";

    /** 抽取模板：文本模式用户提示词 */
    public static final String ENTITY_EXTRACTION_USER_PROMPT = "entity_extraction_user_prompt";

    /** 抽取模板：文本模式续抽（gleaning）用户提示词 */
    public static final String ENTITY_CONTINUE_EXTRACTION_USER_PROMPT = "entity_continue_extraction_user_prompt";

    /** 抽取模板：JSON 模式系统提示词 */
    public static final String ENTITY_EXTRACTION_JSON_SYSTEM_PROMPT = "entity_extraction_json_system_prompt";

    /** 抽取模板：JSON 模式用户提示词 */
    public static final String ENTITY_EXTRACTION_JSON_USER_PROMPT = "entity_extraction_json_user_prompt";

    /** 抽取模板：JSON 模式续抽（gleaning）用户提示词 */
    public static final String ENTITY_CONTINUE_EXTRACTION_JSON_USER_PROMPT = "entity_continue_extraction_json_user_prompt";

    // ------------------------------------------------------------------ 描述摘要模板（LightRAG 7-8）

    /** 摘要模板：实体/关系描述合并摘要 */
    public static final String SUMMARIZE_ENTITY_DESCRIPTIONS = "summarize_entity_descriptions";

    // ------------------------------------------------------------------ 检索链路模板（8-1~8-7）

    /** 检索模板：问题改写（自研双套） */
    public static final String QUERY_REWRITE = "query_rewrite";

    /** 检索模板：双层关键词提取 */
    public static final String KEYWORDS_EXTRACTION = "keywords_extraction";

    /** 检索模板：MIX 模式答案生成 */
    public static final String RAG_RESPONSE = "rag_response";

    /** 检索模板：NAIVE 模式答案生成 */
    public static final String NAIVE_RAG_RESPONSE = "naive_rag_response";

    /** 检索模板：MIX 模式上下文骨架（图谱实体/关系/切片分节） */
    public static final String KG_QUERY_CONTEXT = "kg_query_context";

    /** 检索模板：NAIVE 模式上下文骨架（切片列表分节） */
    public static final String NAIVE_QUERY_CONTEXT = "naive_query_context";

    /** 检索模板：无可用上下文兜底直出 */
    public static final String FAIL_RESPONSE = "fail_response";

    // ------------------------------------------------------------------ 查询增强模板（QUERY_* 族，《Prompt原文对照清单》第二部分）

    /** 查询增强模板：图片描述 user prompt */
    public static final String QUERY_IMAGE_DESCRIPTION = "QUERY_IMAGE_DESCRIPTION";

    /** 查询增强模板：图片分析 system prompt */
    public static final String QUERY_IMAGE_ANALYST_SYSTEM = "QUERY_IMAGE_ANALYST_SYSTEM";

    /** 查询增强模板：表格分析 user prompt */
    public static final String QUERY_TABLE_ANALYSIS = "QUERY_TABLE_ANALYSIS";

    /** 查询增强模板：表格分析 system prompt */
    public static final String QUERY_TABLE_ANALYST_SYSTEM = "QUERY_TABLE_ANALYST_SYSTEM";

    /** 查询增强模板：公式解释 user prompt */
    public static final String QUERY_EQUATION_ANALYSIS = "QUERY_EQUATION_ANALYSIS";

    /** 查询增强模板：公式解释 system prompt */
    public static final String QUERY_EQUATION_ANALYST_SYSTEM = "QUERY_EQUATION_ANALYST_SYSTEM";

    /** 查询增强模板：通用内容分析 user prompt */
    public static final String QUERY_GENERIC_ANALYSIS = "QUERY_GENERIC_ANALYSIS";

    /** 查询增强模板：通用内容分析 system prompt */
    public static final String QUERY_GENERIC_ANALYST_SYSTEM = "QUERY_GENERIC_ANALYST_SYSTEM";

    /** 查询增强模板：多模态增强查询尾部指导句（渲染产物以两个前导换行起始） */
    public static final String QUERY_ENHANCEMENT_SUFFIX = "QUERY_ENHANCEMENT_SUFFIX";

    /** 全部模板名集合（41 个：32 既有 + 9 查询增强），供目录键集合对账 */
    public static final Set<String> TEMPLATE_NAMES = Set.of(
            IMAGE_ANALYSIS_SYSTEM, IMAGE_ANALYSIS_FALLBACK_SYSTEM, TABLE_ANALYSIS_SYSTEM,
            EQUATION_ANALYSIS_SYSTEM, GENERIC_ANALYSIS_SYSTEM,
            VISION_PROMPT, VISION_PROMPT_WITH_CONTEXT, TABLE_PROMPT, TABLE_PROMPT_WITH_CONTEXT,
            EQUATION_PROMPT, EQUATION_PROMPT_WITH_CONTEXT, GENERIC_PROMPT, GENERIC_PROMPT_WITH_CONTEXT,
            IMAGE_CHUNK, TABLE_CHUNK, EQUATION_CHUNK, GENERIC_CHUNK,
            ENTITY_EXTRACTION_SECTION_CONTEXT, ENTITY_EXTRACTION_SYSTEM_PROMPT,
            ENTITY_EXTRACTION_USER_PROMPT, ENTITY_CONTINUE_EXTRACTION_USER_PROMPT,
            ENTITY_EXTRACTION_JSON_SYSTEM_PROMPT, ENTITY_EXTRACTION_JSON_USER_PROMPT,
            ENTITY_CONTINUE_EXTRACTION_JSON_USER_PROMPT,
            SUMMARIZE_ENTITY_DESCRIPTIONS,
            QUERY_REWRITE, KEYWORDS_EXTRACTION, RAG_RESPONSE, NAIVE_RAG_RESPONSE,
            KG_QUERY_CONTEXT, NAIVE_QUERY_CONTEXT, FAIL_RESPONSE,
            QUERY_IMAGE_DESCRIPTION, QUERY_IMAGE_ANALYST_SYSTEM,
            QUERY_TABLE_ANALYSIS, QUERY_TABLE_ANALYST_SYSTEM,
            QUERY_EQUATION_ANALYSIS, QUERY_EQUATION_ANALYST_SYSTEM,
            QUERY_GENERIC_ANALYSIS, QUERY_GENERIC_ANALYST_SYSTEM,
            QUERY_ENHANCEMENT_SUFFIX
    );

    /**
     * 工具常量类禁止实例化。
     */
    private PromptTemplates() {
        // 常量集合类不允许构造实例
    }
}
