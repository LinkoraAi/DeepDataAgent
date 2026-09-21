package com.linkroa.deepdataagent.rag.infrastructure.prompts.catalog;

import com.linkroa.deepdataagent.knowledgebase.domain.model.EntityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PromptCatalog} 静态门面单元测试。
 * <p>覆盖门面行为契约：render 的 fail-fast 矩阵（空模板名 / 空语言 / null 变量 /
 * 未知模板 / 缺变量 / null 变量值 / 残留占位符 / 系统分隔符不可被调用方覆盖）、
 * normalizeLanguage 语言归一（全名 / 裸码 / 大小写 / trim）、
 * buildEntityTypesGuidance 组装（内置清单、自定义去重与同名剔除、Other 兜底、双语引导句）、
 * {@code QUERY_ENHANCEMENT_SUFFIX} 前导双换行，
 * 以及自旧 {@code PromptLoaderTest} 原样迁移的 zh≍en 逐字一致与 query-rewrite 分叉锁定断言集
 * （仅渲染调用改为静态门面）。</p>
 */
class PromptCatalogTest {

    /** 用于从模板原始正文中提取占位符名的正则（仅匹配小写下划线形态的完整占位符） */
    private static final Pattern TEMPLATE_PLACEHOLDER_PATTERN = Pattern.compile("\\{([a-z_]+)\\}");

    /**
     * 首轮抽取用户提示词（text/JSON 两变体）的占位符基线集合（修订前），
     * 模板修订不得新增或删除占位符。
     */
    private static final Set<String> USER_PROMPT_BASELINE_PLACEHOLDERS = Set.of(
            "max_total_records", "max_entity_records", "completion_delimiter",
            "language", "heading_context_block", "input_text");

    /**
     * 续抽用户提示词（text/JSON 两变体）的占位符基线集合（修订前），
     * 模板修订不得新增或删除占位符。
     */
    private static final Set<String> CONTINUE_PROMPT_BASELINE_PLACEHOLDERS = Set.of(
            "max_total_records", "max_entity_records", "completion_delimiter", "language");

    // ------------------------------------------------------------------ render fail-fast 矩阵

    /**
     * 场景：模板名为 {@code null}、空串或空白串。
     * 预期：立即抛 {@link IllegalArgumentException}（fail-fast，避免静默空渲染）。
     *
     * @param templateName 非法模板名
     */
    @ParameterizedTest(name = "模板名 [{0}] 应被拒绝")
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void should_throwException_when_render_given_blankTemplateName(String templateName) {
        // given / when / then
        assertThrows(IllegalArgumentException.class,
                () -> PromptCatalog.render(templateName, "English", Map.of()));
    }

    /**
     * 场景：渲染语言为空白（null / 空串 / 空格串）。
     * 预期：立即抛 {@link IllegalArgumentException}（语言不允许缺位）。
     *
     * @param language 非法语言值
     */
    @ParameterizedTest(name = "语言 [{0}] 应被拒绝")
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void should_throwException_when_render_given_blankLanguage(String language) {
        // given / when / then
        assertThrows(IllegalArgumentException.class,
                () -> PromptCatalog.render(PromptTemplates.GENERIC_PROMPT, language,
                        PromptVarsFixtures.GENERIC_PROMPT_VARS));
    }

    /**
     * 场景：变量集合传入 {@code null}。
     * 预期：抛 {@link IllegalArgumentException}（即使模板无占位符也拒绝 null 变量表）。
     */
    @Test
    void should_throwException_when_render_given_nullVars() {
        // given / when
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> PromptCatalog.render(PromptTemplates.FAIL_RESPONSE, "English", null));

        // then
        assertTrue(exception.getMessage().contains("渲染变量不能为空"));
    }

    /**
     * 场景：请求渲染不存在的模板。
     * 预期：抛 {@link IllegalArgumentException}（fail-fast，避免静默生成空模板）。
     */
    @Test
    void should_throwException_when_render_given_unknownTemplate() {
        // given
        Map<String, Object> vars = Map.of("content_type", "PDF");

        // when / then
        assertThrows(IllegalArgumentException.class,
                () -> PromptCatalog.render("not_exist_template", "English", vars));
    }

    /**
     * 场景：渲染模板时未提供某个必需占位符变量（fail-fast 契约）。
     * 预期：抛 {@link IllegalArgumentException} 且提示缺失的变量名。
     */
    @Test
    void should_throwException_when_render_given_missingVar() {
        // given
        Map<String, Object> vars = Map.of("content_type", "PDF");

        // when
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> PromptCatalog.render(PromptTemplates.GENERIC_PROMPT, "English", vars));

        // then
        assertTrue(exception.getMessage().contains("{entity_name}") || exception.getMessage().contains("{content}"));
    }

    /**
     * 场景：渲染模板时某个占位符变量值为 {@code null}（fail-fast 契约）。
     * 预期：抛 {@link IllegalArgumentException} 且提示缺失的变量名。
     */
    @Test
    void should_throwException_when_render_given_nullVarValue() {
        // given
        Map<String, Object> vars = new HashMap<>();
        vars.put("content_type", "PDF");
        vars.put("entity_name", "DeepData");
        vars.put("content", null);

        // when
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> PromptCatalog.render(PromptTemplates.GENERIC_PROMPT, "English", vars));

        // then
        assertTrue(exception.getMessage().contains("{content}"));
    }

    /**
     * 场景：变量取值本身携带未转义占位符形态内容（注入后正文再次出现 {@code {entity_name}}）。
     * 预期：替换完成后残留占位符校验命中，抛 {@link IllegalArgumentException}。
     */
    @Test
    void should_throwException_when_render_given_injectedValueWithLeftoverPlaceholder() {
        // given
        Map<String, Object> vars = Map.of(
                "content_type", "PDF",
                "entity_name", "DeepData",
                "content", "{entity_name}");

        // when
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> PromptCatalog.render(PromptTemplates.GENERIC_PROMPT, "English", vars));

        // then
        assertTrue(exception.getMessage().contains("未替换的占位符"),
                "应报告残留占位符而非静默输出");
    }

    /**
     * 场景：渲染含 {@code {tuple_delimiter}}/{@code {completion_delimiter}} 的模板，
     * 且用户变量携带同名 key。
     * 预期：系统常量值被注入（{@code <|#|>}/{@code <|COMPLETE|>}），同名用户值被忽略。
     */
    @Test
    void should_injectSystemDelimitersIgnoringUserVars_when_render_given_fencedTemplate() {
        // given（夹具变量表迁移自旧 PromptLoaderTest）
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("tuple_delimiter", "USER_TUPLE");
        vars.put("completion_delimiter", "USER_COMPLETE");
        vars.put("entity_types_guidance", "classify");
        vars.put("max_total_records", "100");
        vars.put("max_entity_records", "50");
        vars.put("examples", "example-rows");
        vars.put("language", "English");

        // when
        String rendered = PromptCatalog.render(PromptTemplates.ENTITY_EXTRACTION_SYSTEM_PROMPT, "English", vars);

        // then
        assertTrue(rendered.contains(PromptTemplates.TUPLE_DELIMITER), "应注入系统元组分隔符 <|#|>");
        assertTrue(rendered.contains(PromptTemplates.COMPLETION_DELIMITER), "应注入系统完成信号 <|COMPLETE|>");
        assertTrue(rendered.contains("classify"));
        assertTrue(rendered.contains("100"));
        assertTrue(rendered.contains("50"));
        assertFalse(rendered.contains("USER_TUPLE"), "vars 中的 tuple_delimiter 应被系统常量覆盖");
        assertFalse(rendered.contains("USER_COMPLETE"), "vars 中的 completion_delimiter 应被系统常量覆盖");
    }

    /**
     * 场景：渲染模板时变量集合携带模板并不使用的多余 key。
     * 预期：渲染成功，多余 key 的值不进入结果正文。
     */
    @Test
    void should_ignoreExtraKeys_when_render_given_redundantVars() {
        // given
        Map<String, Object> vars = new HashMap<>(PromptVarsFixtures.GENERIC_PROMPT_VARS);
        vars.put("unused", "UNUSED_VALUE");

        // when
        String rendered = PromptCatalog.render(PromptTemplates.GENERIC_PROMPT, "English", vars);

        // then
        assertFalse(rendered.contains("UNUSED_VALUE"));
    }

    // ------------------------------------------------------------------ normalizeLanguage 语言归一

    /**
     * 场景：以中文全名的各种书写形态（大小写混合、首尾空白）归一模板套语言。
     * 预期：{@code Chinese}/{@code " chinese "}/{@code "CHINESE "} 均映射 {@code zh}。
     *
     * @param language 中文全名书写变体
     */
    @ParameterizedTest(name = "[{0}] 应归一为 zh")
    @ValueSource(strings = {"Chinese", " chinese ", "CHINESE ", "chinese"})
    void should_returnZh_when_normalizeLanguage_given_chineseFullNameVariants(String language) {
        // given / when
        String normalized = PromptCatalog.normalizeLanguage(language);

        // then
        assertEquals(PromptCatalog.LANGUAGE_ZH, normalized);
    }

    /**
     * 场景：以英文全名、其他语言全名或历史裸语言码归一模板套语言。
     * 预期：{@code English}/{@code Japanese}/{@code Korean}/{@code en}/{@code zh} 一律宽容映射 {@code en}。
     *
     * @param language 非中文语言标识
     */
    @ParameterizedTest(name = "[{0}] 应归一为 en")
    @ValueSource(strings = {"en", "zh", "English", "Japanese", "Korean", "fr"})
    void should_returnEn_when_normalizeLanguage_given_nonChineseLanguage(String language) {
        // given / when
        String normalized = PromptCatalog.normalizeLanguage(language);

        // then
        assertEquals(PromptCatalog.LANGUAGE_EN, normalized);
    }

    // ------------------------------------------------------------------ buildEntityTypesGuidance 组装

    /**
     * 场景：自定义类型传 {@code null}，以英文拼装实体类型引导文本。
     * 预期：引导句 → 空行 → 内置 11 类（顺序固定）→ 末尾 {@code - Other} 兜底，共 14 行。
     */
    @Test
    void should_buildGuidanceWithBuiltinsAndOther_when_buildGuidance_given_nullCustomTypes() {
        // given / when
        String guidance = PromptCatalog.buildEntityTypesGuidance(null, "English");

        // then
        assertTrue(guidance.startsWith(
                "Classify each entity using one of the following types. If no type fits, use `Other`.\n\n"));
        assertTrue(guidance.contains("- Person: Human individuals, real or fictional"));
        assertTrue(guidance.contains("- NaturalObject: Natural non-living objects "
                + "(minerals, celestial bodies, chemical compounds)"));
        assertTrue(guidance.endsWith("\n- Other"));
        assertEquals(14, guidance.split("\n", -1).length, "应为 引导句+空行+11 类+Other 共 14 行");
    }

    /**
     * 场景：自定义类型包含 null 元素、与内置类/Other 同名项（英文代码、中文名、大小写变体）
     * 及重复项，以中文全名拼装引导文本（空白名称已被 {@link EntityType} 值对象拒绝，不在此列）。
     * 预期：null 项被过滤、同名项全部剔除、重复项仅保留首个（大小写不敏感），
     * 有效自定义类型以 {@code - 名称} 形式追加、内置类在前、末尾唯一 {@code - Other}。
     */
    @Test
    void should_filterBuiltinAndDeduplicateCustomTypes_when_buildGuidance_given_conflictingCustomTypes() {
        // given
        List<EntityType> customTypes = Arrays.asList(
                null,
                new EntityType("Person"),
                new EntityType("人"),
                new EntityType("Other"),
                new EntityType("CustomType"),
                new EntityType("customtype"),
                new EntityType("IndustryStandard"));

        // when
        String guidance = PromptCatalog.buildEntityTypesGuidance(customTypes, "English");

        // then
        assertTrue(guidance.endsWith("\n- Other"));
        assertFalse(hasBareLine(guidance, "- Person"), "不应输出与内置类 Person 同名的自定义类型");
        assertFalse(hasBareLine(guidance, "- 人"), "不应输出内置类中文名（人）");
        assertEquals(1, countOccurrences(guidance, "\n- Other"), "Other 应仅出现在唯一的兜底位置");
        assertTrue(hasBareLine(guidance, "- CustomType"), "应保留首个 CustomType");
        assertFalse(hasBareLine(guidance, "- customtype"), "重复项（大小写不敏感）应被去重");
        assertTrue(hasBareLine(guidance, "- IndustryStandard"));
        assertEquals(1, countOccurrences(guidance, "- CustomType"), "CustomType 应恰好出现一次");
        int personLine = guidance.indexOf("- Person");
        int customLine = guidance.indexOf("- CustomType");
        assertTrue(personLine < customLine, "内置类应排在自定义类型之前");
    }

    /**
     * 场景：以中文全名 {@code Chinese} 拼装实体类型引导文本。
     * 预期：返回中文引导句与中文内置 11 类描述，末尾仍为 {@code - Other}。
     */
    @Test
    void should_buildChineseGuidance_when_buildGuidance_given_chineseFullName() {
        // given / when
        String guidance = PromptCatalog.buildEntityTypesGuidance(List.of(), "Chinese");

        // then
        assertTrue(guidance.startsWith("请使用下列类型之一对每个实体进行分类。"
                + "如果没有合适的分类，请使用 `Other`。\n\n"));
        assertTrue(guidance.contains("- Person: 人类个体，真实的或虚构的"));
        assertTrue(guidance.contains("- NaturalObject: 自然界的非生命对象（矿物、天体、化合物）"));
        assertTrue(guidance.endsWith("\n- Other"));
    }

    /**
     * 场景：拼装引导文本时语言为空白。
     * 预期：抛 {@link IllegalArgumentException}（fail-fast）。
     */
    @Test
    void should_throwException_when_buildGuidance_given_blankLanguage() {
        // given / when / then
        assertThrows(IllegalArgumentException.class,
                () -> PromptCatalog.buildEntityTypesGuidance(List.of(), "  "));
    }

    // ------------------------------------------------------------------ QUERY_ENHANCEMENT_SUFFIX 前导换行

    /**
     * 场景：分别以英文与中文全名渲染 {@code QUERY_ENHANCEMENT_SUFFIX}（模板正文无占位符，传空变量表）。
     * 预期：渲染产物均以两个前导换行 {@code \n\n} 起始（显式拼接，与上游 LightRAG 形态对齐）。
     *
     * @param language 知识库语言全名
     */
    @ParameterizedTest(name = "[{0}] 渲染应以两个前导换行起始")
    @ValueSource(strings = {"English", "Chinese"})
    void should_startWithDoubleNewline_when_render_given_queryEnhancementSuffix(String language) {
        // given / when
        String rendered = PromptCatalog.render(PromptTemplates.QUERY_ENHANCEMENT_SUFFIX, language, Map.of());

        // then
        assertTrue(rendered.startsWith("\n\n"), "后缀渲染产物必须以两个前导换行起始");
    }

    // ------------------------------------------------------------------ zh≍en 逐字一致与分叉锁定（自旧 PromptLoaderTest 原样迁移）

    /**
     * 场景：一致性锁定——4 对 LightRAG 非抽取模板
     * （summarize_entity_descriptions / keywords_extraction / rag_response / naive_rag_response）
     * 的 zh 套与 en 套正文逐字相同（英文正文唯一权威）。
     * 预期：注入同一超集变量表分别以 {@code Chinese} 与 {@code English} 渲染，产物原文逐字一致
     * （不做任何 normalize 比较）。
     *
     * @param templateName LightRAG 非抽取模板名
     */
    @ParameterizedTest(name = "[{0}] zh/en 渲染应逐字一致")
    @ValueSource(strings = {
            PromptTemplates.SUMMARIZE_ENTITY_DESCRIPTIONS,
            PromptTemplates.KEYWORDS_EXTRACTION,
            PromptTemplates.RAG_RESPONSE,
            PromptTemplates.NAIVE_RAG_RESPONSE})
    void should_renderIdenticalBody_when_render_given_lightragTemplateDualSets(String templateName) {
        // given：覆盖 4 个模板全部占位符的超集变量表（值不含花括号，避免触发残留占位符误报）
        Map<String, Object> vars = new HashMap<>(PromptVarsFixtures.LIGHTRAG_SUPERSET_VARS);

        // when
        String zhRendered = PromptCatalog.render(templateName, "Chinese", vars);
        String enRendered = PromptCatalog.render(templateName, "English", vars);

        // then
        assertEquals(enRendered, zhRendered,
                "模板 [" + templateName + "] zh 套渲染应与 en 套逐字一致（英文正文唯一权威）");
    }

    /**
     * 场景：query-rewrite 模板双套为有意分叉（zh 套为翻译后正文，与 en 套内容不同）。
     * 预期：注入同一变量表分别以 {@code Chinese} 与 {@code English} 渲染，产物存在差异，
     * 且各自携带对应语言的指令正文（锁定双套分叉不被误回退为逐字相同）。
     */
    @Test
    void should_renderDivergentBodies_when_render_given_queryRewriteDualSets() {
        // given
        Map<String, Object> vars = new HashMap<>(PromptVarsFixtures.QUERY_REWRITE_VARS);

        // when
        String zhRendered = PromptCatalog.render(PromptTemplates.QUERY_REWRITE, "Chinese", vars);
        String enRendered = PromptCatalog.render(PromptTemplates.QUERY_REWRITE, "English", vars);

        // then：双套正文存在差异（有意分叉），且各自携带对应语言特征正文
        assertNotEquals(enRendered, zhRendered, "query-rewrite 双套为有意分叉，渲染产物不应逐字相同");
        assertTrue(zhRendered.contains("问题改写专家"), "zh 套应保留中文指令正文");
        assertTrue(enRendered.contains("expert query rewriter"), "en 套应保留英文指令正文");
        assertTrue(zhRendered.contains("订单发货超时的处理规则是什么"), "zh 套应完成 {query} 占位符替换");
    }

    /**
     * 场景：以 {@code Chinese} 全名渲染 text 版首轮抽取用户提示词
     * {@code entity_extraction_user_prompt}（抽取系模板英文正文唯一权威口径）。
     * 预期：渲染产物与英文套逐字一致；保留反臆测条款（"confident about"
     * "clearly marked as speculation"）；正文中不再出现中文指令文本。
     */
    @Test
    void should_renderEnglishAuthorityBody_when_render_given_textUserPromptChineseSet() {
        // given
        Map<String, Object> vars = new HashMap<>(PromptVarsFixtures.ENTITY_USER_PROMPT_VARS);

        // when
        String zhRendered = PromptCatalog.render(PromptTemplates.ENTITY_EXTRACTION_USER_PROMPT, "Chinese", vars);
        String enRendered = PromptCatalog.render(PromptTemplates.ENTITY_EXTRACTION_USER_PROMPT, "English", vars);

        // then
        assertEquals(enRendered, zhRendered, "zh 回落抽取系模板：Chinese 渲染应与英文套逐字一致");
        assertTrue(zhRendered.contains("confident about"), "反臆测条款应保留（加固不回退）");
        assertTrue(zhRendered.contains("clearly marked as speculation"), "推测标注要求应保留");
        assertFalse(zhRendered.contains("确有把握"), "zh 套渲染不应再有中文指令正文");
    }

    /**
     * 场景：以 {@code Chinese} 全名渲染 JSON 版首轮抽取用户提示词
     * {@code entity_extraction_json_user_prompt}（抽取系模板英文正文唯一权威口径）。
     * 预期：渲染产物与英文套逐字一致且保留反臆测条款，无中文指令正文。
     */
    @Test
    void should_renderEnglishAuthorityBody_when_render_given_jsonUserPromptChineseSet() {
        // given
        Map<String, Object> vars = new HashMap<>(PromptVarsFixtures.ENTITY_USER_PROMPT_VARS);

        // when
        String zhRendered = PromptCatalog.render(PromptTemplates.ENTITY_EXTRACTION_JSON_USER_PROMPT, "Chinese", vars);
        String enRendered = PromptCatalog.render(PromptTemplates.ENTITY_EXTRACTION_JSON_USER_PROMPT, "English", vars);

        // then
        assertEquals(enRendered, zhRendered, "zh 回落抽取系模板：JSON 变体 Chinese 渲染应与英文套逐字一致");
        assertTrue(zhRendered.contains("confident about"), "JSON 变体反臆测条款应保留");
        assertFalse(zhRendered.contains("确有把握"), "zh 套 JSON 渲染不应再有中文指令正文");
    }

    /**
     * 场景：渲染 text 版续抽用户提示词 {@code entity_continue_extraction_user_prompt}
     * （b 系精修后：zh 回落 + 纠偏语义扩展）。
     * 预期：英文正文保留空响应条款（"did not produce any valid records"、仅输出完成信号行），
     * 并新增覆盖「描述有误」纠偏语义（"incorrectly described"）；zh 套渲染无中文指令正文。
     */
    @Test
    void should_containEmptyResponseAndDescriptionCorrection_when_render_given_textContinuePrompt() {
        // given
        Map<String, Object> vars = new HashMap<>(PromptVarsFixtures.CONTINUE_PROMPT_VARS);

        // when
        String zhRendered = PromptCatalog.render(
                PromptTemplates.ENTITY_CONTINUE_EXTRACTION_USER_PROMPT, "Chinese", vars);
        String enRendered = PromptCatalog.render(
                PromptTemplates.ENTITY_CONTINUE_EXTRACTION_USER_PROMPT, "English", vars);

        // then
        assertEquals(enRendered, zhRendered, "zh 回落后续抽模板英文套唯一权威");
        assertTrue(enRendered.contains("did not produce any valid records"), "空响应条款应保留");
        assertTrue(enRendered.contains("output only the `<|COMPLETE|>` line"), "空响应条款应指向渲染后的完成信号");
        assertTrue(enRendered.contains("**missed**"), "纠偏范围应覆盖漏抽");
        assertTrue(enRendered.contains("incorrectly described"), "纠偏范围应新增覆盖描述有误");
        assertFalse(zhRendered.contains("未产出任何有效记录"), "zh 套续抽渲染不应再有中文指令正文");
    }

    /**
     * 场景：渲染 JSON 版续抽用户提示词 {@code entity_continue_extraction_json_user_prompt}
     * （b1 首句回退 "incorrectly described" + b2 元组分隔符噪声移除后）。
     * 预期：首句为「missed or incorrectly described」语义、保留空结果结构与空响应一致性条款；
     * 渲染产物不出现元组分隔符 {@code <|#|>}（JSON 模式指令与元组协议彻底解耦）。
     */
    @Test
    void should_coverDescriptionCorrectionWithoutTupleNoise_when_render_given_jsonContinuePrompt() {
        // given
        Map<String, Object> vars = new HashMap<>(PromptVarsFixtures.CONTINUE_PROMPT_VARS);

        // when
        String zhRendered = PromptCatalog.render(
                PromptTemplates.ENTITY_CONTINUE_EXTRACTION_JSON_USER_PROMPT, "Chinese", vars);
        String enRendered = PromptCatalog.render(
                PromptTemplates.ENTITY_CONTINUE_EXTRACTION_JSON_USER_PROMPT, "English", vars);

        // then
        assertEquals(enRendered, zhRendered, "zh 回落后 JSON 续抽模板英文套唯一权威");
        assertTrue(enRendered.contains("missed or incorrectly described"),
                "b1：首句应回退清单原文「漏抽或描述有误」语义");
        assertFalse(enRendered.contains("incorrectly formatted entities and relationships from the input text"),
                "b1：首句不应再限定为格式错误");
        assertTrue(enRendered.contains("{\"entities\": [], \"relationships\": []}"),
                "空结果结构条款应保留");
        assertTrue(enRendered.contains("this response should also be empty"),
                "空响应一致性条款应保留（加固不回退）");
        assertFalse(enRendered.contains("<|#|>"), "b2：JSON 续抽渲染不应出现元组分隔符");
        assertFalse(zhRendered.contains("未产出任何有效记录"), "zh 套 JSON 续抽渲染不应再有中文指令正文");
    }

    /**
     * 场景：渲染 JSON 模式系统提示词 {@code entity_extraction_json_system_prompt}
     * （b2 噪声移除 + b3 转义字面示例回填 + 4 反引号围栏后）。
     * 预期：渲染成功（4 反引号围栏解析无损）；产物不含元组分隔符 {@code <|#|>} 与
     * "DO NOT use" 指令句；keywords 指令为清单 "separated by commas" 表述；
     * 转义指令携带清单字面示例（{@code \"}、{@code \\}、{@code \n}、{@code \frac}）。
     */
    @Test
    void should_renderCleanJsonSystemPrompt_when_render_given_jsonSystemTemplate() {
        // given
        Map<String, Object> vars = new HashMap<>(PromptVarsFixtures.SYSTEM_PROMPT_VARS);

        // when
        String rendered = PromptCatalog.render(PromptTemplates.ENTITY_EXTRACTION_JSON_SYSTEM_PROMPT, "English", vars);

        // then
        assertFalse(rendered.contains("<|#|>"), "b2：JSON 系统提示词渲染不应含元组分隔符");
        assertFalse(rendered.contains("DO NOT use"), "b2：DO NOT use 指令句应已删除");
        assertTrue(rendered.contains("separated by commas"), "b2：keywords 指令应为清单逗号分隔表述");
        assertTrue(rendered.contains("escape `\"` as `\\\"`"), "b3：应回填引号转义字面示例");
        assertTrue(rendered.contains("escape backslashes as `\\\\`"), "b3：应回填反斜杠转义字面示例");
        assertTrue(rendered.contains("newlines as `\\n`"), "b3：应回填换行转义字面示例");
        assertTrue(rendered.contains("`\\frac` is written as `\"\\\\frac\"`"), "b3：应回填 LaTeX 双反斜杠字面示例");
    }

    /**
     * 场景：静态化迁移完成后，读取目录中的 4 段用户提示词原始正文（zh/en 两套）。
     * 预期：各模板占位符集合与修订前基线完全一致（新条款仅复用既有 {@code {completion_delimiter}}，
     * 未新增或删除任何占位符）。
     */
    @Test
    void should_keepBaselinePlaceholders_when_loadTemplates_given_revisedExtractionPrompts() {
        // given
        Map<String, Set<String>> baselineByTemplate = new LinkedHashMap<>();
        baselineByTemplate.put(PromptTemplates.ENTITY_EXTRACTION_USER_PROMPT, USER_PROMPT_BASELINE_PLACEHOLDERS);
        baselineByTemplate.put(PromptTemplates.ENTITY_EXTRACTION_JSON_USER_PROMPT, USER_PROMPT_BASELINE_PLACEHOLDERS);
        baselineByTemplate.put(PromptTemplates.ENTITY_CONTINUE_EXTRACTION_USER_PROMPT,
                CONTINUE_PROMPT_BASELINE_PLACEHOLDERS);
        baselineByTemplate.put(PromptTemplates.ENTITY_CONTINUE_EXTRACTION_JSON_USER_PROMPT,
                CONTINUE_PROMPT_BASELINE_PLACEHOLDERS);

        // when / then
        for (Map.Entry<String, Set<String>> entry : baselineByTemplate.entrySet()) {
            assertBaselinePlaceholders(entry.getKey(), entry.getValue(), ZhPromptCatalog.TEMPLATES);
            assertBaselinePlaceholders(entry.getKey(), entry.getValue(), EnPromptCatalog.TEMPLATES);
        }
    }

    /**
     * 断言指定目录中某模板正文的占位符集合与基线一致。
     *
     * @param templateName 模板名
     * @param baseline     基线占位符集合
     * @param catalog      目录注册表（zh 或 en）
     */
    private static void assertBaselinePlaceholders(String templateName, Set<String> baseline,
                                                   Map<String, String> catalog) {
        String body = catalog.get(templateName);
        assertTrue(body != null && !body.isBlank(),
                "模板 [" + templateName + "] 应存在于目录注册表");
        assertEquals(baseline, extractPlaceholderNames(body),
                "模板 [" + templateName + "] 占位符集合应与修订前基线一致");
    }

    /**
     * 从模板原始正文中提取全部占位符名（小写下划线形态，形如 {@code {name}}）。
     *
     * @param body 模板原始正文（渲染前）
     * @return 去重后的占位符名集合
     */
    private static Set<String> extractPlaceholderNames(String body) {
        Set<String> names = new HashSet<>();
        Matcher matcher = TEMPLATE_PLACEHOLDER_PATTERN.matcher(body);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /**
     * 统计字符串中某子串出现的次数。
     *
     * @param text  被统计文本
     * @param token 目标子串
     * @return 出现次数
     */
    private static int countOccurrences(String text, String token) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(token, index)) != -1) {
            count++;
            index += token.length();
        }
        return count;
    }

    /**
     * 判断文本中是否存在"裸行"（整行内容恰为给定项、不含冒号描述）。
     * 内置类输出形如 {@code - Person: ...}（带描述），自定义类输出形如 {@code - CustomType}（裸行），
     * 用整行精确匹配区分二者，避免 {@code contains} 误判。
     *
     * @param text 被匹配文本
     * @param line 期望的裸行内容（如 {@code - Person}）
     * @return 存在整行恰为给定内容的行返回 true
     */
    private static boolean hasBareLine(String text, String line) {
        return Arrays.stream(text.split("\n", -1)).map(String::trim).anyMatch(line::equals);
    }
}
