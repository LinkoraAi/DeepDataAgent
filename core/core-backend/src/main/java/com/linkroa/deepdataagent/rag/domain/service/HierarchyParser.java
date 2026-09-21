package com.linkroa.deepdataagent.rag.domain.service;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 标题层级检测与层级树合并工具（book / laws 共享，参考 ~）。
 * <p>职责链：全文 sections 组型投票 {@link #bulletsCategory}（五组模式取命中最多组）→
 * 组内首中模式标注行层级 {@link #detectLevelsInGroup}（组内序号即 1-based 层级）→
 * 目标层级由 {@link #mostLevel} 频率投票与「第 depth 小 distinct 层级」共同决定（落正文桶降一级）→
 * 按目标层级构建 Node 树（栈式）→ DFS 展平输出「标题路径 + 正文」前缀块 →
 * {@link #absorbShortBlocks} 按 {@link #HIERARCHICAL_ABSORB_THRESHOLD}(218) 吸收合并短块。</p>
 * <p>既有单行直判 {@link #detectLevels} 与其十条形态正则保留，作为组内层级标注的历史实现；
 * 坐标标签对判定不可见（先剥 {@code @@..##}）。</p>
 */
public final class HierarchyParser {

    /** 正文桶标记（参考 {@code bodyLevel = 1<<31 - 1}，不可能被当作标题的巨大值）。 */
    public static final int BODY_LEVEL = Integer.MAX_VALUE;

    /** 层级短块吸收阈值（books 层级合并）。 */
    public static final int HIERARCHICAL_ABSORB_THRESHOLD = 218;

    /** 法条模式合并深度（laws，参考 tree_merge depth=2）。 */
    public static final int LAWS_DEPTH = 2;

    /** 书籍模式合并深度（book，参考 hierarchical_merge depth=5）。 */
    public static final int BOOK_DEPTH = 5;

    /**
     * 标题组型模式表（参考 {@code BULLET_PATTERN} 五组，逐字照抄）。
     * <p>组内正则<b>有序</b>，一行取组内首中模式，其 1-based 序号即层级：
     * 组 0 中文法规类（分编/编/部分 → 章 → 节 → 条 → 中文数字圆括号）、
     * 组 1 数字层级类（第 N 章 → 第 N 节 → {@code 1.} → {@code 1.2} → {@code 1.2.3} → {@code 1.2.3.4}）、
     * 组 2 中文数字类（第 N 章 → 第 N 节 → {@code 一、} → {@code （一）} → {@code （1）}）、
     * 组 3 英文法规类（PART / Chapter / Section / Article）、组 4 Markdown（{@code #}~{@code ######}）。</p>
     * <p>匹配口径为「自行首起匹配、不要求整行匹配」，等价 Python {@code re.match}（见 {@link Pattern#lookingAt()}）；
     * Python 量词 {@code [0-9]{,2}} 在 Java 不支持，等价改写为 {@code [0-9]{0,2}}。</p>
     */
    public static final List<List<Pattern>> BULLET_PATTERN = List.of(
            patterns("第[零一二三四五六七八九十百0-9]+(分?编|部分)", "第[零一二三四五六七八九十百0-9]+章",
                    "第[零一二三四五六七八九十百0-9]+节", "第[零一二三四五六七八九十百0-9]+条",
                    "[\\(（][零一二三四五六七八九十百]+[\\)）]"),
            patterns("第[0-9]+章", "第[0-9]+节", "[0-9]{0,2}[\\. 、]",
                    "[0-9]{0,2}\\.[0-9]{0,2}[^a-zA-Z/%~-]", "[0-9]{0,2}\\.[0-9]{0,2}\\.[0-9]{0,2}",
                    "[0-9]{0,2}\\.[0-9]{0,2}\\.[0-9]{0,2}\\.[0-9]{0,2}"),
            patterns("第[零一二三四五六七八九十百0-9]+章", "第[零一二三四五六七八九十百0-9]+节",
                    "[零一二三四五六七八九十百]+[ 、]", "[\\(（][零一二三四五六七八九十百]+[\\)）]",
                    "[\\(（][0-9]{0,2}[)）]"),
            patterns("PART (ONE|TWO|THREE|FOUR|FIVE|SIX|SEVEN|EIGHT|NINE|TEN)",
                    "Chapter (I+V?|VI*|XI|IX|X)", "Section [0-9]+", "Article [0-9]+"),
            patterns("^#[^#]", "^##[^#]", "^###.*", "^####.*", "^#####.*", "^######.*"));

    /**
     * 非标题候选守卫（参考 {@code NOT_BULLET} 四条合并）：
     * 单独的 0 / "2026 年" 式数字密集行 / "1...." / "1.2.3中"。
     */
    private static final Pattern NOT_BULLET = Pattern.compile(
            "^0|^[0-9]+ +[0-9~个只-]|^[0-9]+\\.{2,}|^[0-9]+(\\.[0-9]+){2,}[的中]");

    /** 历史单行直判守卫（仅供 {@code detectLevel} 使用，整行 matches 口径，保持既有行为不变）。 */
    private static final Pattern HEADING_NOT_BULLET = Pattern.compile(
            "^0$|^\\d+ +\\d+[~个只-]?|^\\d+\\.{2,}|^\\d+(\\.\\d+){2,}[\u4e00-\u9fa5中]*");

    /** Markdown 标题：{@code #}~{@code ######}，level = # 个数。 */
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^#{1,6}(\\s|$)");

    /** 中文法规一级标题：编 / 部分 / 分则 / 分编。 */
    private static final Pattern CN_BIAN = Pattern.compile("^第[零〇一二三四五六七八九十百千0-9]+(编|部分|分则|分编)");

    /** 中文法规二级标题：章。 */
    private static final Pattern CN_ZHANG = Pattern.compile("^第[零〇一二三四五六七八九十百千0-9]+章");

    /** 中文法规三级标题：节。 */
    private static final Pattern CN_JIE = Pattern.compile("^第[零〇一二三四五六七八九十百千0-9]+节");

    /** 中文法规四级标题：条。 */
    private static final Pattern CN_TIAO = Pattern.compile("^第[零〇一二三四五六七八九十百千0-9]+条");

    /** 数字三级层级：{@code 1.2.3}。 */
    private static final Pattern DIGIT_LEVEL_3 = Pattern.compile("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");

    /** 数字二级层级：{@code 1.2}。 */
    private static final Pattern DIGIT_LEVEL_2 = Pattern.compile("^\\d{1,3}\\.\\d{1,3}");

    /** 数字条目：{@code 1.} / {@code 1、} / {@code 1)}。 */
    private static final Pattern NUM_ITEM = Pattern.compile("^\\d{1,3}[\\.、)](\\s|\\S)");

    /** 中文数字条目：{@code 一、} / {@code 二 }。 */
    private static final Pattern CN_ITEM = Pattern.compile("^[一二三四五六七八九十百]+[ 、](\\s|\\S)");

    /** 圆括号编号：{@code (1)} / {@code （一）}。 */
    private static final Pattern PAREN_ITEM = Pattern.compile("^[（(][0-9一二三四五六七八九十百]+[）)]");

    /** 目录标记行：整行为「目录 / 目次 / Contents / Table of Contents」（不分大小写，容忍中间空白）。 */
    private static final Pattern CONTENTS_MARKER = Pattern.compile(
            "^(?:目\\s*录|目\\s*次|contents|table\\s+of\\s+contents)$", Pattern.CASE_INSENSITIVE);

    /** 目录条目行尾形态：点线（≥2 个 {@code . … · - _ * =}）后跟 1~4 位页码（允许其间空白）。 */
    private static final Pattern TOC_LEADER_PAGE = Pattern.compile("[.…·\\-*_=]{2,}\\s*\\d{1,4}$");

    /** 目录候选段单段最大可见字符数：超出视为正文长段，目录段连续性在此中断。 */
    private static final int TOC_CANDIDATE_MAX_CHARS = 60;

    /** 目录段最少命中段数：低于该值不成段（正文偶发「……页码」行不被误杀）。 */
    private static final int TOC_MIN_RUN_SECTIONS = 3;

    /**
     * 目录段命中段数占全文可见段数的比例阈值。
     * <p>与 {@link #TOC_MIN_RUN_SECTIONS} 取大者，兼顾「小文档目录占比高」与
     * 「大文档目录绝对行数够」。数值为经验值，待真实语料微调。</p>
     */
    private static final double TOC_RUN_DOC_RATIO = 0.05D;

    /**
     * 目录候选段内部的命中占比阈值：段内命中「BULLET 行首 + 点线页码行尾」形态的段数
     * 须达该比例才整段剔除。数值为经验值，待真实语料微调。
     */
    private static final double TOC_RUN_INNER_RATIO = 0.6D;

    private HierarchyParser() {
    }

    /**
     * 层级行：单条文本记录及其绝对层级（{@link #BODY_LEVEL} 表示正文/非标题行）。
     *
     * @param level 层级数值（1 为最高级），BODY_LEVEL 表示正文
     * @param text  原始行文本（保留坐标标签，供下游剥离入元数据）
     */
    public record LevelLine(int level, String text) {
    }

    /**
     * 将内容块文本按行拆分并逐行判定层级（参考步骤 1/2）。
     * <p>过滤空文本 / 剥坐标后长度不足 2 的行 / 纯数字页码（参考 tree_merge 预处理）。
     * 判定先剥坐标标签（标题识别不受标签干扰），层级数值为绝对层级
     * （Markdown #→1..6、编/部分→1、章→2、节→3、条→4、数字分层→1..3、中文数字/圆括号→1）。</p>
     *
     * @param text 内容块文本（可能含 {@code @@页码\t左\t右\t上\t下##} 坐标标签）
     * @return 层级行列表（可能为空）
     */
    public static List<LevelLine> detectLevels(String text) {
        String normalized = text.replace("\r\n", "\n");
        List<LevelLine> out = new ArrayList<>();
        for (String line : normalized.split("\n", -1)) {
            String visible = DelimiterParser.stripTags(line).trim();
            if (StringUtils.isBlank(visible) || visible.length() <= 1
                    || visible.matches("\\p{Nd}+")) {
                continue;
            }
            out.add(new LevelLine(detectLevel(visible), line));
        }
        return out;
    }

    /**
     * 判定单行文本的绝对层级（两次扫描：标题形态 → 非标题守卫兜底为正文）。
     *
     * @param visible 已剥坐标标签的行文本（非空）
     * @return 层级数值；正文行返回 {@link #BODY_LEVEL}
     */
    private static int detectLevel(String visible) {
        var m = MARKDOWN_HEADING.matcher(visible);
        if (m.matches() && !HEADING_NOT_BULLET.matcher(visible).matches()) {
            int sharp = 0;
            for (int i = 0; i < visible.length() && visible.charAt(i) == '#'; i++) {
                sharp++;
            }
            return sharp;
        }
        if (CN_BIAN.matcher(visible).matches()) {
            return 1;
        }
        if (CN_ZHANG.matcher(visible).matches()) {
            return 2;
        }
        if (CN_JIE.matcher(visible).matches()) {
            return 3;
        }
        if (CN_TIAO.matcher(visible).matches() && !HEADING_NOT_BULLET.matcher(visible).matches()) {
            return 4;
        }
        if (DIGIT_LEVEL_3.matcher(visible).matches()) {
            return 3;
        }
        if (DIGIT_LEVEL_2.matcher(visible).matches()) {
            return 2;
        }
        if (NUM_ITEM.matcher(visible).matches() || CN_ITEM.matcher(visible).matches()
                || PAREN_ITEM.matcher(visible).matches()) {
            return 1;
        }
        return BODY_LEVEL;
    }

    /**
     * 标题组型投票（参考 {@code bullets_category}）。
     * <p>入参为文档各文本块文本（元素可含多行）。逐行剥坐标标签与首尾空白后，先过滤空行与
     * {@link #NOT_BULLET} 命中的行；其余行对五组模式依序判定：某一组内任一正则自行首命中，
     * 该组计一次（一组至多加一次，不同组可在同一行各自加一次）。
     * 取命中数最大且大于 0 的组号；并列时取组号较小者（等价 Python {@code max(range, key=...)}）。</p>
     *
     * @param sections 文档各文本块文本（可为 null / 空）
     * @return 命中最多的组号（0~4）；全组零命中或入参为空返回 -1（调用方应回退 naive）
     */
    public static int bulletsCategory(List<String> sections) {
        if (CollectionUtils.isEmpty(sections)) {
            return -1;
        }
        int[] hits = new int[BULLET_PATTERN.size()];
        for (String section : sections) {
            if (StringUtils.isBlank(section)) {
                continue;
            }
            for (String line : section.replace("\r\n", "\n").split("\n", -1)) {
                String visible = DelimiterParser.stripTags(line).trim();
                if (StringUtils.isBlank(visible) || notBullet(visible)) {
                    continue;
                }
                for (int i = 0; i < hits.length; i++) {
                    if (anyMatch(BULLET_PATTERN.get(i), visible)) {
                        hits[i]++;
                    }
                }
            }
        }
        int best = -1;
        for (int i = 0; i < hits.length; i++) {
            if (hits[i] > 0 && (best < 0 || hits[i] > hits[best])) {
                best = i;
            }
        }
        return best;
    }

    /**
     * 剔除目录区段（BOOK / LAWS 标题组型投票前的前置清洗，参考 {@code remove_contents_table}）。
     * <p>两条剔除规则：</p>
     * <ol>
     *   <li>以「目录 / 目次 / Contents / Table of Contents」标记行起始的<b>连续目录区</b>
     *       （标记行之后的连续「目录条目行 + 空白段」），且区内至少存在一段目录条目形态时整区剔除；</li>
     *   <li>无标记时，对每个极大候选连续段判定占比：段内命中「行首 BULLET 形态 + 行尾点线页码」
     *       的段数须同时 ≥ {@link #TOC_MIN_RUN_SECTIONS}、≥ 全文可见段数 ×
     *       {@link #TOC_RUN_DOC_RATIO}、≥ 段内段数 × {@link #TOC_RUN_INNER_RATIO}，才整段剔除。</li>
     * </ol>
     * <p><b>零影响保证</b>：无命中时返回与入参逐元素相同（同一批字符串引用）的新列表，
     * 不改动任何一行文本；被剔除的目录行既不参与投票也不进层级树。</p>
     * <p>调用面仅 BOOK / LAWS（对齐外部实现：其清洗调用点只在 book / laws），
     * GENERAL / QA / TABLE / PRESENTATION / ONE 一律不得调用。</p>
     *
     * @param sections 文档各文本块文本（按文档顺序，元素可含多行；可为 null / 空）
     * @return 剔除目录区后的段列表（内容未被改动，仅可能少了目录段）
     */
    public static List<String> removeContentsTable(List<String> sections) {
        if (CollectionUtils.isEmpty(sections)) {
            return new ArrayList<>();
        }
        int size = sections.size();
        boolean[] candidate = new boolean[size];
        boolean[] entry = new boolean[size];
        boolean[] blank = new boolean[size];
        int visibleSections = 0;
        for (int index = 0; index < size; index++) {
            List<String> lines = visibleLines(sections.get(index));
            if (CollectionUtils.isEmpty(lines)) {
                candidate[index] = true;
                blank[index] = true;
                continue;
            }
            visibleSections++;
            int entryHits = 0;
            int shortHits = 0;
            for (String line : lines) {
                if (isTocEntryLine(line)) {
                    entryHits++;
                }
                if (line.length() <= TOC_CANDIDATE_MAX_CHARS) {
                    shortHits++;
                }
            }
            entry[index] = entryHits == lines.size();
            candidate[index] = shortHits == lines.size();
        }
        boolean[] removed = new boolean[size];
        removeMarkedRegion(sections, blank, entry, removed);
        removeRatioQualifiedRegions(candidate, entry, removed, visibleSections);
        return keepSurvivors(sections, removed);
    }

    /**
     * 层级频率投票（参考 {@code most_level}）。
     * <p>对 {@code levels} 中不大于 {@code bulletsSize} 的层级做频率统计，取出现次数最多者；
     * 出现次数并列时取输入序先遇到者；无候选（入参为空或全部超限）返回 0。</p>
     *
     * @param levels      层级序列（文档顺序，含 {@link #BODY_LEVEL} 时通常已被 {@code bulletsSize} 过滤）
     * @param bulletsSize 层级上限（生效组内模式条数，超限者不参与统计）
     * @return 出现次数最多的层级；无候选返回 0
     */
    public static int mostLevel(List<Integer> levels, int bulletsSize) {
        if (CollectionUtils.isEmpty(levels)) {
            return 0;
        }
        Map<Integer, Integer> frequency = new LinkedHashMap<>();
        for (Integer level : levels) {
            if (ObjectUtils.isEmpty(level) || level > bulletsSize) {
                continue;
            }
            frequency.merge(level, 1, Integer::sum);
        }
        int most = 0;
        int top = 0;
        for (Map.Entry<Integer, Integer> entry : frequency.entrySet()) {
            if (entry.getValue() > top) {
                top = entry.getValue();
                most = entry.getKey();
            }
        }
        return most;
    }

    /**
     * 按指定组模式标注行层级（参考步骤 2 的组内首中判定）。
     * <p>与 {@link #detectLevels} 同口径做预处理：剥坐标标签、跳过空行 / 剥后长度不足 2 的行 /
     * 纯数字页码行；命中 {@link #NOT_BULLET} 的行按正文处理。组内首中正则的 1-based 序号即层级。</p>
     *
     * @param texts 按行的文本列表（元素可含换行，会二次拆分；可为 null / 空）
     * @param bull  组号（{@link #bulletsCategory} 的返回值）；小于 0 或不小于组数时全部返回正文层级
     * @return 层级行列表（元素按输入顺序，{@link LevelLine#text()} 保留原始行文本含坐标标签）
     */
    public static List<LevelLine> detectLevelsInGroup(List<String> texts, int bull) {
        List<LevelLine> out = new ArrayList<>();
        if (CollectionUtils.isEmpty(texts)) {
            return out;
        }
        for (String text : texts) {
            if (StringUtils.isBlank(text)) {
                continue;
            }
            for (String line : text.replace("\r\n", "\n").split("\n", -1)) {
                String visible = DelimiterParser.stripTags(line).trim();
                if (StringUtils.isBlank(visible) || visible.length() <= 1
                        || visible.matches("\\p{Nd}+")) {
                    continue;
                }
                out.add(new LevelLine(levelInGroup(visible, bull), line));
            }
        }
        return out;
    }

    /**
     * 判断层级行序列中是否存在标题行（供策略决定是否回退 naive）。
     *
     * @param lines 层级行列表
     * @return true 表示至少一行命中标题层级
     */
    public static boolean hasHeading(List<LevelLine> lines) {
        for (LevelLine line : lines) {
            if (line.level() != BODY_LEVEL) {
                return true;
            }
        }
        return false;
    }

    /**
     * 层级树合并（参考 Node 树 + DFS）。
     * <p>目标层级候选序列 = 「标题行出现过的 distinct 层级升序」+ 末尾 {@link #BODY_LEVEL} 哨兵，
     * 取第 {@code depth} 小者；候选不足时落入末位正文桶 → 降一级取最大的真实标题层级
     * （参考步骤 3）。该层级及其更高层作为树骨架，剩余层级/正文并入最近祖先；
     * DFS 展平时每块文本 = 标题路径（用 {@code \n} 连接）+ 正文，保证召回可读（标题路径前缀）。</p>
     *
     * @param lines 层级行列表（需含至少一行标题，由调用方先 {@link #hasHeading} 判定）
     * @param depth 目标合并深度（books=5 / laws=2）
     * @return 合并后的块文本列表（已剥空块，坐标标签保留）
     */
    public static List<String> treeMerge(List<LevelLine> lines, int depth) {
        List<Integer> levels = lines.stream().map(LevelLine::level)
                .filter(l -> l != BODY_LEVEL).distinct().sorted().toList();
        if (levels.isEmpty()) {
            // 全正文退化为整块（调用方应已用 hasHeading 分流，此处兜底）
            return List.of(String.join("\n", lines.stream().map(LevelLine::text).toList()));
        }
        int target = resolveTargetLevel(levels, depth);
        Node root = new Node(0, target);
        root.buildTree(lines);
        return root.dfs(new ArrayList<>());
    }

    /**
     * 短块吸收合并（参考步骤 3）。
     * <p>单元素块（不含 {@code \n} 的标题叶块）按 {@link #HIERARCHICAL_ABSORB_THRESHOLD}(218)
     * 累计并入上一块；累计加当前块仍低于阈值才并入，否则独立成块；多元素块视为预算满
     * （记 218）以逼后续单元素块独立。避免出现「只有一个标题的空块」。</p>
     *
     * @param blocks  待整理块文本列表（坐标标签保留）
     * @param counter 真实 token 计数端口
     * @return 吸收合并后的块列表
     */
    public static List<String> absorbShortBlocks(List<String> blocks, TokenCounter counter) {
        List<List<String>> groups = new ArrayList<>();
        List<Integer> tokens = new ArrayList<>();
        groups.add(new ArrayList<>());
        tokens.add(0);
        for (String block : blocks) {
            int n = counter.count(DelimiterParser.stripTags(block));
            boolean single = !block.contains("\n");
            if (single && n + tokens.get(tokens.size() - 1) < HIERARCHICAL_ABSORB_THRESHOLD) {
                groups.get(groups.size() - 1).add(block);
                tokens.set(tokens.size() - 1, tokens.get(tokens.size() - 1) + n);
                continue;
            }
            groups.add(new ArrayList<>(List.of(block)));
            tokens.add(single ? n : HIERARCHICAL_ABSORB_THRESHOLD);
        }
        List<String> out = new ArrayList<>();
        for (List<String> group : groups) {
            if (!group.isEmpty()) {
                out.add(String.join("\n", group));
            }
        }
        return out;
    }

    /**
     * 编译一组正则（{@link #BULLET_PATTERN} 静态初始化用），产出不可变列表。
     *
     * @param regexList 组内有序正则（组内序号即层级）
     * @return 编译后的模式列表
     */
    private static List<Pattern> patterns(String... regexList) {
        List<Pattern> compiled = new ArrayList<>(regexList.length);
        for (String regex : regexList) {
            compiled.add(Pattern.compile(regex));
        }
        return List.copyOf(compiled);
    }

    /**
     * 非标题候选守卫（参考 {@code not_bullet}）：命中即不参与组型投票、不标注标题层级。
     *
     * @param visible 已剥坐标标签并 trim 的行文本
     * @return true 表示该行不应视为标题候选
     */
    private static boolean notBullet(String visible) {
        return StringUtils.isNotBlank(visible) && NOT_BULLET.matcher(visible).find();
    }

    /**
     * 组内是否存在自行首命中的模式（等价 Python {@code re.match} 遍历）。
     *
     * @param group   组内有序模式列表
     * @param visible 行文本
     * @return true 表示该组命中
     */
    private static boolean anyMatch(List<Pattern> group, String visible) {
        for (Pattern pattern : group) {
            if (pattern.matcher(visible).lookingAt()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 拆分段落为「可见行」（剥坐标标签 + trim + 去空白行）。
     *
     * @param section 段文本（可为 null / 空白）
     * @return 可见行列表（可能为空）
     */
    private static List<String> visibleLines(String section) {
        List<String> lines = new ArrayList<>();
        if (StringUtils.isBlank(section)) {
            return lines;
        }
        for (String line : section.replace("\r\n", "\n").split("\n", -1)) {
            String visible = DelimiterParser.stripTags(line).trim();
            if (StringUtils.isNotBlank(visible)) {
                lines.add(visible);
            }
        }
        return lines;
    }

    /**
     * 判定可见行是否为目录条目形态：行首命中任一组 BULLET 标题形态且行尾为点线 + 页码。
     *
     * @param visible 已剥坐标标签并 trim 的行文本（非空）
     * @return true 表示该行为目录条目
     */
    private static boolean isTocEntryLine(String visible) {
        if (!TOC_LEADER_PAGE.matcher(visible).find()) {
            return false;
        }
        for (List<Pattern> group : BULLET_PATTERN) {
            if (anyMatch(group, visible)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 规则①：以目录标记行起始的连续目录区剔除（区内至少一段目录条目才动手，
     * 避免误杀正文里恰好出现的「目录」二字）。
     * <p>区界判定只认「目录条目段 + 空白段」，第一条非条目正文即终止扩张，保证正文真实标题不受影响。</p>
     *
     * @param sections 段列表
     * @param blank    各段是否为空白段
     * @param entry    各段是否整段为目录条目
     * @param removed  剔除标记位（命中时置 true）
     */
    private static void removeMarkedRegion(List<String> sections, boolean[] blank, boolean[] entry,
                                           boolean[] removed) {
        int marker = -1;
        for (int index = 0; index < sections.size(); index++) {
            List<String> lines = visibleLines(sections.get(index));
            if (CollectionUtils.isNotEmpty(lines) && CONTENTS_MARKER.matcher(lines.get(0)).matches()) {
                marker = index;
                break;
            }
        }
        if (marker < 0) {
            return;
        }
        int end = marker + 1;
        while (end < sections.size() && (entry[end] || blank[end])) {
            end++;
        }
        if (countTrue(entry, marker + 1, end) < 1) {
            return;
        }
        markRemoved(removed, marker, end);
    }

    /**
     * 规则②：对每个极大候选连续段按占比阈值判定是否整段剔除。
     *
     * @param candidate       各段是否为目录候选段
     * @param entry           各段是否整段为目录条目
     * @param removed         剔除标记位（命中时置 true）
     * @param visibleSections 全文可见段数（占比分母）
     */
    private static void removeRatioQualifiedRegions(boolean[] candidate, boolean[] entry, boolean[] removed,
                                                    int visibleSections) {
        int index = 0;
        while (index < candidate.length) {
            if (!candidate[index]) {
                index++;
                continue;
            }
            int end = index;
            while (end < candidate.length && candidate[end]) {
                end++;
            }
            int hits = countTrue(entry, index, end);
            int docFloor = (int) Math.ceil(visibleSections * TOC_RUN_DOC_RATIO);
            int innerFloor = (int) Math.ceil((end - index) * TOC_RUN_INNER_RATIO);
            if (hits >= TOC_MIN_RUN_SECTIONS && hits >= docFloor && hits >= innerFloor) {
                markRemoved(removed, index, end);
            }
            index = end;
        }
    }

    /**
     * 统计 {@code flags} 在 [from, to) 区间内 true 的个数。
     *
     * @param flags 标记数组
     * @param from  起始下标（含）
     * @param to    结束下标（不含）
     * @return true 的个数
     */
    private static int countTrue(boolean[] flags, int from, int to) {
        int count = 0;
        for (int index = Math.max(from, 0); index < Math.min(to, flags.length); index++) {
            if (flags[index]) {
                count++;
            }
        }
        return count;
    }

    /**
     * 将 [from, to) 区间的剔除标记位置为 true。
     *
     * @param removed 剔除标记数组
     * @param from    起始下标（含）
     * @param to      结束下标（不含）
     */
    private static void markRemoved(boolean[] removed, int from, int to) {
        for (int index = Math.max(from, 0); index < Math.min(to, removed.length); index++) {
            removed[index] = true;
        }
    }

    /**
     * 按剔除标记取存活段（原样保留字符串引用，不做任何文本加工）。
     *
     * @param sections 段列表
     * @param removed  剔除标记数组
     * @return 存活段列表
     */
    private static List<String> keepSurvivors(List<String> sections, boolean[] removed) {
        List<String> out = new ArrayList<>(sections.size());
        for (int index = 0; index < sections.size(); index++) {
            if (!removed[index]) {
                out.add(sections.get(index));
            }
        }
        return out;
    }

    /**
     * 取指定组内的首中模式序号作为层级（1-based）。
     *
     * @param visible 已剥坐标标签并 trim 的行文本
     * @param bull    组号
     * @return 组内 1-based 序号；组号越界、命中守卫或全未命中返回 {@link #BODY_LEVEL}
     */
    private static int levelInGroup(String visible, int bull) {
        if (bull < 0 || bull >= BULLET_PATTERN.size() || notBullet(visible)) {
            return BODY_LEVEL;
        }
        List<Pattern> group = BULLET_PATTERN.get(bull);
        for (int i = 0; i < group.size(); i++) {
            if (group.get(i).matcher(visible).lookingAt()) {
                return i + 1;
            }
        }
        return BODY_LEVEL;
    }

    /**
     * 解析目标合并层级（参考步骤 3：{@code sorted_levels[depth-1]}，落正文桶降一级）。
     * <p>候选 = 真实标题层级升序 + 末位 {@link #BODY_LEVEL} 哨兵；索引取 
     * （{@code depth} 小于 1 时按 1 处理，越界时截断到末位）。选中哨兵即「落正文桶」，
     * 降级为候选中最大的真实标题层级。</p>
     *
     * @param headingLevels 标题行 distinct 层级升序列表（非空）
     * @param depth         目标合并深度
     * @return 目标层级（恒为某个真实标题层级）
     */
    private static int resolveTargetLevel(List<Integer> headingLevels, int depth) {
        int index = Math.min(Math.max(depth, 1) - 1, headingLevels.size());
        if (index >= headingLevels.size()) {
            // 落 body 桶 → 降一级：取最大的真实标题层级
            return headingLevels.get(headingLevels.size() - 1);
        }
        return headingLevels.get(index);
    }

    /**
     * 层级树节点（栈式建树 + DFS 展平，参考）。
     */
    public static final class Node {

        /** 节点层级（root 为 0）。 */
        private final int level;

        /** 节点携带的行文本列表（正文并入最近标题时追加于此）。 */
        private final List<String> texts = new ArrayList<>();

        /** 合并深度（越界正文并入最近标题的条件）。 */
        private final int depth;

        /** 子节点列表（栈式建树时维护）。 */
        private final List<Node> children = new ArrayList<>();

        /**
         * 构造节点。
         *
         * @param level 节点层级
         * @param depth 合并深度（root 使用目标层级）
         */
        public Node(int level, int depth) {
            this.level = level;
            this.depth = depth;
        }

        /**
         * 栈式建树：level 越小层级越高；越界正文（level 大于 depth）并入最近标题文本，
         * 不建子节点；否则弹栈到层级更小的祖先后挂接子节点（参考）。
         *
         * @param lines 层级行列表
         */
        public void buildTree(List<LevelLine> lines) {
            List<Node> stack = new ArrayList<>();
            stack.add(this);
            for (LevelLine line : lines) {
                if (depth != -1 && line.level() > depth) {
                    stack.get(stack.size() - 1).texts.add(line.text());
                    continue;
                }
                while (stack.size() > 1 && line.level() <= stack.get(stack.size() - 1).level) {
                    stack.remove(stack.size() - 1);
                }
                Node child = new Node(line.level(), depth);
                child.texts.add(line.text());
                stack.get(stack.size() - 1).children.add(child);
                stack.add(child);
            }
        }

        /**
         * DFS 展平：每个输出块 = 标题路径（用 {@code \n} 连接）+ 正文，丢弃空块。
         * <ul>
         *   <li>root（level=0）有文本 → 文档级头部块；</li>
         *   <li>1..depth 的叶标题 → 仅输出标题路径；</li>
         *   <li>越界正文（level&gt;depth）有文本 → 输出标题路径 + 正文；</li>
         *   <li>递归传递时标题节点将其文本并入路径。</li>
         * </ul>
         *
         * @param titles 已累积的标题路径文本
         * @return 展平块列表（已过滤空块）
         */
        public List<String> dfs(List<String> titles) {
            List<String> out = new ArrayList<>();
            boolean heading = level >= 1 && level <= depth;
            if (level == 0 && !texts.isEmpty()) {
                out.add(String.join("\n", concat(titles, texts)));
            } else if (heading && children.isEmpty()) {
                out.add(String.join("\n", concat(titles, texts)));
            } else if (level > depth && !texts.isEmpty()) {
                out.add(String.join("\n", concat(titles, texts)));
            }
            for (Node child : children) {
                List<String> next = heading ? concat(titles, texts) : new ArrayList<>(titles);
                out.addAll(child.dfs(next));
            }
            return out.stream().filter(StringUtils::isNotBlank).toList();
        }

        /**
         * 合并两个字符串列表（标题路径 + 节点文本）。
         */
        private static List<String> concat(List<String> head, List<String> tail) {
            List<String> all = new ArrayList<>(head);
            all.addAll(tail);
            return all;
        }
    }
}