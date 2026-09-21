package com.linkroa.deepdataagent.rag.domain.service;

import org.apache.commons.lang3.StringUtils;

import java.util.regex.Pattern;

/**
 * 实体名归一工具（上游 LightRAG 实体名清洗契约的逐条移植，全链路唯一实现）。
 *
 * <p><b>定位与幂等约束</b>：本类是实体名/关系端点名的唯一身份归一实现——抽取解析入口
 * （文本模式与 JSON 模式）、多模态主实体名定形处、图合并段名称入库防御线全部消费本函数。
 * 归一 MUST 幂等：{@code normalize(normalize(x)) == normalize(x)}，因此合并段防御线对
 * 已归一名称重复执行无副作用。规则集与上游
 * {@code lightrag/utils.py::normalize_entity_name}
 * （= {@code sanitize_and_normalize_extracted_text(remove_inner_quotes=True)}
 * → {@code normalize_extracted_info} + 前置 {@code sanitize_text_for_encoding}）
 * 逐条同源，禁止自创；次序与上游一致：</p>
 *
 * <ol>
 *   <li>控制字符清洗（上游 sanitize 前置步：删除 {@code [\x00-\x08\x0B\x0C\x0E-\x1F\x7F]}，
 *       保留 {@code \t}/{@code \n}/{@code \r}；JVM 字符串不存在 Python 代理对编码问题，
 *       故不移植其 surrogates 剔除与 {@code html.unescape} 两步）；</li>
 *   <li>HTML 段落/换行标签剥离（{@code <p>}/{@code </p>}/{@code <p/>}、
 *       {@code <br>}/{@code </br>}/{@code <br/>}，大小写不敏感）；</li>
 *   <li>全角字母/数字转半角，全角符号 {@code －＋／＊} 转半角；</li>
 *   <li>中文括号转英文括号、破折号 {@code —} 归一为 {@code -}、全角空格转普通空格；</li>
 *   <li>中中间空格删除（空白类按上游 Python {@code \s} 语义扩含 NBSP 族，保证幂等）；</li>
 *   <li>中西文（含数字与常见符号集）之间空格双向删除；英文词内空格 MUST NOT 删除
 *       （{@code ACME Corp} 不得变形，硬验收线）；</li>
 *   <li>外层成对引号五形态剥离（英文双引号、英文单引号、中文弯双引号、中文弯单引号、
 *       书名号；仅当内部不含同类引号时剥离）；</li>
 *   <li>内部引号与 NBSP 族处理（上游 {@code remove_inner_quotes=True} 形态）：删除全部中文
 *       弯引号、删除中文语境邻接的英文引号、NBSP（{@code \u00A0}）转普通空格、
 *       窄 NBSP（{@code \u202F}）在非数字之后转普通空格；</li>
 *   <li>trim；</li>
 *   <li>无效名过滤（返回空串，由解析层统一按丢弃处理）：长度 &lt; 3 且纯数字；
 *       或长度 &lt; 6 且仅由数字与点组成并至少含一个点（如 {@code 3.14}、{@code 1.2.3}）。</li>
 * </ol>
 *
 * <p>工具类，私有构造防止实例化；所有方法线程安全。</p>
 *
 * @author DeepDataAgent
 */
public final class EntityNameNormalizer {

    /** 控制字符模式（上游 _CONTROL_CHAR_PATTERN_ALL：删除除 \t \n \r 之外的 C0 控制符与 DEL） */
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    /** HTML 段落标签模式（上游 </p\s*>|<p\s*>|<p/>，大小写不敏感） */
    private static final Pattern HTML_PARAGRAPH_TAGS =
            Pattern.compile("</p\\s*>|<p\\s*>|<p/>", Pattern.CASE_INSENSITIVE);

    /** HTML 换行标签模式（上游 </br\s*>|<br\s*>|<br/>，大小写不敏感） */
    private static final Pattern HTML_BREAK_TAGS =
            Pattern.compile("</br\\s*>|<br\\s*>|<br/>", Pattern.CASE_INSENSITIVE);

    /** 中文字符范围（上游 [\u4e00-\u9fa5] 同口径） */
    private static final String CJK_RANGE = "\\u4e00-\\u9fa5";

    /**
     * 空白字符类：Java {@code \s} 仅覆盖 ASCII 空白，上游 Python {@code \s}（str 模式）
     * 还匹配 NBSP 族 Unicode 空白，此处按上游语义扩集（全角空格已在第 4 步转出，不再列入）。
     */
    private static final String WHITESPACE_CLASS =
            "[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F]";

    /** 中中间空格模式（上游 (?<=[CJK])\s+(?=[CJK])） */
    private static final Pattern SPACES_BETWEEN_CJK = Pattern.compile(
            "(?<=[" + CJK_RANGE + "])" + WHITESPACE_CLASS + "+(?=[" + CJK_RANGE + "])");

    /** 中文→西文/数字/常见符号方向空格模式（上游中西文间空格删除之一） */
    private static final Pattern SPACES_CJK_TO_WESTERN = Pattern.compile(
            "(?<=[" + CJK_RANGE + "])" + WHITESPACE_CLASS + "+(?=[a-zA-Z0-9()\\[\\]@#$%!&*\\-=+_])");

    /** 西文/数字/常见符号→中文方向空格模式（上游中西文间空格删除之二） */
    private static final Pattern SPACES_WESTERN_TO_CJK = Pattern.compile(
            "(?<=[a-zA-Z0-9()\\[\\]@#$%!&*\\-=+_])" + WHITESPACE_CLASS + "+(?=[" + CJK_RANGE + "])");

    /** 中文语境前邻英文引号模式（上游 ['\"]+(?=[CJK])） */
    private static final Pattern ENGLISH_QUOTES_BEFORE_CJK =
            Pattern.compile("['\"]+(?=[" + CJK_RANGE + "])");

    /** 中文语境后邻英文引号模式（上游 (?<=[CJK])['\"]+） */
    private static final Pattern ENGLISH_QUOTES_AFTER_CJK =
            Pattern.compile("(?<=[" + CJK_RANGE + "])['\"]+");

    /** 窄 NBSP 转空格模式（上游 (?<=[^\d])\u202F → 普通空格，数字之后的窄 NBSP 保留） */
    private static final Pattern NARROW_NBSP_AFTER_NON_DIGIT = Pattern.compile("(?<=\\D)\\u202F");

    /** 纯数字模式（长度 < 3 的纯数字名过滤判据） */
    private static final Pattern DIGITS_ONLY = Pattern.compile("[0-9]+");

    /** 全角大写字母区间起点（Ａ） */
    private static final char FULLWIDTH_A = '\uFF21';

    /** 全角大写字母区间终点（Ｚ） */
    private static final char FULLWIDTH_Z = '\uFF3A';

    /** 全角小写字母区间起点（ａ） */
    private static final char FULLWIDTH_a = '\uFF41';

    /** 全角小写字母区间终点（ｚ） */
    private static final char FULLWIDTH_z = '\uFF5A';

    /** 全角数字区间起点（０） */
    private static final char FULLWIDTH_ZERO = '\uFF10';

    /** 全角数字区间终点（９） */
    private static final char FULLWIDTH_NINE = '\uFF19';

    /** 全角→半角的固定码位差（U+FF01 段与 ASCII 段偏移 0xFEE0） */
    private static final int FULLWIDTH_OFFSET = 0xFEE0;

    /** 无效名过滤一：长度上限（不含） */
    private static final int SHORT_PURE_DIGIT_MAX_LENGTH = 3;

    /** 无效名过滤二：长度上限（不含） */
    private static final int SHORT_DIGIT_DOT_MAX_LENGTH = 6;

    /**
     * 工具类，禁止实例化。
     */
    private EntityNameNormalizer() {
    }

    /**
     * 归一实体/关系端点名。
     *
     * @param input 原始名称（可为 null/空白）
     * @return 归一后的名称；空白输入或命中无效名过滤（短纯数字/短数字点组合）时返回空串
     */
    public static String normalize(String input) {
        if (StringUtils.isBlank(input)) {
            return StringUtils.EMPTY;
        }
        String name = CONTROL_CHARS.matcher(input).replaceAll(StringUtils.EMPTY);
        name = HTML_PARAGRAPH_TAGS.matcher(name).replaceAll(StringUtils.EMPTY);
        name = HTML_BREAK_TAGS.matcher(name).replaceAll(StringUtils.EMPTY);
        name = toHalfWidth(name);
        name = SPACES_BETWEEN_CJK.matcher(name).replaceAll(StringUtils.EMPTY);
        name = SPACES_CJK_TO_WESTERN.matcher(name).replaceAll(StringUtils.EMPTY);
        name = SPACES_WESTERN_TO_CJK.matcher(name).replaceAll(StringUtils.EMPTY);
        name = stripOuterQuotes(name);
        name = removeInnerQuotes(name);
        name = StringUtils.trim(name);
        return filterInvalidNumericName(name);
    }

    /**
     * 全角转半角：字母/数字按码位区间平移，常用全角符号与中文括号、破折号、全角空格按
     * 上游替换表逐字符处理（对应上游第 2~4 步）。
     *
     * @param name 当前名称
     * @return 半角化后的名称
     */
    private static String toHalfWidth(String name) {
        StringBuilder builder = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= FULLWIDTH_A && c <= FULLWIDTH_Z || c >= FULLWIDTH_a && c <= FULLWIDTH_z
                    || c >= FULLWIDTH_ZERO && c <= FULLWIDTH_NINE) {
                builder.append((char) (c - FULLWIDTH_OFFSET));
            } else if (c == '\uFF0D' || c == '\u2014') {
                builder.append('-');
            } else if (c == '\uFF0B') {
                builder.append('+');
            } else if (c == '\uFF0F') {
                builder.append('/');
            } else if (c == '\uFF0A') {
                builder.append('*');
            } else if (c == '\uFF08') {
                builder.append('(');
            } else if (c == '\uFF09') {
                builder.append(')');
            } else if (c == '\u3000') {
                builder.append(' ');
            } else {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    /**
     * 外层成对引号五形态剥离（对应上游顺序：英文双、英文单、中文弯双、中文弯单、书名号；
     * 各形态独立顺序判定，仅当剥离后内部不含同类引号才生效）。
     *
     * @param name 当前名称
     * @return 剥离外层引号后的名称
     */
    private static String stripOuterQuotes(String name) {
        if (name.length() < 2) {
            return name;
        }
        name = stripQuotePair(name, '"', '"');
        name = stripQuotePair(name, '\'', '\'');
        name = stripQuotePair(name, '\u201C', '\u201D');
        name = stripQuotePair(name, '\u2018', '\u2019');
        name = stripQuotePair(name, '\u300A', '\u300B');
        return name;
    }

    /**
     * 单形态外层引号剥离：首尾恰为该对引号且内部不含同类左/右引号时剥除外层。
     *
     * @param name 当前名称
     * @param open 左引号
     * @param close 右引号
     * @return 剥离结果（不满足条件时原样返回）
     */
    private static String stripQuotePair(String name, char open, char close) {
        if (name.length() < 2 || name.charAt(0) != open || name.charAt(name.length() - 1) != close) {
            return name;
        }
        String inner = name.substring(1, name.length() - 1);
        if (inner.indexOf(open) >= 0 || inner.indexOf(close) >= 0) {
            return name;
        }
        return inner;
    }

    /**
     * 内部引号与 NBSP 族处理（对应上游 {@code remove_inner_quotes=True} 分支）：
     * 删除全部中文弯引号、删除中文语境邻接的英文引号、NBSP 转普通空格、
     * 数字之后的窄 NBSP 保留其余转普通空格。
     *
     * @param name 当前名称
     * @return 处理后的名称
     */
    private static String removeInnerQuotes(String name) {
        name = StringUtils.remove(name, '\u201C');
        name = StringUtils.remove(name, '\u201D');
        name = StringUtils.remove(name, '\u2018');
        name = StringUtils.remove(name, '\u2019');
        name = ENGLISH_QUOTES_BEFORE_CJK.matcher(name).replaceAll(StringUtils.EMPTY);
        name = ENGLISH_QUOTES_AFTER_CJK.matcher(name).replaceAll(StringUtils.EMPTY);
        name = StringUtils.replace(name, "\u00A0", " ");
        name = NARROW_NBSP_AFTER_NON_DIGIT.matcher(name).replaceAll(" ");
        return name;
    }

    /**
     * 无效名过滤：长度 &lt; 3 且纯数字，或长度 &lt; 6 且仅由数字与点组成并至少含一个点，
     * 均视为模型输出的数值碎片而非实体名，返回空串交由解析层丢弃。
     *
     * @param name trim 后的名称
     * @return 有效名称原样返回；命中过滤返回空串
     */
    private static String filterInvalidNumericName(String name) {
        if (name.length() < SHORT_PURE_DIGIT_MAX_LENGTH && DIGITS_ONLY.matcher(name).matches()) {
            return StringUtils.EMPTY;
        }
        if (name.length() < SHORT_DIGIT_DOT_MAX_LENGTH && consistsOfDigitsAndDots(name)) {
            return StringUtils.EMPTY;
        }
        return name;
    }

    /**
     * 判断名称是否「仅由数字与点组成且至少含一个点」（上游 should_filter_by_dots 同判据，
     * 命中形态如 {@code 1.2.3}、{@code 12.3}、{@code .123}、{@code 123.}）。
     *
     * @param name 名称
     * @return 命中返回 true
     */
    private static boolean consistsOfDigitsAndDots(String name) {
        boolean containsDot = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '.') {
                containsDot = true;
            } else if (!Character.isDigit(c)) {
                return false;
            }
        }
        return containsDot;
    }
}
