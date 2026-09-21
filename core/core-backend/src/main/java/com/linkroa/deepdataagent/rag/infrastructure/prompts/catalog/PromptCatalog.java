package com.linkroa.deepdataagent.rag.infrastructure.prompts.catalog;

import com.linkroa.deepdataagent.knowledgebase.domain.model.EntityType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.BuiltinEntityType;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt 渲染统一静态门面（取代 {@code PromptLoader} + {@code PromptRenderer} 端口，
 * <p>模板正文由编译期静态目录 {@link ZhPromptCatalog}/{@link EnPromptCatalog} 承载
 * （41 模板 × 2 语言套），模板完整性由编译期保证，不存在启动扫描、围栏解析、
 * 计数校验或运行期降级概念；消费方直接静态调用本门面，不再经依赖注入。</p>
 * <p>渲染入参语言为知识库语言全名：{@code Chinese}（大小写不敏感、trim）取中文模板套，
 * 其余一切值（含历史裸语言码 {@code zh}/{@code en}）宽容取英文模板套（见
 * {@link #normalizeLanguage}）；产出语言由提示词内 {@code {language}} 变量另行控制。</p>
 * <p>{@link #render(String, String, Map)} 支持占位符替换：系统常量
 * {@code {tuple_delimiter}}/{@code {completion_delimiter}} 优先注入且禁止用户覆盖；
 * 其余占位符取值自调用方变量，缺失或为 {@code null} 立即抛异常（fail-fast），
 * 替换完成后若仍存在残留占位符同样抛异常，避免静默替换损坏抽取格式。</p>
 *
 * @author DeepDataAgent
 */
public final class PromptCatalog {

    /** 英文语言码 */
    public static final String LANGUAGE_EN = "en";

    /** 中文语言码 */
    public static final String LANGUAGE_ZH = "zh";

    /** 中文知识库语言全名（{@code normalizeLanguage} 判定值，与 KbLanguage 枚举 Chinese 项一致） */
    private static final String LANGUAGE_FULL_NAME_CHINESE = "Chinese";

    /** 系统常量占位符映射（占位符变量名 -> 常量值），渲染时优先于用户变量并忽略同名用户 key */
    private static final Map<String, String> SYSTEM_DELIMITERS = Map.of(
            "tuple_delimiter", PromptTemplates.TUPLE_DELIMITER,
            "completion_delimiter", PromptTemplates.COMPLETION_DELIMITER
    );

    /** 占位符正则：{@code {identifier}}，标识符以字母/下划线开头，后续为字母/数字/下划线 */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}");

    /** 实体类型列表条目前缀 */
    private static final String ENTITY_TYPE_ITEM_PREFIX = "- ";

    /** 兜底实体类型名称（强制保留，禁止用户自定义/同名覆盖） */
    private static final String OTHER_TYPE_NAME = "Other";

    /** 英文引导句 */
    private static final String EN_GUIDANCE_INTRO = "Classify each entity using one of the following types. "
            + "If no type fits, use `Other`.";

    /** 中文引导句 */
    private static final String ZH_GUIDANCE_INTRO = "请使用下列类型之一对每个实体进行分类。"
            + "如果没有合适的分类，请使用 `Other`。";

    /** 英文内置 11 类列表（顺序固定） */
    private static final List<String> EN_BUILTIN_TYPE_LINES = List.of(
            "- Person: Human individuals, real or fictional",
            "- Creature: Non-human living beings (animals, mythical beings, etc.)",
            "- Organization: Companies, institutions, government bodies, groups",
            "- Location: Geographic places (cities, countries, buildings, regions)",
            "- Event: Occurrences, incidents, ceremonies, meetings",
            "- Concept: Abstract ideas, theories, principles, beliefs",
            "- Method: Procedures, techniques, algorithms, workflows",
            "- Content: Creative or informational works (books, articles, films, reports)",
            "- Data: Quantitative or structured information (statistics, datasets, measurements)",
            "- Artifact: Physical or digital objects created by humans (tools, software, devices)",
            "- NaturalObject: Natural non-living objects (minerals, celestial bodies, chemical compounds)"
    );

    /** 中文内置 11 类列表（顺序固定） */
    private static final List<String> ZH_BUILTIN_TYPE_LINES = List.of(
            "- Person: 人类个体，真实的或虚构的",
            "- Creature: 非人类生物（动物、神话生物等）",
            "- Organization: 公司、机构、政府机关、团体",
            "- Location: 地理场所（城市、国家、建筑、地区）",
            "- Event: 事件、事故、典礼、会议",
            "- Concept: 抽象概念、理论、原则、信念",
            "- Method: 流程、技术、算法、工作流",
            "- Content: 创意或信息作品（书籍、文章、电影、报告）",
            "- Data: 定量或结构化信息（统计数据、数据集、测量值）",
            "- Artifact: 人造的物理或数字对象（工具、软件、设备）",
            "- NaturalObject: 自然界的非生命对象（矿物、天体、化合物）"
    );

    /** 语言 -> 内置 11 类列表的映射 */
    private static final Map<String, List<String>> BUILTIN_TYPE_LINES = Map.of(
            LANGUAGE_EN, EN_BUILTIN_TYPE_LINES,
            LANGUAGE_ZH, ZH_BUILTIN_TYPE_LINES
    );

    /**
     * 工具门面类禁止实例化。
     */
    private PromptCatalog() {
        // 静态门面不允许构造实例
    }

    /**
     * 渲染指定语言的模板：先注入系统常量（优先于用户变量且忽略同名用户 key），
     * 再以用户变量替换其余占位符，任意缺失/空值/残留占位符均立即抛异常（fail-fast）。
     *
     * @param templateName 模板名（{@link PromptTemplates} 集中定义的模板名常量）
     * @param language     知识库语言全名：{@code Chinese}（大小写不敏感）取中文模板套，
     *                     其余（含历史裸语言码）一律取英文模板套
     * @param vars         占位符变量集合，不允许为 {@code null}
     * @return 渲染完成的模板正文
     * @throws IllegalArgumentException 模板名/语言/变量非法、模板不存在、某语言版本缺失、
     *                                  占位符变量缺失或为 null、替换后仍有残留占位符
     */
    public static String render(String templateName, String language, Map<String, ?> vars) {
        if (StringUtils.isBlank(templateName)) {
            throw new IllegalArgumentException("模板名不能为空");
        }
        if (StringUtils.isBlank(language)) {
            throw new IllegalArgumentException("语言不能为空");
        }
        if (Objects.isNull(vars)) {
            throw new IllegalArgumentException("渲染变量不能为空");
        }
        String lang = normalizeLanguage(language);
        Map<String, String> templates = catalogFor(lang);
        String body = templates.get(templateName);
        if (Objects.isNull(body)) {
            throw new IllegalArgumentException("未知的 Prompt 模板: " + templateName);
        }
        return renderTemplate(body, vars);
    }

    /**
     * 拼装实体类型引导文本（注入抽取模板的 {@code {entity_types_guidance}} 占位符）：
     * 引导句 -> 内置 11 类（顺序固定）-> 知识库自定义类型 -> 末尾 {@code - Other} 兜底。
     * 自定义类型会过滤 null/空串、剔除与内置 11 类或 {@code Other} 同名项（英文代码大小写不敏感、
     * 中文名精确匹配）、大小写不敏感去重并保留首个。
     *
     * @param customEntityTypes 知识库自定义实体类型集合，可为空
     * @param language          知识库语言全名：{@code Chinese}（大小写不敏感）返回中文版，其余返回英文版
     * @return 完整实体类型引导文本
     * @throws IllegalArgumentException 语言为空白
     */
    public static String buildEntityTypesGuidance(List<EntityType> customEntityTypes, String language) {
        if (StringUtils.isBlank(language)) {
            throw new IllegalArgumentException("语言不能为空");
        }
        String lang = normalizeLanguage(language);
        List<String> lines = new ArrayList<>();
        lines.add(LANGUAGE_ZH.equals(lang) ? ZH_GUIDANCE_INTRO : EN_GUIDANCE_INTRO);
        lines.add(StringUtils.EMPTY);
        lines.addAll(BUILTIN_TYPE_LINES.get(lang));
        Set<String> seen = new LinkedHashSet<>();
        if (Objects.nonNull(customEntityTypes)) {
            for (EntityType entityType : customEntityTypes) {
                if (Objects.isNull(entityType) || StringUtils.isBlank(entityType.entityType())) {
                    continue;
                }
                String typeName = entityType.entityType().trim();
                if (BuiltinEntityType.find(typeName).isPresent()) {
                    continue;
                }
                if (!seen.add(typeName.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                lines.add(ENTITY_TYPE_ITEM_PREFIX + typeName);
            }
        }
        lines.add(ENTITY_TYPE_ITEM_PREFIX + OTHER_TYPE_NAME);
        return String.join("\n", lines);
    }

    /**
     * 按归一语言取对应静态模板目录。
     *
     * @param lang 归一后的模板套语言码（{@code en}/{@code zh}）
     * @return 模板名 -> 正文的不可变注册表
     */
    private static Map<String, String> catalogFor(String lang) {
        return LANGUAGE_ZH.equals(lang) ? ZhPromptCatalog.TEMPLATES : EnPromptCatalog.TEMPLATES;
    }

    /**
     * 执行占位符替换：按正则依次替换，系统常量优先、忽略同名用户 key，
     * 缺失/null 立即抛异常；替换结束后若存在残留占位符同样抛异常。
     *
     * @param body 模板正文
     * @param vars 用户变量集合
     * @return 渲染结果
     */
    private static String renderTemplate(String body, Map<String, ?> vars) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(body);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(rendered,
                    Matcher.quoteReplacement(resolvePlaceholder(matcher.group(1), vars)));
        }
        matcher.appendTail(rendered);
        String result = rendered.toString();
        Matcher leftover = PLACEHOLDER_PATTERN.matcher(result);
        if (leftover.find()) {
            throw new IllegalArgumentException("模板存在未替换的占位符: {" + leftover.group(1) + "}");
        }
        return result;
    }

    /**
     * 解析单个占位符变量的值：系统常量优先（并忽略同名用户 key）；否则取用户变量，
     * 缺失或为 {@code null} 时抛异常，值统一按 {@link String#valueOf(Object)} 转字符串。
     *
     * @param key  占位符变量名
     * @param vars 用户变量集合
     * @return 替换值
     */
    private static String resolvePlaceholder(String key, Map<String, ?> vars) {
        if (SYSTEM_DELIMITERS.containsKey(key)) {
            return SYSTEM_DELIMITERS.get(key);
        }
        Object value = vars.get(key);
        if (Objects.isNull(value)) {
            throw new IllegalArgumentException("缺少占位符变量: {" + key + "}");
        }
        return String.valueOf(value);
    }

    /**
     * 语言归一（模板套选择）：输入契约知识库语言全名——仅 {@code Chinese}（大小写不敏感、trim）
     * 映射中文模板套，其余一律映射英文模板套（含历史裸语言码 {@code zh}/{@code en}，宽容不抛错；
     * 非中文指令正文英文权威，产出语言由提示词内 {@code {language}} 变量另行控制）。
     *
     * @param language 语言标识（知识库语言全名，兼容历史裸语言码）
     * @return 模板套语言码（{@code en}/{@code zh}）
     */
    static String normalizeLanguage(String language) {
        if (StringUtils.trim(language).equalsIgnoreCase(LANGUAGE_FULL_NAME_CHINESE)) {
            return LANGUAGE_ZH;
        }
        return LANGUAGE_EN;
    }
}
