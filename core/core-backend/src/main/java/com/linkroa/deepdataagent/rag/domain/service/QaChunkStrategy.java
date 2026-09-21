package com.linkroa.deepdataagent.rag.domain.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkroa.deepdataagent.rag.domain.model.ChunkVO;
import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import com.linkroa.deepdataagent.rag.domain.service.DelimiterParser.Unit;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 问答对分块策略（method={@code qa}）：六路固定优先级级联识别内容形状，命中即止。
 * <p>级联顺序：① JSON 路 → ② 标记交替路 → ③ 表格路 → ④ Markdown 问题栈路 → ⑤ 无标记交替路
 * → ⑥ 整块兜底。识别仅基于解析后的文本内容，不依据文件后缀。任一路线产出为空列表时继续尝试
 * 下一级；全部不命中走整块兜底（策略不变量：SHALL NOT 返回空列表）。</p>
 * <ul>
 *   <li>① JSON 路：文本可解析为含 question/answer 键的对象（或数组）时逐对提取；
 *       similar_questions、category、tags 等扩展字段仅写入 chunk 元数据，不拼接进块正文；</li>
 *   <li>② 标记交替路：行首锚定标记词（词族见 {@link #QA_TOKENS}）且其后必须为冒号（全/半角）
 *       或制表符（禁止仅空格，防英文散文行 "A good day" 误报）；Q 标记开新对、A 标记起答案、
 *       无标记续行并入答案、单行内 Q 段与 A 段拆分；启用需至少各出现一次 Q 与 A 标记；
 *       仅问题无答案的对丢弃；</li>
 *   <li>③ 表格路：tab 优先探测分隔符，成立阈值为「非空行中 ≥80% 可切出 ≥2 列」；前两列为
 *       问题/答案，第 3 列起进元数据；首行前两列命中表头词族时跳过表头；</li>
 *   <li>④ 问题栈路：多级 Markdown 标题入栈，问题为整条标题路径；{@code ---} 水平分隔线行
 *       不并入答案；答案为空的裸标题对丢弃；</li>
 *   <li>⑤ 无标记交替路：短行（≤50 token）且以 ？/? 结尾或以疑问词开头视为问题起点；
 *       识别成对数 &lt;2 整体放弃落兜底；</li>
 *   <li>⑥ 兜底：整块 1 chunk。</li>
 * </ul>
 * <p>输出归一：中文内容 {@code 问题：{q}⇥回答：{a}}、英文内容 {@code Question: {q}⇥Answer: {a}}
 * （⇥ 为制表符）；语言判定按问答对文本的 ASCII 字符占比（接口无 language 入参，不改签名）。
 * 本策略不消费 {@code params} 的 token 预算与分隔符（QA 级联自带形状识别）。</p>
 */
@Component
public class QaChunkStrategy implements ChunkStrategy {

    /** 日志器（JSON 路解析失败降级留痕）。 */
    private static final Logger log = LoggerFactory.getLogger(QaChunkStrategy.class);

    /** 策略方法名（注册表键，与 {@code ChunkStrategyRegistry} 映射一致）。 */
    public static final String METHOD = "qa";

    /** JSON 解析器（无状态线程安全，仅用于 QA JSON 路形状探测与提取）。 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 问题侧标记词族（不分大小写；多字词必须排在其短前缀之前，保证回溯匹配取最长）。 */
    private static final String Q_MARKER_FAMILY = "问题|question|user|问|Q";

    /** 答案侧标记词族（不分大小写；同上排序约束）。 */
    private static final String A_MARKER_FAMILY = "答案|回答|answer|assistant|A|答";

    /** Q/A 标记词族全集（RM_QA_PREFIX 与行首标记正则共用，保证词族统一维护）。 */
    private static final String QA_TOKENS = Q_MARKER_FAMILY + "|" + A_MARKER_FAMILY;

    /** 剥离 Q/A 前缀（参考 rmQAPrefix）：词族 + {@code [\t:： ]} 收尾，仅行首一次。 */
    private static final Pattern RM_QA_PREFIX = Pattern.compile(
            "(?i)^(" + QA_TOKENS + ")[\\t:： ]+");

    /** 行首 Q/A 标记（分组 1 为标记词）：其后必须为冒号（全/半角）或制表符，禁止仅空格。 */
    private static final Pattern QA_LINE_MARKER = Pattern.compile(
            "(?i)^\\s*(" + QA_TOKENS + ")\\s*[:：\\t]");

    /** 单行内嵌答案标记（用于 "Q: x A: y" 拆分）：标记前须为行首或非字母数字下划线字符。 */
    private static final Pattern EMBEDDED_A_MARKER = Pattern.compile(
            "(?i)(^|[^\\p{L}0-9_])(" + A_MARKER_FAMILY + ")\\s*[:：\\t]");

    /** 单行内嵌问题标记（用于 "A: x Q: y" 拆分）。 */
    private static final Pattern EMBEDDED_Q_MARKER = Pattern.compile(
            "(?i)(^|[^\\p{L}0-9_])(" + Q_MARKER_FAMILY + ")\\s*[:：\\t]");

    /** 问题标记词集合（小写全量，供 {@link #isQuestionMarker} 分类判定）。 */
    private static final Set<String> Q_MARKER_TOKENS =
            Set.of("问题", "question", "user", "问", "q");

    /** 表头词集合：答案列（小写全量，前两列同时命中问答词族判定为表头行）。 */
    private static final Set<String> A_MARKER_TOKENS =
            Set.of("答案", "回答", "answer", "assistant", "答", "a");

    /** 多级 Markdown 标题（分组 1 为 {@code #} 个数，分组 2 为标题文本）。 */
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s*(.*)$");

    /** Markdown 水平分隔线行（3 个及以上连续减号，trim 后全行匹配），问题栈路不并入答案。 */
    private static final Pattern HORIZONTAL_RULE = Pattern.compile("-{3,}");

    /** 输出标签（中文问题前缀）。 */
    private static final String LABEL_Q_CN = "问题：";

    /** 输出标签（中文回答前缀）。 */
    private static final String LABEL_A_CN = "回答：";

    /** 输出标签（英文问题前缀，含尾随空格，对齐 ragflow "Question: "）。 */
    private static final String LABEL_Q_EN = "Question: ";

    /** 输出标签（英文回答前缀，含尾随空格，对齐 ragflow "Answer: "）。 */
    private static final String LABEL_A_EN = "Answer: ";

    /** 问答对正文问题与回答间的字段分隔符（⇥ 制表符）。 */
    private static final String FIELD_SEPARATOR = "\t";

    /** 答案多行拼接符。 */
    private static final String ANSWER_LINE_JOINER = "\n";

    /** 代码围栏标记。 */
    private static final String CODE_FENCE = "```";

    /** 无分隔符标记（表格路探测返回 {@code '\0'} 表示无候选）。 */
    private static final char NO_DELIM = '\0';

    /** 表格路成立阈值：非空行中可切出 ≥2 列的行占比下限（杜绝少量单逗号散文误判）。 */
    private static final double TABLE_COLUMN_RATIO_THRESHOLD = 0.8d;

    /** 表格路最少列数。 */
    private static final int TABLE_MIN_COLUMNS = 2;

    /** 无标记交替路问题行 token 上限（超过视为正文而非问题起点）。 */
    private static final int HEURISTIC_QUESTION_TOKEN_LIMIT = 50;

    /** 无标记交替路最少成对数（低于该值整体放弃落兜底，宁兜底不猜错）。 */
    private static final int HEURISTIC_MIN_PAIRS = 2;

    /** 无标记交替路问题行结尾疑问标记。 */
    private static final String[] HEURISTIC_QUESTION_ENDINGS = {"？", "?"};

    /** 无标记交替路问题行开头疑问词族。 */
    private static final String[] HEURISTIC_QUESTION_PREFIXES =
            {"如何", "怎样", "为什么", "是什么", "哪些", "能否", "可以"};

    /** 英文判定阈值：问答对文本非空白字符中 ASCII 占比达到该值视为英文内容。 */
    private static final double ASCII_EN_RATIO_THRESHOLD = 0.5d;

    /** JSON 字段：问题键。 */
    private static final String JSON_KEY_QUESTION = "question";

    /** JSON 字段：答案键。 */
    private static final String JSON_KEY_ANSWER = "answer";

    /** meta 键：JSON 相似问法（源字段透传，命名风格对齐 MultimodalMetaKeys 的 snake_case）。 */
    private static final String META_KEY_SIMILAR_QUESTIONS = "similar_questions";

    /** meta 键：JSON 分类。 */
    private static final String META_KEY_CATEGORY = "category";

    /** meta 键：JSON 标签。 */
    private static final String META_KEY_TAGS = "tags";

    /** meta 键：表格路第 3 列起的附加列（下划线前缀为策略注入键，风格对齐 MultimodalMetaKeys）。 */
    private static final String META_KEY_EXTRA_COLUMNS = "_qa_extra_columns";

    /** 扩展字段白名单（JSON 路仅这些键入 meta，其余核心键跳过、任意扩展键同样并入 meta）。 */
    private static final String[] JSON_CORE_KEYS = {JSON_KEY_QUESTION, JSON_KEY_ANSWER};

    @Override
    public String method() {
        return METHOD;
    }

    @Override
    public boolean supports(ContentBlockVO block) {
        return ContentBlockVO.TYPE_TEXT.equals(block.type());
    }

    /**
     * 对单个文本块执行六路级联问答切分。
     *
     * @param block   待切分文本内容块，非空
     * @param params  分块参数（QA 级联自带形状识别，本参数不消费）
     * @param counter 真实 token 计数端口（无标记交替路短行判定与块计数使用）
     * @return 每问答对 1 chunk 的结果列表；全部路线不命中时为整块兜底的单 chunk（非空）
     * @throws IllegalArgumentException block 或 counter 为 null 时抛出
     */
    @Override
    public List<ChunkVO> split(ContentBlockVO block, ChunkParams params, TokenCounter counter) {
        if (ObjectUtils.isEmpty(block)) {
            throw new IllegalArgumentException("QA 分块内容块不能为空");
        }
        if (ObjectUtils.isEmpty(counter)) {
            throw new IllegalArgumentException("QA 分块 token 计数端口不能为空");
        }
        String normalized = StringUtils.defaultString(block.text()).replace("\r\n", "\n").replace("\r", "\n");
        if (StringUtils.isBlank(DelimiterParser.stripTags(normalized))) {
            return fallbackWholeBlock(block, counter);
        }
        List<String> lines = List.of(normalized.split("\n", -1));
        List<QaPair> pairs = cascadePairs(normalized, lines, counter);
        if (CollectionUtils.isEmpty(pairs)) {
            return fallbackWholeBlock(block, counter);
        }
        List<Unit> units = new ArrayList<>();
        for (QaPair pair : pairs) {
            String content = formatPair(pair);
            units.add(new Unit(content, counter.count(content), false, enrichMeta(block, pair)));
        }
        return DelimiterParser.toChunkVOs(units, counter);
    }

    /**
     * 六路级联（命中即止）：任一路线产出为空列表则继续下一级。
     *
     * @param normalized 换行归一化后的块文本
     * @param lines      按行拆分列表
     * @param counter    token 计数端口
     * @return 命中的路线产出的问答对列表；全部不命中返回空列表（调用方落兜底）
     */
    private List<QaPair> cascadePairs(String normalized, List<String> lines, TokenCounter counter) {
        List<QaPair> pairs = extractJsonPairs(normalized);
        if (CollectionUtils.isNotEmpty(pairs)) {
            return pairs;
        }
        pairs = extractMarkedPairs(lines);
        if (CollectionUtils.isNotEmpty(pairs)) {
            return pairs;
        }
        pairs = extractTablePairs(lines);
        if (CollectionUtils.isNotEmpty(pairs)) {
            return pairs;
        }
        if (containsMarkdownHeading(lines)) {
            pairs = buildFromMarkdown(lines);
            if (CollectionUtils.isNotEmpty(pairs)) {
                return pairs;
            }
        }
        return extractHeuristicPairs(lines, counter);
    }

    /**
     * ① JSON 路：解析含 question/answer 键的对象或数组并逐对提取。
     * <p>文本 trim 后须以 <code>{</code> 或 <code>[</code> 开头且可被 Jackson 解析；解析失败或
     * 无有效对时返回空列表落下一级。similar_questions/category/tags 等扩展字段仅写入对元数据。</p>
     *
     * @param text 归一化后的块文本
     * @return 问答对列表（每对象 1 对）；形状不命中返回空列表
     */
    private List<QaPair> extractJsonPairs(String text) {
        String trimmed = text.trim();
        if (!StringUtils.startsWithAny(trimmed, "{", "[")) {
            return List.of();
        }
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(trimmed);
        } catch (JsonProcessingException e) {
            log.debug("QA 分块 JSON 路解析失败，降级后续形状识别: {}", e.getMessage());
            return List.of();
        }
        List<JsonNode> nodes = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(nodes::add);
        } else if (root.isObject()) {
            nodes.add(root);
        } else {
            return List.of();
        }
        List<QaPair> pairs = new ArrayList<>();
        for (JsonNode node : nodes) {
            if (!node.isObject() || !node.hasNonNull(JSON_KEY_QUESTION) || !node.hasNonNull(JSON_KEY_ANSWER)) {
                continue;
            }
            String question = jsonText(node.get(JSON_KEY_QUESTION));
            String answer = jsonText(node.get(JSON_KEY_ANSWER));
            if (StringUtils.isBlank(question) || StringUtils.isBlank(answer)) {
                continue;
            }
            pairs.add(new QaPair(question.trim(), answer.trim(), jsonExtensionMeta(node)));
        }
        return pairs;
    }

    /**
     * 提取 JSON 对象的扩展字段元数据：除 question/answer 外的全部键（含
     * similar_questions/category/tags）原样入 meta，不拼接进块正文。
     *
     * @param node 单个问答对象节点
     * @return 扩展字段元数据（无扩展字段返回空 Map）
     */
    private static Map<String, Object> jsonExtensionMeta(JsonNode node) {
        Map<String, Object> meta = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (StringUtils.equalsAny(field.getKey(), JSON_CORE_KEYS)) {
                continue;
            }
            meta.put(field.getKey(), jsonMetaValue(field.getValue()));
        }
        return meta;
    }

    /**
     * JSON 字段文本化：数组逐元素换行拼接，标量取文本值，空节点返回空串。
     *
     * @param node 字段节点（可为 null）
     * @return 字段文本
     */
    private static String jsonText(JsonNode node) {
        if (ObjectUtils.isEmpty(node) || node.isNull()) {
            return StringUtils.EMPTY;
        }
        if (node.isArray()) {
            List<String> parts = new ArrayList<>();
            node.forEach(item -> parts.add(item.asText()));
            return String.join(ANSWER_LINE_JOINER, parts);
        }
        return node.asText();
    }

    /**
     * JSON 扩展字段值转 meta 值：数组转字符串列表，嵌套对象序列化保留，标量取文本。
     *
     * @param node 字段节点
     * @return meta 值
     */
    private static Object jsonMetaValue(JsonNode node) {
        if (node.isArray()) {
            List<String> list = new ArrayList<>();
            node.forEach(item -> list.add(item.asText()));
            return list;
        }
        if (node.isObject()) {
            return node.toString();
        }
        return node.asText();
    }

    /**
     * ② 标记交替路：按行首 Q/A 标记（其后必须为冒号全/半角或制表符）解析问答对。
     * <p>Q 标记开新对、A 标记起内容入答案、无标记后续行为答案续行、单行内同时含 Q 段与
     * A 段拆分；启用需至少各出现一次 Q 与 A 标记，否则整体不命中；仅问题无答案的对丢弃。</p>
     *
     * @param lines 归一化后的行列表
     * @return 问答对列表；未启用（缺 Q 或缺 A 标记）返回空列表
     */
    private static List<QaPair> extractMarkedPairs(List<String> lines) {
        List<QaPair> pairs = new ArrayList<>();
        String question = null;
        List<String> answer = new ArrayList<>();
        boolean sawQuestionMarker = false;
        boolean sawAnswerMarker = false;
        for (String raw : lines) {
            String visible = StringUtils.stripToEmpty(DelimiterParser.stripTags(raw)).trim();
            if (StringUtils.isBlank(visible)) {
                continue;
            }
            Matcher marker = QA_LINE_MARKER.matcher(visible);
            if (!marker.lookingAt()) {
                addIfNotBlank(answer, visible);
                continue;
            }
            String token = marker.group(1).toLowerCase(Locale.ROOT);
            String rest = visible.substring(marker.end()).trim();
            if (isQuestionMarker(token)) {
                sawQuestionMarker = true;
                flush(pairs, question, answer, null);
                Matcher embedded = EMBEDDED_A_MARKER.matcher(rest);
                answer = new ArrayList<>();
                if (embedded.find()) {
                    sawAnswerMarker = true;
                    question = rest.substring(0, embedded.start()).trim();
                    addIfNotBlank(answer, rest.substring(embedded.end()).trim());
                } else {
                    question = rest;
                }
            } else {
                sawAnswerMarker = true;
                Matcher embedded = EMBEDDED_Q_MARKER.matcher(rest);
                if (embedded.find()) {
                    addIfNotBlank(answer, rest.substring(0, embedded.start()).trim());
                    flush(pairs, question, answer, null);
                    question = rest.substring(embedded.end()).trim();
                    answer = new ArrayList<>();
                } else {
                    addIfNotBlank(answer, rest);
                }
            }
        }
        flush(pairs, question, answer, null);
        if (!sawQuestionMarker || !sawAnswerMarker) {
            return new ArrayList<>();
        }
        return pairs;
    }

    /**
     * 判定标记词是否属于问题侧词族（入参须已小写）。
     *
     * @param token 行首命中的标记词（小写）
     * @return true 表示问题侧标记（否则为答案侧标记）
     */
    private static boolean isQuestionMarker(String token) {
        return Q_MARKER_TOKENS.contains(token);
    }

    /**
     * ③ 表格路：以「非空行中 ≥80% 可切出 ≥2 列」为成立阈值探测分隔符（tab 优先）并成对。
     * <p>成立时前两列为问题/答案，第 3 列起进该对元数据；首行前两列命中表头词族跳过表头；
     * 不足 2 列的行作为当前答案续行。</p>
     *
     * @param lines 归一化后的行列表
     * @return 问答对列表；阈值不成立返回空列表
     */
    private static List<QaPair> extractTablePairs(List<String> lines) {
        List<String> visible = visibleNonBlankLines(lines);
        if (CollectionUtils.isEmpty(visible)) {
            return new ArrayList<>();
        }
        char delim = detectDelimiter(visible);
        if (delim == NO_DELIM) {
            return new ArrayList<>();
        }
        List<QaPair> pairs = new ArrayList<>();
        String question = null;
        List<String> answer = new ArrayList<>();
        Map<String, Object> pendingMeta = null;
        boolean headerChecked = false;
        for (String line : visible) {
            String[] parts = line.split(String.valueOf(delim), -1);
            if (parts.length < TABLE_MIN_COLUMNS) {
                addIfNotBlank(answer, line);
                continue;
            }
            if (!headerChecked) {
                headerChecked = true;
                if (isHeaderRow(parts[0], parts[1])) {
                    continue;
                }
            }
            flush(pairs, question, answer, pendingMeta);
            question = rmQAPrefix(parts[0].trim());
            answer = new ArrayList<>();
            addIfNotBlank(answer, rmQAPrefix(parts[1].trim()));
            List<String> extraColumns = extraColumns(parts);
            pendingMeta = CollectionUtils.isEmpty(extraColumns) ? null : Map.of(META_KEY_EXTRA_COLUMNS, extraColumns);
        }
        flush(pairs, question, answer, pendingMeta);
        return pairs;
    }

    /**
     * 分隔符探测（表格路）：tab 优先；成立条件为该分隔符可把 ≥
     * {@link #TABLE_COLUMN_RATIO_THRESHOLD} 比例的非空行切出 ≥2 列。
     *
     * @param visible 非空可见行列表
     * @return 探测出的分隔符字符；均不达标返回 {@link #NO_DELIM}
     */
    private static char detectDelimiter(List<String> visible) {
        int total = visible.size();
        int tabCount = countMultiColumnLines(visible, '\t');
        if (tabCount >= total * TABLE_COLUMN_RATIO_THRESHOLD) {
            return '\t';
        }
        int commaCount = countMultiColumnLines(visible, ',');
        if (commaCount >= total * TABLE_COLUMN_RATIO_THRESHOLD) {
            return ',';
        }
        return NO_DELIM;
    }

    /**
     * 统计以指定分隔符可切出 ≥2 列的行数。
     *
     * @param lines 非空可见行列表
     * @param delim 分隔符字符
     * @return 命中行数
     */
    private static int countMultiColumnLines(List<String> lines, char delim) {
        int count = 0;
        for (String line : lines) {
            if (line.split(String.valueOf(delim), -1).length >= TABLE_MIN_COLUMNS) {
                count++;
            }
        }
        return count;
    }

    /**
     * 判定行首两列是否为表头（前两列分别命中问题/答案词族，如 question/answer、问题/答案）。
     *
     * @param firstColumn  第一列原文
     * @param secondColumn 第二列原文
     * @return true 表示为表头行，应跳过
     */
    private static boolean isHeaderRow(String firstColumn, String secondColumn) {
        String first = StringUtils.stripToEmpty(firstColumn).trim().toLowerCase(Locale.ROOT);
        String second = StringUtils.stripToEmpty(secondColumn).trim().toLowerCase(Locale.ROOT);
        return isQuestionMarker(first) && A_MARKER_TOKENS.contains(second);
    }

    /**
     * 提取表格行第 3 列起的附加列（trim 并过滤空白列）。
     *
     * @param parts 切列结果
     * @return 附加列值列表（不足 3 列返回空列表）
     */
    private static List<String> extraColumns(String[] parts) {
        List<String> extras = new ArrayList<>();
        for (int i = TABLE_MIN_COLUMNS; i < parts.length; i++) {
            String value = parts[i].trim();
            if (StringUtils.isNotBlank(value)) {
                extras.add(value);
            }
        }
        return extras;
    }

    /**
     * ④ 问题栈路构建：标题入栈、同级/更高层弹栈；当前问题 = {@code \n} 连接整条问题栈；
     * 标题下正文并入答案；代码围栏内跳过；{@code ---} 水平分隔线行不并入答案；
     * 答案为空的裸标题对丢弃（见 {@link #flush}）。
     *
     * @param lines 归一化后的行列表
     * @return 问答对列表（每对携带整条标题路径为问题）
     */
    private static List<QaPair> buildFromMarkdown(List<String> lines) {
        List<QaPair> pairs = new ArrayList<>();
        List<Map.Entry<Integer, String>> stack = new ArrayList<>();
        String question = null;
        List<String> answer = new ArrayList<>();
        boolean inCode = false;
        for (String line : lines) {
            String visible = StringUtils.stripToEmpty(DelimiterParser.stripTags(line)).trim();
            if (StringUtils.startsWith(visible, CODE_FENCE)) {
                inCode = !inCode;
                continue;
            }
            if (inCode) {
                if (ObjectUtils.isNotEmpty(question)) {
                    answer.add(visible);
                }
                continue;
            }
            if (HORIZONTAL_RULE.matcher(visible).matches()) {
                continue;
            }
            Matcher matcher = HEADING.matcher(visible);
            if (matcher.matches()) {
                String heading = rmQAPrefix(matcher.group(2));
                if (StringUtils.isBlank(heading)) {
                    continue;
                }
                flush(pairs, question, answer, null);
                int level = matcher.group(1).length();
                while (!stack.isEmpty() && level <= stack.get(stack.size() - 1).getKey()) {
                    stack.remove(stack.size() - 1);
                }
                stack.add(Map.entry(level, heading));
                question = String.join(ANSWER_LINE_JOINER, stack.stream().map(Map.Entry::getValue).toList());
                answer = new ArrayList<>();
            } else if (ObjectUtils.isNotEmpty(question)) {
                answer.add(visible);
            }
        }
        flush(pairs, question, answer, null);
        return pairs;
    }

    /**
     * 判定文本是否含 Markdown 标题（问题栈路的形状前置探测）。
     *
     * @param lines 归一化后的行列表
     * @return true 表示至少一行命中 {@code #} 标题
     */
    private static boolean containsMarkdownHeading(List<String> lines) {
        for (String line : lines) {
            String visible = StringUtils.stripToEmpty(DelimiterParser.stripTags(line)).trim();
            if (HEADING.matcher(visible).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * ⑤ 无标记交替路：短行（≤50 token）且以 ？/? 结尾或以疑问词开头视为问题起点，
     * 后续行为答案续行。识别成对数 &lt;2 时整体放弃（返回空列表落兜底，宁兜底不猜错）。
     *
     * @param lines   归一化后的行列表
     * @param counter token 计数端口
     * @return 问答对列表；成对数不足 2 返回空列表
     */
    private static List<QaPair> extractHeuristicPairs(List<String> lines, TokenCounter counter) {
        List<QaPair> pairs = new ArrayList<>();
        String question = null;
        List<String> answer = new ArrayList<>();
        for (String raw : lines) {
            String visible = StringUtils.stripToEmpty(DelimiterParser.stripTags(raw)).trim();
            if (StringUtils.isBlank(visible)) {
                continue;
            }
            if (isHeuristicQuestionStart(visible, counter)) {
                flush(pairs, question, answer, null);
                question = visible;
                answer = new ArrayList<>();
            } else if (ObjectUtils.isNotEmpty(question)) {
                answer.add(visible);
            }
        }
        flush(pairs, question, answer, null);
        return pairs.size() < HEURISTIC_MIN_PAIRS ? new ArrayList<>() : pairs;
    }

    /**
     * 判定无标记行是否构成问题起点：token 数 ≤50 且（以 ？/? 结尾或以疑问词开头）。
     *
     * @param line    可见行文本（trim 后）
     * @param counter token 计数端口
     * @return true 表示视为问题起点
     */
    private static boolean isHeuristicQuestionStart(String line, TokenCounter counter) {
        if (counter.count(line) > HEURISTIC_QUESTION_TOKEN_LIMIT) {
            return false;
        }
        return StringUtils.endsWithAny(line, HEURISTIC_QUESTION_ENDINGS)
                || StringUtils.startsWithAny(line, HEURISTIC_QUESTION_PREFIXES);
    }

    /**
     * 输出缓冲区刷新：问答齐备才产出对——问题为空或对答案为空（仅问题无答案 / 无正文裸标题）
     * 的对丢弃。
     *
     * @param pairs    结果列表
     * @param question 当前问题文本，可为 null
     * @param answer   当前答案行列表，可为 null
     * @param meta     该对的扩展元数据，可为 null
     */
    private static void flush(List<QaPair> pairs, String question, List<String> answer, Map<String, Object> meta) {
        if (StringUtils.isBlank(question)) {
            return;
        }
        String answerText = CollectionUtils.isEmpty(answer) ? StringUtils.EMPTY
                : String.join(ANSWER_LINE_JOINER, answer).trim();
        if (StringUtils.isBlank(answerText)) {
            return;
        }
        pairs.add(new QaPair(question.trim(), answerText, meta));
    }

    /**
     * 非空白时追加答案行。
     *
     * @param answer 答案行缓冲
     * @param line   待追加行
     */
    private static void addIfNotBlank(List<String> answer, String line) {
        if (StringUtils.isNotBlank(line)) {
            answer.add(line);
        }
    }

    /**
     * 提取剥离坐标标签并 trim 后的非空可见行列表。
     *
     * @param lines 原始行列表
     * @return 可见非空行列表
     */
    private static List<String> visibleNonBlankLines(List<String> lines) {
        List<String> visible = new ArrayList<>();
        for (String line : lines) {
            String stripped = StringUtils.stripToEmpty(DelimiterParser.stripTags(line)).trim();
            if (StringUtils.isNotBlank(stripped)) {
                visible.add(stripped);
            }
        }
        return visible;
    }

    /**
     * 问答对输出归一：按对文本 ASCII 占比选择中英文标签前缀，问题与回答以制表符分隔。
     *
     * @param pair 问答对
     * @return 归一后的 chunk 正文
     */
    private static String formatPair(QaPair pair) {
        if (isEnglishContent(pair.question() + pair.answer())) {
            return LABEL_Q_EN + pair.question() + FIELD_SEPARATOR + LABEL_A_EN + pair.answer();
        }
        return LABEL_Q_CN + pair.question() + FIELD_SEPARATOR + LABEL_A_CN + pair.answer();
    }

    /**
     * 语言判定：非空白字符中 ASCII 字符占比 ≥ {@link #ASCII_EN_RATIO_THRESHOLD} 视为英文内容。
     *
     * @param text 问答对拼接文本
     * @return true 表示英文内容（输出使用英文前缀）
     */
    private static boolean isEnglishContent(String text) {
        long total = text.chars().filter(cp -> !Character.isWhitespace(cp)).count();
        if (total == 0) {
            return false;
        }
        long ascii = text.chars().filter(cp -> !Character.isWhitespace(cp) && cp < 128).count();
        return (double) ascii / total >= ASCII_EN_RATIO_THRESHOLD;
    }

    /**
     * 剥离 Q/A 前缀（仅行首一次；无命中原样返回）。
     *
     * @param text 待剥前缀文本
     * @return 剥前缀后的文本
     */
    private static String rmQAPrefix(String text) {
        if (StringUtils.isBlank(text)) {
            return text;
        }
        return RM_QA_PREFIX.matcher(text).replaceFirst(StringUtils.EMPTY);
    }

    /**
     * 将问答对的扩展元数据合入来源块 meta，生成该 chunk 专属来源块（无元数据时保留原块）。
     *
     * @param block 原始内容块
     * @param pair  问答对
     * @return 携带合并后 meta 的来源块，或原块（对无扩展元数据时）
     */
    private static ContentBlockVO enrichMeta(ContentBlockVO block, QaPair pair) {
        if (ObjectUtils.isEmpty(pair.meta())) {
            return block;
        }
        Map<String, Object> merged = new HashMap<>();
        if (ObjectUtils.isNotEmpty(block.meta())) {
            merged.putAll(block.meta());
        }
        merged.putAll(pair.meta());
        return new ContentBlockVO(block.type(), block.text(), merged);
    }

    /**
     * ⑥ 整块兜底：无法识别任何问答形状时整块透传为单 chunk（策略不变量：不返回空列表）。
     *
     * @param block   原始内容块
     * @param counter token 计数端口
     * @return 单元素 chunk 列表
     */
    private static List<ChunkVO> fallbackWholeBlock(ContentBlockVO block, TokenCounter counter) {
        return DelimiterParser.toChunkVOs(List.of(DelimiterParser.passthroughUnit(block, counter)), counter);
    }

    /**
     * 级联中间态问答对（仅内存）。
     *
     * @param question 问题文本（非空）
     * @param answer   答案文本（非空，多行以 \n 拼接）
     * @param meta     扩展元数据（JSON 扩展字段 / 表格附加列），可为 null
     */
    private record QaPair(String question, String answer, Map<String, Object> meta) {
    }
}
