package com.linkroa.deepdataagent.rag.domain.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多模态 LLM JSON 响应容错解析器（五级容错链，纯静态无状态领域工具）。
 * <p>解析链按代价从低到高逐级降级，任一级成功即返回：</p>
 * <ol>
 *   <li>剥离 think/thinking 思考标签（兼容尖括号与方括号两种形式）与 markdown 代码围栏
 *       （围栏语言标记如 json 通配识别），并截取最外层 JSON 主体；</li>
 *   <li>Jackson 直接解析；</li>
 *   <li>基础清理后重试：清除非法控制字符、成对智能引号转直引号、单引号键转双引号键、删除尾逗号；</li>
 *   <li>渐进式引号/括号修复后重试：状态机扫描补全未闭合字符串与括号，再尝试为漏写的逗号插逗号；</li>
 *   <li>正则按已知字段（detailed_description / entity_info 内各键）降级提取重组。</li>
 * </ol>
 * <p>全部失败时记录 WARN 并返回 {@link Optional#empty()}，由调用方走确定性兜底；
 * 本类不依赖任何 infrastructure 组件，可离线单测。</p>
 *
 * @author DeepDataAgent
 */
public final class MultimodalJsonParser {

    /** 日志器 */
    private static final Logger log = LoggerFactory.getLogger(MultimodalJsonParser.class);

    /** 共享 JSON 解析器（线程安全） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 字段名：视觉/内容详描 */
    public static final String FIELD_DETAILED_DESCRIPTION = "detailed_description";
    /** 字段名：实体信息嵌套对象 */
    public static final String FIELD_ENTITY_INFO = "entity_info";
    /** 字段名：实体名 */
    public static final String FIELD_ENTITY_NAME = "entity_name";
    /** 字段名：实体类型 */
    public static final String FIELD_ENTITY_TYPE = "entity_type";
    /** 字段名：摘要 */
    public static final String FIELD_SUMMARY = "summary";

    /** 抽取正则扫描的已知字段（顶层 + entity_info 嵌套统一扁平提取） */
    private static final List<String> KNOWN_FIELDS = List.of(
            FIELD_DETAILED_DESCRIPTION, FIELD_ENTITY_NAME, FIELD_ENTITY_TYPE, FIELD_SUMMARY);

    /** 思考内容开标签（thinking 形式，尖括号用 unicode 转义避免文档工具剥离） */
    private static final String THINK_OPEN_TAG = "\u003Cthinking\u003E";
    /** 方括号 think 开标签（部分模型输出 [think] 形式） */
    private static final String THINK_OPEN_TAG_BRACKET = "[think]";

    /** 成对智能引号 → 直引号（避免破坏 JSON 字符串定界） */
    private static final String[][] SMART_QUOTE_PAIRS = {
            {"\u201C", "\""}, {"\u201D", "\""}, {"\u201E", "\""},
            {"\u2018", "'"}, {"\u2019", "'"}, {"\u201A", "'"}
    };

    /** markdown 代码围栏起始行：三及以上反引号 + 任意语言标记 */
    private static final Pattern FENCE_OPEN_PATTERN = Pattern.compile("^\\s*`{3,}[^`\\r\\n]*\\r?\\n");
    /** markdown 代码围栏结束行：整行仅由反引号组成 */
    private static final Pattern FENCE_CLOSE_PATTERN = Pattern.compile("\\r?\\n[ \\t]*`{3,}[ \\t]*");
    /** 非法控制字符（保留制表/换行/回车） */
    private static final Pattern CONTROL_CHARS_PATTERN =
            Pattern.compile("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]");
    /** 单引号包裹的键 → 双引号键 */
    private static final Pattern SINGLE_QUOTED_KEY_PATTERN = Pattern.compile("'([^'\\r\\n']*)'\\s*:");
    /** 对象/数组收尾前的尾逗号 */
    private static final Pattern TRAILING_COMMA_PATTERN = Pattern.compile(",\\s*([}\\]])");
    /** 裸键补引号：结构符（{ , [）后未加引号的标识符键 */
    private static final Pattern BARE_KEY_PATTERN =
            Pattern.compile("([{,\\[]\\s*)([A-Za-z_][A-Za-z0-9_]*)(\\s*:)");
    /** 漏写逗号：字符串/数字/闭合括号后紧跟换行再接开引号 */
    private static final Pattern MISSING_COMMA_PATTERN = Pattern.compile(
            "((?:\"[^\"\\r\\n]*\"|\\d+(?:\\.\\d+)?|[}\\]]))(\\s*\\r?\\n\\s*)(?=\")");
    /** 字段严格抽取模板：字符串值转义安全（可含转义引号） */
    private static final String STRICT_FIELD_VALUE_PATTERN_TEMPLATE =
            "\"%s\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"";
    /** 字段宽松抽取模板：到下一个引号为止（应对值内引号未转义） */
    private static final String LOOSE_FIELD_VALUE_PATTERN_TEMPLATE = "\"%s\"\\s*:\\s*\"([^\"]*)\"";
    /** 思考内容对（尖括号与方括号两种闭标签） */
    private static final Pattern THINK_PAIR_PATTERN = Pattern.compile(
            "(?:\\u003Cthink(?:ing)?\\u003E).*?(?:\\u003C/think(?:ing)?\\u003E|\\[/think\\])", Pattern.DOTALL);

    /** JSON 结构栈帧：对象 */
    private static final int SCOPE_OBJECT = 1;
    /** JSON 结构栈帧：数组 */
    private static final int SCOPE_ARRAY = 2;

    /** 失败日志中原文摘要的最大长度 */
    private static final int LOG_ABBREVIATE_WIDTH = 200;

    /**
     * 工具类禁止实例化。
     */
    private MultimodalJsonParser() {
    }

    /**
     * 容错解析 LLM 返回文本中的 JSON 对象/数组。
     *
     * @param rawText LLM 原始返回文本（可为 null/空白）
     * @return 解析成功的 JSON 节点；全部降级链失败返回 {@link Optional#empty()}
     */
    public static Optional<JsonNode> parse(String rawText) {
        if (StringUtils.isBlank(rawText)) {
            return Optional.empty();
        }
        String candidate = stripThinkingAndFences(rawText);
        if (StringUtils.isBlank(candidate)) {
            log.warn("多模态 JSON 解析失败：剥离 thinking/围栏后无 JSON 主体，原文前缀=[{}]",
                    StringUtils.abbreviate(rawText, LOG_ABBREVIATE_WIDTH));
            return Optional.empty();
        }
        JsonNode direct = tryParse(candidate);
        if (ObjectUtils.isNotEmpty(direct)) {
            return Optional.of(direct);
        }
        String cleaned = basicCleanup(candidate);
        JsonNode basic = tryParse(cleaned);
        if (ObjectUtils.isNotEmpty(basic)) {
            return Optional.of(basic);
        }
        String repaired = repairStructure(cleaned);
        JsonNode repairedNode = tryParse(repaired);
        if (ObjectUtils.isNotEmpty(repairedNode)) {
            return Optional.of(repairedNode);
        }
        String quotedKeys = quoteBareKeys(repaired);
        JsonNode quotedNode = tryParse(quotedKeys);
        if (ObjectUtils.isNotEmpty(quotedNode)) {
            return Optional.of(quotedNode);
        }
        JsonNode insertedComma = tryParse(insertMissingCommas(quotedKeys));
        if (ObjectUtils.isNotEmpty(insertedComma)) {
            return Optional.of(insertedComma);
        }
        JsonNode extracted = extractFieldsViaRegex(candidate);
        if (ObjectUtils.isNotEmpty(extracted)) {
            return Optional.of(extracted);
        }
        log.warn("多模态 JSON 五级容错链全部失败，原文前缀=[{}]",
                StringUtils.abbreviate(rawText, LOG_ABBREVIATE_WIDTH));
        return Optional.empty();
    }

    /**
     * 剥离思考内容与 markdown 代码围栏，提取最外层 JSON 主体。
     * <p>处理顺序：成对 think 标签删除 → 孤儿 think 开标签（其前文为思考内容）丢弃 →
     * 围栏内文提取 → 首个左大括号/方括号至末个同类右括号截取。</p>
     *
     * @param rawText LLM 原始返回文本（可为 null）
     * @return 剥离后的候选文本；无 JSON 主体时为空串
     */
    public static String stripThinkingAndFences(String rawText) {
        if (StringUtils.isBlank(rawText)) {
            return StringUtils.EMPTY;
        }
        String text = THINK_PAIR_PATTERN.matcher(rawText).replaceAll(StringUtils.EMPTY);
        int openIdx = lastIndexOfFirst(text, THINK_OPEN_TAG, THINK_OPEN_TAG_BRACKET);
        if (openIdx >= 0) {
            text = text.substring(openIdx + longestOpenTagLength(text, openIdx));
        }
        text = extractFenceBody(text);
        return extractJsonBody(text);
    }

    /**
     * 一级解析：Jackson 直接读取，仅接受对象或数组根节点。
     *
     * @param candidate 候选 JSON 文本
     * @return 解析成功的节点；非法 JSON 返回 {@code null}（继续降级）
     */
    private static JsonNode tryParse(String candidate) {
        if (StringUtils.isBlank(candidate)) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(candidate);
            if (node.isObject() || node.isArray()) {
                return node;
            }
            return null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * 三级基础清理：控制字符、成对智能引号、单引号键、尾逗号。
     *
     * @param candidate 一级解析失败的 JSON 文本
     * @return 清理后的候选文本
     */
    private static String basicCleanup(String candidate) {
        String cleaned = CONTROL_CHARS_PATTERN.matcher(candidate).replaceAll(StringUtils.EMPTY);
        for (String[] pair : SMART_QUOTE_PAIRS) {
            cleaned = cleaned.replace(pair[0], pair[1]);
        }
        cleaned = SINGLE_QUOTED_KEY_PATTERN.matcher(cleaned).replaceAll("\"$1\":");
        cleaned = TRAILING_COMMA_PATTERN.matcher(cleaned).replaceAll("$1");
        return cleaned;
    }

    /**
     * 四级渐进式修复：状态机扫描字符串/转义/括号栈，补全未闭合的引号与括号。
     *
     * @param candidate 清理后仍非法的 JSON 文本
     * @return 结构补全文本（多余的闭合符按栈自动弹出匹配）
     */
    private static String repairStructure(String candidate) {
        StringBuilder repaired = new StringBuilder(candidate.length() + 16);
        Deque<Integer> scopeStack = new ArrayDeque<>();
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < candidate.length(); i++) {
            char ch = candidate.charAt(i);
            if (inString) {
                repaired.append(ch);
                if (escape) {
                    escape = false;
                } else if (ch == '\\') {
                    escape = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            switch (ch) {
                case '"' -> {
                    inString = true;
                    repaired.append(ch);
                }
                case '{' -> {
                    scopeStack.push(SCOPE_OBJECT);
                    repaired.append(ch);
                }
                case '[' -> {
                    scopeStack.push(SCOPE_ARRAY);
                    repaired.append(ch);
                }
                case '}', ']' -> closeScope(scopeStack, repaired, ch);
                default -> repaired.append(ch);
            }
        }
        if (inString) {
            repaired.append('"');
        }
        while (ObjectUtils.isNotEmpty(scopeStack)) {
            repaired.append(scopeStack.pop() == SCOPE_OBJECT ? '}' : ']');
        }
        return repaired.toString();
    }

    /**
     * 闭合一个作用域：若与栈顶类型不匹配，先自动补齐内层闭合符再闭合当前符号；
     * 栈空时忽略多余的闭合符（不写入）。
     *
     * @param scopeStack 作用域栈
     * @param repaired   修复缓冲
     * @param closing    当前遇到的闭合符
     */
    private static void closeScope(Deque<Integer> scopeStack, StringBuilder repaired, char closing) {
        if (ObjectUtils.isEmpty(scopeStack)) {
            return;
        }
        int top = scopeStack.peek();
        boolean mismatch = (closing == '}' && top != SCOPE_OBJECT) || (closing == ']' && top != SCOPE_ARRAY);
        if (mismatch) {
            scopeStack.pop();
            repaired.append(top == SCOPE_OBJECT ? '}' : ']');
            closeScope(scopeStack, repaired, closing);
            return;
        }
        scopeStack.pop();
        repaired.append(closing);
    }

    /**
     * 四级补救一：为结构符后未加引号的裸键补双引号。
     *
     * @param candidate 结构补全后仍非法的 JSON 文本
     * @return 补引号后的文本
     */
    private static String quoteBareKeys(String candidate) {
        return BARE_KEY_PATTERN.matcher(candidate).replaceAll("$1\"$2\"$3");
    }

    /**
     * 四级补救二：为「值结束 + 换行 + 新字符串键起始」处漏写的逗号插入逗号后重试。
     *
     * @param candidate 补引号后仍非法的 JSON 文本
     * @return 插逗号后的文本
     */
    private static String insertMissingCommas(String candidate) {
        return MISSING_COMMA_PATTERN.matcher(candidate).replaceAll("$1,$2");
    }

    /**
     * 五级降级：正则按已知字段提取并重组 JSON 对象（entity_name/entity_type/summary 归入 entity_info）。
     * <p>每个字段先按严格模式（转义安全）匹配，再按宽松模式（到下一引号为止）匹配；
     * 至少命中一个字段才视为降级成功。</p>
     *
     * @param candidate 剥离后的候选文本
     * @return 重组对象节点；无任何字段命中返回 {@code null}
     */
    private static JsonNode extractFieldsViaRegex(String candidate) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode entityInfo = null;
        for (String field : KNOWN_FIELDS) {
            String value = extractFieldValue(candidate, field);
            if (StringUtils.isEmpty(value)) {
                continue;
            }
            if (FIELD_DETAILED_DESCRIPTION.equals(field)) {
                root.put(field, value);
            } else {
                if (ObjectUtils.isEmpty(entityInfo)) {
                    entityInfo = root.putObject(FIELD_ENTITY_INFO);
                }
                entityInfo.put(field, value);
            }
        }
        if (root.isEmpty()) {
            return null;
        }
        return root;
    }

    /**
     * 单字段降级提取：严格模式优先，失败退宽松模式。
     *
     * @param candidate 候选文本
     * @param field     字段名
     * @return 字段值；未命中返回 {@code null}
     */
    private static String extractFieldValue(String candidate, String field) {
        Matcher strict = Pattern.compile(String.format(STRICT_FIELD_VALUE_PATTERN_TEMPLATE, field))
                .matcher(candidate);
        if (strict.find()) {
            return unescapeJsonString(strict.group(1));
        }
        Matcher loose = Pattern.compile(String.format(LOOSE_FIELD_VALUE_PATTERN_TEMPLATE, field))
                .matcher(candidate);
        if (loose.find()) {
            return loose.group(1);
        }
        return null;
    }

    /**
     * JSON 字符串值反转义（降级提取链使用）：常见转义序列还原。
     *
     * @param raw 原始捕获值
     * @return 反转义后的值
     */
    private static String unescapeJsonString(String raw) {
        return raw.replace("\\n", "\n").replace("\\t", "\t").replace("\\r", "\r")
                .replace("\\\"", "\"").replace("\\\\", "\\");
    }

    /**
     * 提取围栏代码块正文：存在开围栏时取第一个围栏块内部（未闭合围栏取开围栏以后全部）。
     *
     * @param text 剥离 think 后的文本
     * @return 围栏内文或原文
     */
    private static String extractFenceBody(String text) {
        Matcher open = FENCE_OPEN_PATTERN.matcher(text);
        if (!open.find()) {
            return text;
        }
        String afterOpen = text.substring(open.end());
        Matcher close = FENCE_CLOSE_PATTERN.matcher(afterOpen);
        if (close.find()) {
            return afterOpen.substring(0, close.start());
        }
        return afterOpen;
    }

    /**
     * 截取最外层 JSON 主体：取首个出现的大括号或方括号，截到最后一个同类右括号。
     *
     * @param text 候选文本
     * @return JSON 主体；无起始括号或右括号缺失返回空串
     */
    private static String extractJsonBody(String text) {
        int braceStart = text.indexOf('{');
        int bracketStart = text.indexOf('[');
        int start = firstNonNegative(braceStart, bracketStart);
        if (start < 0) {
            return StringUtils.EMPTY;
        }
        char endChar = text.charAt(start) == '{' ? '}' : ']';
        int end = text.lastIndexOf(endChar);
        if (end <= start) {
            return StringUtils.EMPTY;
        }
        return text.substring(start, end + 1);
    }

    /**
     * 返回多个候选标签在文本中最后出现的最靠后位置（孤儿开标签截断用）。
     *
     * @param text 待扫描文本
     * @param tags 候选标签
     * @return 最靠后的下标，均不存在返回 -1
     */
    private static int lastIndexOfFirst(String text, String... tags) {
        int best = -1;
        for (String tag : tags) {
            int idx = text.lastIndexOf(tag);
            if (idx > best) {
                best = idx;
            }
        }
        return best;
    }

    /**
     * 取命中位置处最长的开标签长度（避免截断标签残余字符）。
     *
     * @param text 待扫描文本
     * @param idx  命中下标
     * @return 标签长度（均未命中返回 0）
     */
    private static int longestOpenTagLength(String text, int idx) {
        List<String> tags = List.of(THINK_OPEN_TAG, THINK_OPEN_TAG_BRACKET);
        int max = 0;
        for (String tag : tags) {
            if (text.startsWith(tag, idx) && tag.length() > max) {
                max = tag.length();
            }
        }
        return max;
    }

    /**
     * 两个下标取非负较小者（-1 表示不存在）。
     *
     * @param a 下标 a
     * @param b 下标 b
     * @return 非负较小下标；均为 -1 返回 -1
     */
    private static int firstNonNegative(int a, int b) {
        if (a < 0) {
            return b;
        }
        if (b < 0) {
            return a;
        }
        return Math.min(a, b);
    }
}
