package com.linkroa.deepdataagent.rag.infrastructure.prompts.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ZhPromptCatalog} 中文静态模板目录单元测试。
 * <p>覆盖两类契约：</p>
 * <ol>
 *   <li>键集合对账：注册表键与 {@link PromptTemplates#TEMPLATE_NAMES} 完全一致（41 项）；</li>
 *   <li>全键渲染冒烟：每个模板以「既有变量表夹具（迁移自旧 {@code PromptLoaderTest}）
 *       或按正文占位符集合构造的最小变量表」注入后经 {@link PromptCatalog} 门面渲染成功、
 *       产物非空且无残留占位符。</li>
 * </ol>
 */
class ZhPromptCatalogTest {

    /** 占位符正则（与 {@link PromptCatalog} 渲染口径一致：字母/下划线开头的标识符） */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}");

    /** 系统常量占位符（渲染时优先注入，不需要调用方提供取值） */
    private static final Set<String> SYSTEM_PLACEHOLDER_KEYS =
            Set.of("tuple_delimiter", "completion_delimiter");

    /** 最小变量表的占位取值（不含花括号，避免触发残留占位符误报） */
    private static final String MINIMAL_VAR_VALUE = "TEST_VALUE";

    /**
     * 渲染冒烟参数源：全部 41 个模板名（与 {@link PromptTemplates#TEMPLATE_NAMES} 对账后逐一套用）。
     *
     * @return 模板名流
     */
    static Stream<String> templateNameProvider() {
        return PromptTemplates.TEMPLATE_NAMES.stream().sorted();
    }

    /**
     * 场景：中文目录注册表构建完成。
     * 预期：键集合与 {@link PromptTemplates#TEMPLATE_NAMES} 完全一致且共 41 项，
     * 每个键的正文均非空白。
     */
    @Test
    void should_matchTemplateNamesKeys_when_keySet_given_zhCatalogLoaded() {
        // given / when
        Set<String> keys = ZhPromptCatalog.TEMPLATES.keySet();

        // then
        assertEquals(PromptTemplates.TEMPLATE_NAMES, keys, "中文目录键集合应与 TEMPLATE_NAMES 完全一致");
        assertEquals(41, keys.size(), "中文目录应恰收录 41 个模板");
        for (String key : keys) {
            assertNotNull(ZhPromptCatalog.TEMPLATES.get(key), "模板 [" + key + "] 正文不应为 null");
            assertFalse(ZhPromptCatalog.TEMPLATES.get(key).isBlank(), "模板 [" + key + "] 正文不应为空白");
        }
    }

    /**
     * 场景：以 Chinese 全名经门面逐个渲染中文目录全部 41 个模板，
     * 变量表取既有夹具（迁移自旧 {@code PromptLoaderTest}）或按正文占位符集合构造的最小变量表。
     * 预期：渲染成功、产物非空白且不残留任何 {@code {占位符}} 形态内容。
     *
     * @param templateName 模板名
     */
    @ParameterizedTest(name = "[{0}] zh 套渲染冒烟")
    @MethodSource("templateNameProvider")
    void should_renderWithoutLeftoverPlaceholder_when_render_given_zhTemplateEach(String templateName) {
        // given
        String body = ZhPromptCatalog.TEMPLATES.get(templateName);
        Map<String, Object> vars = buildVarsFor(body);

        // when：门面 render 自身对残留占位符 fail-fast，正常返回即无残留
        String rendered = PromptCatalog.render(templateName, "Chinese", vars);

        // then
        assertNotNull(rendered, "模板 [" + templateName + "] 渲染结果不应为 null");
        assertFalse(rendered.isBlank(), "模板 [" + templateName + "] 渲染结果不应为空白");
        assertFalse(containsPlaceholder(rendered),
                "模板 [" + templateName + "] 渲染产物不应残留未替换占位符");
    }

    /**
     * 场景：以 Chinese 全名渲染多模态用户模板 {@code generic_prompt}（夹具变量表迁移自旧测试）。
     * 预期：命中中文模板套正文（含中文指令特征「请分析该」），变量值全部注入。
     */
    @Test
    void should_renderChineseInstructionBody_when_render_given_genericPromptFixtureVars() {
        // given（夹具变量表迁移自旧 PromptLoaderTest）
        Map<String, Object> vars = new HashMap<>(PromptVarsFixtures.GENERIC_PROMPT_VARS);
        vars.put("content", "第 1 章");

        // when
        String rendered = PromptCatalog.render(PromptTemplates.GENERIC_PROMPT, "Chinese", vars);

        // then
        assertTrue(rendered.contains("请分析该"), "Chinese 全名应命中 zh 模板套");
        assertTrue(rendered.contains("第 1 章"), "{content} 变量应被替换");
    }

    /**
     * 按模板正文占位符集合构造变量表：优先取既有夹具取值（旧 {@code PromptLoaderTest} 变量表迁移），
     * 夹具未覆盖的占位符以统一最小值补齐；系统常量占位符跳过（由门面注入）。
     *
     * @param body 模板原始正文
     * @return 渲染变量表
     */
    private static Map<String, Object> buildVarsFor(String body) {
        Map<String, Object> vars = new HashMap<>();
        for (String key : extractPlaceholderNames(body)) {
            if (SYSTEM_PLACEHOLDER_KEYS.contains(key)) {
                continue;
            }
            vars.put(key, PromptVarsFixtures.KNOWN_VARS.getOrDefault(key, MINIMAL_VAR_VALUE));
        }
        return vars;
    }

    /**
     * 提取模板正文中的全部占位符名（与门面渲染正则同口径）。
     *
     * @param body 模板原始正文
     * @return 占位符名集合（保持出现顺序）
     */
    private static Set<String> extractPlaceholderNames(String body) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(body);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /**
     * 判断文本中是否残留 {@code {占位符}} 形态内容。
     *
     * @param text 渲染产物
     * @return 存在残留返回 true
     */
    private static boolean containsPlaceholder(String text) {
        return PLACEHOLDER_PATTERN.matcher(text).find();
    }
}
