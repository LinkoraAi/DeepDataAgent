package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.rag.domain.model.ChunkVO;
import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 分隔符文法解析与通用文本切分工具（参考 1:1 迁移）。
 * <p>核心职责：{@link #parseDelimiterField} 将配置分隔符字段解析为按长度降序的分隔符集合；
 * {@link #buildPattern} 构造长串优先匹配正则；{@link #splitDroppingDelim} 按分隔符切分并
 * 丢弃分隔符文本（契约 1：分隔符绝不进入切片）；{@link #overlapPrefix} 按可见字符比例截取
 * 上一块尾部重叠前缀（契约 4）。本类为纯静态工具，可被文档级 {@link GeneralChunkingService}
 * 与块级 {@link GeneralChunkStrategy} 共享，保证算法逐字节一致。</p>
 */
public final class DelimiterParser {

    /** 解析器插入的坐标标签（格式 {@code @@页码\t左\t右\t上\t下##}，参考）。 */
    public static final Pattern TAG = Pattern.compile("@@[\\t0-9.\\-]+?##");

    /**
     * 句界正则：换行与中英文句末标点。
     * <p>刻意<b>不含英文句号加空格</b>，避免把 {@code v1.2} / {@code Fig. 3} 切开
     * （无分隔符退化分支）。</p>
     */
    public static final Pattern SENT_DELIM = Pattern.compile("(\\n|[!?。；！？])");

    /** 分隔符字段中标记反引号包裹（多字符自定义分隔符）的字符。 */
    private static final char BACKTICK = '`';

    /** 正则缓存键的分隔符连接符（NUL 字符，分隔符内容不可能含该字符，键无歧义）。 */
    private static final String CACHE_KEY_SEPARATOR = "\u0000";

    /** meta 键：坐标位置。 */
    private static final String META_KEY_POSITIONS = "positions";

    /** 短标题 token 上限：预切段剥离标签后 token 数 ≤ 该值才可能被识别为短标题（禁魔法值）。 */
    private static final int SHORT_HEADER_TOKEN_LIMIT = 50;

    /** Markdown 标题形态：行首 1~6 个 {@code #} 紧跟空白。 */
    private static final Pattern MD_HEADER_PATTERN = Pattern.compile("^#{1,6}\\s+");

    /** 句末标点集合：短标题末字符命中其一则视为完整句，不再当短标题保护。 */
    private static final Pattern END_PUNCT_PATTERN = Pattern.compile("[。；！？!?:;]");

    private DelimiterParser() {
    }

    /**
     * 解析分隔符字段（文法：反引号之间的任意字符 = 一个多字符分隔符；反引号外的每个字符 =
     * 一个单字符分隔符）。
     * <ul>
     *   <li>合并、稳定去重、按长度降序稳定排序（保证 {@code ##} 先于 {@code #} 匹配）；</li>
     *   <li>{@code \r\n} 与孤立 {@code \r} 统一归一为 {@code \n}；</li>
     *   <li>未闭合反引号容错收尾。</li>
     * </ul>
     *
     * @param field 分隔符字段（可为空白，空返回空列表）
     * @return 按长度降序、稳定去重后的分隔符列表（可能为空）
     */
    public static List<String> parseDelimiterField(String field) {
        if (StringUtils.isBlank(field)) {
            return new ArrayList<>();
        }
        String normalized = field.replace("\r\n", "\n").replace("\r", "\n");
        List<String> tokens = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        boolean inBacktick = false;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c == BACKTICK) {
                if (inBacktick) {
                    tokens.add(buffer.toString());
                    buffer.setLength(0);
                }
                inBacktick = !inBacktick;
                continue;
            }
            if (inBacktick) {
                buffer.append(c);
            } else {
                tokens.add(String.valueOf(c));
            }
        }
        if (buffer.length() > 0) {
            // 未闭合反引号：容错收尾
            tokens.add(buffer.toString());
        }
        // 过滤空串 + 稳定去重（LinkedHashMap 保留首次出现顺序）
        Map<String, Boolean> uniq = new LinkedHashMap<>();
        for (String token : tokens) {
            if (!token.isEmpty()) {
                uniq.put(token, Boolean.TRUE);
            }
        }
        List<String> delims = new ArrayList<>(uniq.keySet());
        // 按长度降序稳定排序（TimSort 稳定，等长保持输入序）
        delims.sort(Comparator.comparingInt(String::length).reversed());
        return delims;
    }

    /**
     * 判定分隔符字段是否含反引号包裹的自定义分隔符（custom 模式触发条件）。
     * <p>按：只有反引号包裹才触发 custom（绕过 token 预算），
     * 裸单字符分隔符不触发——「没有有效分隔符」≠「自定义模式」。</p>
     *
     * @param field 分隔符字段
     * @return true 表示含反引号 token，需走 custom 模式
     */
    public static boolean hasCustomDelimiter(String field) {
        return StringUtils.contains(field, BACKTICK);
    }

    /**
     * 已编译分隔符正则缓存（键 = 规一化后的<b>不可变</b>串：分隔符逐个拷贝为不可变快照后
     * 以 NUL 连接，保持顺序——顺序决定交替支优先序，属正则语义的一部分）。
     * <p><b>钉死前提</b>：分隔符集合来自知识库配置、基数有界（取值空间为有限配置形态），
     * 缓存不会无界增长；若未来允许终端用户任意输入分隔符，须补键基数上限与淘汰策略。</p>
     * <p>MUST NOT 以调用方持有的可变 {@code List} 直接作键（内容随调用方修改而漂移会破坏
     * 缓存正确性），故入参一律先经 {@code List.copyOf} 固化为不可变形态再派生键。</p>
     */
    private static final ConcurrentHashMap<String, Pattern> COMPILED_PATTERN_CACHE = new ConcurrentHashMap<>();

    /**
     * 构建分隔符匹配正则（DOTALL 模式；多字符优先已由长度降序保证）。
     * <p>同内容分隔符集合复用同一已编译 {@link Pattern}（不可变、线程安全），
     * 避免块级/文档级调用点在 {@code ChunkStrategy#splitDocument} 逐块循环内重复编译。</p>
     *
     * @param delims 分隔符列表（应按长度降序，来自 {@link #parseDelimiterField}）
     * @return 编译后的匹配正则；delims 为空时返回 null（表示无生效分隔符）
     */
    public static Pattern buildPattern(List<String> delims) {
        if (delims == null || delims.isEmpty()) {
            return null;
        }
        List<String> immutableDelims = List.copyOf(delims);
        return COMPILED_PATTERN_CACHE.computeIfAbsent(String.join(CACHE_KEY_SEPARATOR, immutableDelims),
                key -> Pattern.compile(String.join("|", immutableDelims.stream().map(Pattern::quote).toList()),
                        Pattern.DOTALL));
    }

    /**
     * 按分隔符切分并丢弃分隔符文本（等价参考 {@code split_dropping_delim}：re.split 带捕获组取偶数位）。
     * <p>契约 1：切分点只落在分隔符之后，分隔符文本绝不进入任何切片。</p>
     *
     * @param text    待切分正文（CRLF 归一化在调用方负责）
     * @param pattern 分隔符正则；null 表示无分隔符，原样返回
     * @return 不含分隔符的正文段列表
     */
    public static List<String> splitDroppingDelim(String text, Pattern pattern) {
        if (pattern == null || StringUtils.isEmpty(text)) {
            return List.of(text);
        }
        List<String> parts = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        int last = 0;
        while (matcher.find()) {
            parts.add(text.substring(last, matcher.start()));
            last = matcher.end();
        }
        parts.add(text.substring(last));
        return parts;
    }

    /**
     * 按重叠比例截取上一块可见字符尾部的重叠前缀（契约 4）。
     * <p>先剥离坐标标签得到「可见字符」，再按 {@code len(visible) × (100 - pct) / 100} 起点
     * 截取尾部；pct ≤ 0 或无上一块时返回空串。</p>
     *
     * @param prevText 上一块完整文本（可能含坐标标签）
     * @param pct      重叠比例（0~30）
     * @return 上一块可见字符尾部前缀，可为空串
     */
    public static String overlapPrefix(String prevText, int pct) {
        if (pct <= 0 || StringUtils.isEmpty(prevText)) {
            return "";
        }
        String visible = TAG.matcher(prevText).replaceAll("");
        int start = (int) (visible.length() * (100 - pct) / 100);
        int from = Math.min(start, visible.length());
        return visible.substring(from);
    }

    /**
     * 剥离文本中的坐标标签（{@code @@…##}），仅保留正文。
     *
     * @param text 待剥离文本
     * @return 剥离标签后的正文（非 null）
     */
    public static String stripTags(String text) {
        return text == null ? "" : TAG.matcher(text).replaceAll("");
    }

    /**
     * 构建文本切分单元（参考 {@code build_units}，含无分隔符退化）。
     * <p>有生效分隔符时按分隔符切段；否则整段 ≤ 预算作为单单元，超预算按句界
     * {@link #SENT_DELIM} 切分（契约 2：仅无分隔符退化才切句界）。空可见字符段过滤。
     * 每个单元文本统一前置 {@code "\n"}（保留原始前后空白，只去掉分隔符）。</p>
     *
     * @param text    单个内容块的文本
     * @param pattern 分隔符正则；null 表示无生效分隔符
     * @param target  分块 token 软目标（chunk_token_num）
     * @param counter 真实 token 计数端口
     * @return 文本单元列表（可能为空）
     */
    public static List<Unit> buildUnits(String text, Pattern pattern, int target, TokenCounter counter) {
        return buildUnits(text, pattern, target, counter, null);
    }

    /**
     * 构建文本切分单元（带来源块版本，供文档级主管线保留回源元数据）。
     *
     * @param text    单个内容块的文本
     * @param pattern 分隔符正则；null 表示无生效分隔符
     * @param target  分块 token 软目标（chunk_token_num）
     * @param counter 真实 token 计数端口
     * @param source  来源内容块（随单元透传到最终 ChunkVO.block，可 null）
     * @return 文本单元列表（可能为空）
     */
    public static List<Unit> buildUnits(String text, Pattern pattern, int target, TokenCounter counter,
                                        ContentBlockVO source) {
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");
        List<String> segs;
        if (pattern != null) {
            segs = splitDroppingDelim(normalized, pattern);
        } else if (counter.count("\n" + normalized) <= target) {
            segs = List.of(normalized);
        } else {
            segs = new ArrayList<>();
            Matcher matcher = SENT_DELIM.matcher(normalized);
            int last = 0;
            while (matcher.find()) {
                segs.add(normalized.substring(last, matcher.start()));
                last = matcher.end();
            }
            segs.add(normalized.substring(last));
        }
        List<Unit> units = new ArrayList<>();
        for (String seg : segs) {
            if (StringUtils.isBlank(TAG.matcher(seg).replaceAll(""))) {
                continue;
            }
            String unitText = "\n" + seg;
            units.add(new Unit(unitText, counter.count(unitText), false, source));
        }
        return units;
    }

    /**
     * 按块类型构建切分单元（文档级主管线与块级策略共享）。
     * <p>多模态块（表/图/公式）透传为独立单元并中断合并链；custom 分隔符模式
     * 按分隔符切为逐段单元（每 segment 一块，绕过 token 预算）；其余走 {@link #buildUnits}。</p>
     *
     * @param block   内容块
     * @param pattern 分隔符正则（来自 {@link #buildPattern}，可 null）
     * @param custom  是否 custom 模式（反引号包裹分隔符触发）
     * @param target  分块 token 软目标
     * @param counter 真实 token 计数端口
     * @return 切分单元列表
     */
    public static List<Unit> buildUnitsForBlock(ContentBlockVO block, Pattern pattern, boolean custom, int target,
                                                TokenCounter counter) {
        if (block.isMultimodal()) {
            return new ArrayList<>(List.of(passthroughUnit(block, counter)));
        }
        if (custom) {
            List<Unit> units = new ArrayList<>();
            for (String seg : splitDroppingDelim(block.text(), pattern)) {
                if (StringUtils.isBlank(TAG.matcher(seg).replaceAll(""))) {
                    continue;
                }
                String unitText = "\n" + seg;
                units.add(new Unit(unitText, counter.count(unitText), false, block));
            }
            return units;
        }
        return buildUnits(block.text(), pattern, target, counter, block);
    }

    /**
     * 贪心合并文本单元（参考 {@code merge_units}，OVER_CAP 单一封口线）。
     * <p>合并规约（契约 3）：</p>
     * <ul>
     *   <li>非文本单元（表/图）直接透传为独立块并中断合并链；</li>
     *   <li>单个单元 token 超预算 → 独立成块（契约 2，不原子切分）；</li>
     *   <li><b>唯一封口线</b>：并入当前单元后 {@code prev.tokens + unit.tokens > target} 才封块，
     *       当前单元作为新块种子（不溢出并入已封块）；<b>不存在</b>
     *       {@code prev.tokens > target×(100-pct)/100} 的提前封块条件——重叠比例
     *       {@code overlapPct} <b>不参与封口判定</b>，仅在开新块时对上一块做无条件的前缀字符截尾；</li>
     *   <li>短标题强制并入：当前累计块首行为短 markdown 标题（{@link #isShortHeader}）时无条件并入
     *       当前单元，即使超出 {@code target}，避免光杆标题单独成块；passthrough 单元不参与该合并；</li>
     *   <li>并入采用 running-sum token 累加，不对拼接串重新 tokenize（与基线逐字节对齐）；</li>
     *   <li>开新块时无条件前置上一块可见字符尾部前缀（契约 4）。</li>
     * </ul>
     *
     * @param units       文本切分单元（可混入非文本透传单元）
     * @param target      分块 token 软目标
     * @param overlapPct  重叠比例（0~30，仅用于开新块时截前缀，不影响封口）
     * @param counter     真实 token 计数端口
     * @return 合并后的块列表（已过滤剥标签后为空的白块）
     */
    public static List<Unit> mergeUnits(List<Unit> units, int target, int overlapPct, TokenCounter counter) {
        List<Unit> out = new ArrayList<>();
        Unit prev = null;
        for (Unit unit : units) {
            if (unit.passthrough) {
                out.add(unit);
                prev = null;
                continue;
            }
            if (ObjectUtils.isEmpty(prev)) {
                prev = unit;
                out.add(prev);
                continue;
            }
            // 唯一封口线：并入后超 target 才封块（重叠比例不压缩块尺寸）。短标题块无条件并入当前单元、
            // 跳过封口判定；但超预算巨型单元（契约 2）恒独立成块，优先级高于短标题并入，
            // 避免光杆短标题把巨型段粘连进自身。passthrough 不参与此链。
            boolean oversized = unit.tokens > target;
            boolean needNew = oversized || (!isShortHeader(prev) && prev.tokens + unit.tokens > target);
            if (needNew) {
                // 封当前块，当前单元作为新块种子（含 overlap 前缀）；巨型单元亦经此独立成块，
                // 其新块本身超 target，下一单元到来时必再次封块，不会被短标题粘连
                prev = newBlock(prev, unit, overlapPct, counter);
                out.add(prev);
            } else {
                // running-sum：拼接文本与 token 数同步累加，不重新 tokenize
                prev.text = prev.text + unit.text;
                prev.tokens = prev.tokens + unit.tokens;
            }
        }
        out.removeIf(b -> StringUtils.isBlank(TAG.matcher(b.text).replaceAll("")));
        return out;
    }

    /**
     * 判定单元是否为需强制并入下一段的「短标题」（仅 Markdown 形）。
     * <p>满足全部条件方为 true：① 非透传单元；② 剥离坐标标签并 trim 后的首行 token 数
     * ≤ {@value #SHORT_HEADER_TOKEN_LIMIT}；③ 首行命中 Markdown 标题（{@code ^#{1,6}\s+}）；
     * ④ 首行末字符不是句末标点 {@code [。；！？!?:;]}。任一不满足返回 false。</p>
     * <p>中文章条形（第N章/节/条/部/分）<b>不再</b>算短标题形态：其层级归属为 BOOK/LAWS 方法，
     * GENERAL 不越俎代庖；保护与否只看内容形状，不依赖文件后缀与分隔符配置值。</p>
     *
     * @param unit 待判定单元（可为 null）
     * @return true 表示该单元为短标题，应强制与下一段并入
     */
    private static boolean isShortHeader(Unit unit) {
        if (ObjectUtils.isEmpty(unit) || unit.passthrough) {
            return false;
        }
        if (unit.tokens > SHORT_HEADER_TOKEN_LIMIT) {
            return false;
        }
        String firstLine = firstVisibleLine(unit.text);
        if (StringUtils.isEmpty(firstLine)) {
            return false;
        }
        if (!MD_HEADER_PATTERN.matcher(firstLine).find()) {
            return false;
        }
        String lastChar = firstLine.substring(firstLine.length() - 1);
        return !END_PUNCT_PATTERN.matcher(lastChar).find();
    }

    /**
     * 提取单元文本剥离坐标标签并 trim 后的首个可见行（按首个换行切分）。
     *
     * @param text 单元原始文本（可能含前置换行与坐标标签）
     * @return 首个非空可见行；无可见内容时返回空串
     */
    private static String firstVisibleLine(String text) {
        String visible = StringUtils.stripToEmpty(stripTags(text));
        if (StringUtils.isEmpty(visible)) {
            return StringUtils.EMPTY;
        }
        int newline = visible.indexOf('\n');
        return newline < 0 ? visible : StringUtils.stripToEmpty(visible.substring(0, newline));
    }

    /**
     * 构造新块：前置上一块可见字符尾部前缀，token 数对新拼接串真实 encode。
     */
    private static Unit newBlock(Unit prev, Unit unit, int overlapPct, TokenCounter counter) {
        String prefix = prev == null ? "" : overlapPrefix(prev.text, overlapPct);
        String text = prefix + unit.text;
        return new Unit(text, counter.count(text), false, unit.source);
    }

    /**
     * 构建非文本透传单元（表/图/公式等原子块，独立成块并中断合并链）。
     *
     * @param block   多模态内容块
     * @param counter 真实 token 计数端口
     * @return 透传单元（tokens 按真实 encode 计数）
     */
    public static Unit passthroughUnit(ContentBlockVO block, TokenCounter counter) {
        return new Unit(block.text(), counter.count(block.text()), true, block);
    }

    /**
     * 将合并后的单元列表转换为分块结果（参考 {@code to_chunk}）。
     * <p>顺序：剥坐标标签入正文、坐标作为元数据保留（positions）、按传入起始序号递增。
     * 可见字符为空的白块被过滤。</p>
     *
     * @param blocks  合并后的块单元列表
     * @param counter 真实 token 计数端口（透传单元需真实计数）
     * @return 分块结果列表
     */
    public static List<ChunkVO> toChunkVOs(List<Unit> blocks, TokenCounter counter) {
        List<ChunkVO> out = new ArrayList<>();
        int sequence = 1;
        for (Unit unit : blocks) {
            if (unit.passthrough && unit.tokens == 0) {
                // 兼容旧调用：透传单元未计数时补充真实计数
                unit.tokens = counter.count(unit.text);
            }
            List<String> positions = extractPositionParts(unit.text);
            String content = stripTags(unit.text);
            if (StringUtils.isBlank(content) && positions.isEmpty()) {
                continue;
            }
            ChunkVO chunk = new ChunkVO(sequence++, content, unit.tokens, unit.source);
            out.add(chunk);
            if (!positions.isEmpty() && unit.source != null) {
                Map<String, Object> meta = unit.source.meta();
                if (meta == null) {
                    meta = new HashMap<>();
                }
                Map<String, Object> mergedMeta = new HashMap<>(meta);
                mergedMeta.put(META_KEY_POSITIONS, positions);
                ChunkVO enriched = new ChunkVO(chunk.sequence(), chunk.text(), chunk.tokens(),
                        new ContentBlockVO(unit.source.type(), unit.source.text(), mergedMeta));
                out.set(out.size() - 1, enriched);
            }
        }
        return out;
    }

    /**
     * 从文本中提取首个坐标标签内的原始片段（{@code page\t左\t右\t上\t下}）。
     *
     * @param text 含标签文本
     * @return 坐标原始片段列表（如后续坐标缺失返回空列表）
     */
    public static List<String> extractPositionParts(String text) {
        if (StringUtils.isEmpty(text)) {
            return new ArrayList<>();
        }
        Matcher matcher = TAG.matcher(text);
        if (!matcher.find()) {
            return new ArrayList<>();
        }
        String inner = matcher.group().substring(2, matcher.group().length() - 2);
        return List.of(inner.split("\t"));
    }

    /**
     * meta 键：坐标位置（与解析器产物的 positions 字段对齐）。
     *
     * @return meta 键名
     */
    public static String metaKeyPositions() {
        return META_KEY_POSITIONS;
    }

    /**
     * 分块中间单元（仅内存）：由文本段（含透传）构成，支持 running-sum 合并。
     */
    public static final class Unit {

        /** 块文本（含前置换行与可能的坐标标签，合并时为 running-sum 拼接结果）。 */
        public String text;

        /** 块 token 数（合并累积用 running-sum，不重新编码）。 */
        public int tokens;

        /** 是否为非文本透传单元（表/图/公式等原子块）。 */
        public boolean passthrough;

        /** 来源内容块（合并跨块时取合并链首块）。 */
        public ContentBlockVO source;

        /**
         * 构造切分单元。
         *
         * @param text        块文本
         * @param tokens      token 数
         * @param passthrough 是否透传（非文本原子块）
         * @param source      来源内容块
         */
        public Unit(String text, int tokens, boolean passthrough, ContentBlockVO source) {
            this.text = text;
            this.tokens = tokens;
            this.passthrough = passthrough;
            this.source = source;
        }
    }
}