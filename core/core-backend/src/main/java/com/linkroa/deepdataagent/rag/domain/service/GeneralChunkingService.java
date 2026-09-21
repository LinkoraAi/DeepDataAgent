package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentChunkMode;
import com.linkroa.deepdataagent.rag.domain.model.ChunkVO;
import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import com.linkroa.deepdataagent.rag.domain.model.ParsedDocument;
import com.linkroa.deepdataagent.rag.domain.service.DelimiterParser.Unit;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 通用（general）文档级分块主管线（算法参考 {@code chunk_naive}）。
 * <p>职责：遍历解析产出的全部内容块，跨块累积文本单元后统一执行四条全局契约——
 * delimiter 即边界（长分隔符优先、切片内不残留）、不原子切分（超预算独立成块，仅无分隔符
 * 退化按句界切）、OVER_CAP 合并（running-sum 累加、单一封口线 {@code prev+unit > target}、
 * overlap 无条件前缀）与坐标标签剥离入元数据；按文档分块模式经 {@link ChunkStrategyRegistry}
 * 取策略后输出全局序号保持文档顺序的切片列表。</p>
 * <p><b>纯直通</b>：本类不做任何选路判断——用户/库级显式选择的模式恒为生效模式，
 * 模式缺省（null）等同 GENERAL；既不按文件后缀改道，也不按内容形态投票改道，
 * 更不在方法之间互相救援（非法模式串的拒绝由保存侧校验承担，注册表仅保留带回退 WARN 的
 * 防御路径）。保留的仅是<b>内容块类型分流</b>（解析层产物驱动）：多模态块摘出独立成块、
 * 其余文本块走所选策略，两路产物按源块顺序归并——这是同一方法内的块级处理，不是方法间选路。</p>
 */
@Service
public class GeneralChunkingService implements ChunkingService {

    /** 锚定签名分隔符（NUL，正文不可能出现，避免「类型+原文」拼接歧义）。 */
    private static final char SIGNATURE_DELIMITER = (char) 0;

    /** 真实 token 计数端口（窗口边界必须走真实 encode）。 */
    private final TokenCounter tokenCounter;

    /** 分块策略注册表（按文档分块模式取策略，未知模式回退 general 并 WARN）。 */
    private final ChunkStrategyRegistry strategyRegistry;

    /**
     * 构造主管线。
     *
     * @param tokenCounter     真实 token 计数端口
     * @param strategyRegistry 分块策略注册表
     */
    public GeneralChunkingService(TokenCounter tokenCounter, ChunkStrategyRegistry strategyRegistry) {
        this.tokenCounter = tokenCounter;
        this.strategyRegistry = strategyRegistry;
    }

    @Override
    public ChunkingOutcome chunkWithDispatch(ParsedDocument parsed, ChunkParams params,
                                             DocumentChunkMode mode) {
        List<ContentBlockVO> sourceBlocks = parsed.blocks();
        // 前置分流：多模态块摘出独立成块、不进切分器；
        // 其余块（TEXT / GENERIC）构成文本序列整体交给所选策略，两路产物按源块顺序归并重排序号。
        Map<ContentBlockVO, Integer> textAnchorByIdentity = new IdentityHashMap<>();
        Map<String, Integer> textAnchorBySignature = new HashMap<>();
        List<ContentBlockVO> textBlocks = new ArrayList<>(sourceBlocks.size());
        List<Integer> mediaIndices = new ArrayList<>();
        List<ChunkVO> mediaChunks = new ArrayList<>();
        int firstTextAnchor = -1;
        for (int index = 0; index < sourceBlocks.size(); index++) {
            ContentBlockVO block = sourceBlocks.get(index);
            if (block.isMultimodal()) {
                List<ChunkVO> passthrough = DelimiterParser.toChunkVOs(
                        List.of(DelimiterParser.passthroughUnit(block, tokenCounter)), tokenCounter);
                for (ChunkVO chunk : passthrough) {
                    mediaIndices.add(index);
                    mediaChunks.add(chunk);
                }
                continue;
            }
            if (firstTextAnchor < 0) {
                firstTextAnchor = index;
            }
            textAnchorByIdentity.putIfAbsent(block, index);
            textAnchorBySignature.putIfAbsent(signatureKey(block), index);
            textBlocks.add(block);
        }
        if (mediaChunks.isEmpty()) {
            // 纯文本/泛型文档：零改动走直通主干（多数场景的快速路径）
            return dispatchDirect(parsed, params, mode);
        }
        ChunkingOutcome textOutcome;
        if (textBlocks.isEmpty()) {
            // 纯媒体文档：无文本可切分，生效模式即显式模式（未配置记 GENERAL）
            textOutcome = new ChunkingOutcome(List.of(), effectiveMode(mode));
        } else {
            ParsedDocument textOnly = new ParsedDocument(parsed.parsedTextHash(), textBlocks,
                    parsed.fileName(), parsed.images());
            textOutcome = dispatchDirect(textOnly, params, mode);
        }
        return new ChunkingOutcome(mergeBySourceOrder(textOutcome.chunks(), mediaChunks, mediaIndices,
                textAnchorByIdentity, textAnchorBySignature, firstTextAnchor), textOutcome.resolvedMode());
    }

    /**
     * 直通主干：显式模式（null 等同 GENERAL）恒为生效模式，直接取对应策略执行，不改道、不投票。
     *
     * @param parsed 文本序列解析产物（非空块列表）
     * @param params 分块参数
     * @param mode   用户显式选择的模式，可为 null（null 等同 GENERAL）
     * @return 直通结果（切片列表 + 生效模式）
     */
    private ChunkingOutcome dispatchDirect(ParsedDocument parsed, ChunkParams params, DocumentChunkMode mode) {
        DocumentChunkMode resolved = effectiveMode(mode);
        return new ChunkingOutcome(executeStrategy(resolved, parsed, params), resolved);
    }

    /**
     * 生效模式归一：未配置（null）等同 GENERAL，其余原样直通。
     *
     * @param mode 用户显式选择的模式，可为 null
     * @return 非空生效模式
     */
    private DocumentChunkMode effectiveMode(DocumentChunkMode mode) {
        return ObjectUtils.isEmpty(mode) ? DocumentChunkMode.GENERAL : mode;
    }

    /**
     * 两路产物按源块顺序归并并重排全局序号。
     * <p>文本切片锚定其来源文本块的原始下标（先按对象引用命中，再按「类型+原文」签名命中——
     * 坐标标签富化会重建来源块实例导致引用失联；one 模式整篇拼接块落不到任何原文块上，
     * 锚定首个文本块，与 {@link #chunkAsWhole} 的既有归位口径一致）；媒体切片锚定自身原始下标。
     * 归并排序稳定：同锚点保持产出顺序，序号自 1 连续递增。</p>
     *
     * @param textChunks               文本序列的直通产物（文档序）
     * @param mediaChunks              媒体切片（源序）
     * @param mediaIndices             媒体切片各自的源块原始下标（与 mediaChunks 平行）
     * @param textAnchorByIdentity     文本块引用 → 原始下标
     * @param textAnchorBySignature    文本块签名 → 原始下标（引用失联时的回退锚定）
     * @param firstTextAnchor          首个文本块原始下标（无锚可寻切片的兜底）
     * @return 归并后全局序号连续（1..N）的切片列表
     */
    private List<ChunkVO> mergeBySourceOrder(List<ChunkVO> textChunks, List<ChunkVO> mediaChunks,
                                             List<Integer> mediaIndices,
                                             Map<ContentBlockVO, Integer> textAnchorByIdentity,
                                             Map<String, Integer> textAnchorBySignature,
                                             int firstTextAnchor) {
        List<AnchoredChunk> entries = new ArrayList<>(textChunks.size() + mediaChunks.size());
        for (int index = 0; index < mediaChunks.size(); index++) {
            entries.add(new AnchoredChunk(mediaIndices.get(index), mediaChunks.get(index)));
        }
        int fallbackAnchor = firstTextAnchor;
        for (ChunkVO chunk : textChunks) {
            int anchor = anchorOfTextChunk(chunk, textAnchorByIdentity, textAnchorBySignature, fallbackAnchor);
            entries.add(new AnchoredChunk(anchor, chunk));
            fallbackAnchor = Math.max(anchor, firstTextAnchor);
        }
        entries.sort(Comparator.comparingInt(AnchoredChunk::anchor));
        List<ChunkVO> out = new ArrayList<>(entries.size());
        int globalSequence = 1;
        for (AnchoredChunk entry : entries) {
            out.add(new ChunkVO(globalSequence++, entry.chunk().text(), entry.chunk().tokens(),
                    entry.chunk().block()));
        }
        return out;
    }

    /**
     * 解析文本切片的源块锚点：引用命中 → 签名命中 → 沿用上一切片锚点（合并链内切片不越过其前驱）。
     *
     * @param chunk                  文本切片
     * @param textAnchorByIdentity   文本块引用 → 原始下标
     * @param textAnchorBySignature  文本块签名 → 原始下标
     * @param previousAnchor         上一切片的已定锚点（兜底起点为首个文本块下标）
     * @return 该切片的排序锚点
     */
    private int anchorOfTextChunk(ChunkVO chunk, Map<ContentBlockVO, Integer> textAnchorByIdentity,
                                  Map<String, Integer> textAnchorBySignature, int previousAnchor) {
        ContentBlockVO source = chunk.block();
        if (ObjectUtils.isEmpty(source)) {
            return previousAnchor;
        }
        Integer anchored = textAnchorByIdentity.get(source);
        if (ObjectUtils.isEmpty(anchored)) {
            anchored = textAnchorBySignature.get(signatureKey(source));
        }
        return ObjectUtils.isEmpty(anchored) ? previousAnchor : anchored;
    }

    /**
     * 文本块锚定签名：类型 + 原文（坐标标签富化重建块实例后原文与类型不变，据此回退命中锚点）。
     *
     * @param block 文本内容块
     * @return 锚定签名
     */
    private String signatureKey(ContentBlockVO block) {
        return block.type() + SIGNATURE_DELIMITER + String.valueOf(block.text());
    }

    /**
     * 归并排序用中间条目：切片 + 其源块原始下标锚点。
     *
     * @param anchor 源块原始下标
     * @param chunk  切片
     */
    private record AnchoredChunk(int anchor, ChunkVO chunk) {
    }

    /**
     * 按生效模式执行策略分块（钉死契约）。
     * <p>策略经注册表解析（未知模式回退 general 并 WARN），两个方法内部形态特例不走
     * {@link ChunkStrategy#splitDocument} 文档级入口：general 走本类的<b>跨块累积</b>主管线
     * {@link #chunkGeneral}（块级 {@code split} 只在块内合并，跨块合并必须在此完成）；
     * one 为整篇拼接特例（全篇 1 chunk）。其余方法统一委托文档级入口。</p>
     *
     * @param resolved 生效的分块模式（非空）
     * @param parsed   解析结果
     * @param params   分块参数
     * @return 全局有序的分块结果
     */
    private List<ChunkVO> executeStrategy(DocumentChunkMode resolved, ParsedDocument parsed,
                                          ChunkParams params) {
        ChunkStrategy strategy = strategyRegistry.resolve(resolved);
        if (GeneralChunkStrategy.METHOD.equals(strategy.method())) {
            return chunkGeneral(parsed, params);
        }
        if (OneChunkStrategy.METHOD.equals(strategy.method())) {
            // one 模式：TEXT 块拼接为整篇单块、多模态块各自原位透传
            return chunkAsWhole(strategy, parsed, params);
        }
        return strategy.splitDocument(parsed.blocks(), params, tokenCounter);
    }

    /**
     * general 文档级主管线（算法参考 {@code chunk_naive}）。
     * <p>遍历内容块累积切分单元：多模态块透传为独立块并中断合并链；custom 分隔符模式下
     * 每 segment 一块、绕过 token 预算不做合并；常规模式按分隔符切界后统一
     * {@link DelimiterParser#mergeUnits}（OVER_CAP 单一封口线）。合并结果统一剥坐标标签并编排全局序号。</p>
     *
     * @param parsed 解析结果
     * @param params 分块参数
     * @return 全局有序的分块结果
     */
    private List<ChunkVO> chunkGeneral(ParsedDocument parsed, ChunkParams params) {
        List<String> delims = DelimiterParser.parseDelimiterField(params.delimiter());
        boolean custom = DelimiterParser.hasCustomDelimiter(params.delimiter());
        Pattern pattern = DelimiterParser.buildPattern(delims);
        List<Unit> units = new ArrayList<>();
        for (ContentBlockVO block : parsed.blocks()) {
            units.addAll(DelimiterParser.buildUnitsForBlock(block, pattern, custom,
                    params.chunkTokenNum(), tokenCounter));
        }
        List<Unit> blocks = custom
                ? units
                : DelimiterParser.mergeUnits(units, params.chunkTokenNum(),
                        params.overlappedPercent(), tokenCounter);
        return DelimiterParser.toChunkVOs(blocks, tokenCounter);
    }

    /**
     * one 模式整篇切分（ONE 模式多模态块原位透传）。
     * <p>仅将 {@code TYPE_TEXT} 内容块以换行拼接为单个整篇文本块（取首个非空元数据块携带 meta），
     * 交由 one 策略切分一次得到整篇 chunk；图片/表格/公式等多模态块不参与拼接，经
     * {@link DelimiterParser#passthroughUnit} 各自独立透传成 chunk，并保留其内容块引用与类型标识，
     * 使后续多模态增强管线（Stage 2 {@code isMultimodal()} 判定）能识别处理。</p>
     * <p>序号归位：整篇文本块锚定在文档中首个 TEXT 块的原始下标，多模态块保留各自原始下标，
     * 统一按原始下标升序编排全局序号（跨整篇 1..N 连续递增），保持文档原相对次序；
     * 纯 TEXT 文档无透传块，产出单 chunk，与基线行为逐字节一致。</p>
     *
     * @param strategy one 策略实例
     * @param parsed   解析结果
     * @param params   分块参数
     * @return 全局有序的整篇分块结果
     */
    private List<ChunkVO> chunkAsWhole(ChunkStrategy strategy, ParsedDocument parsed, ChunkParams params) {
        List<ContentBlockVO> blocks = parsed.blocks();
        // 仅 TEXT 块参与整篇拼接，多模态块不并入（不吞并图片/表格/公式文本）
        String joined = blocks.stream()
                .filter(block -> ContentBlockVO.TYPE_TEXT.equals(block.type()))
                .map(ContentBlockVO::text)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.joining("\n"));
        Map<String, Object> meta = null;
        for (ContentBlockVO block : blocks) {
            if (ObjectUtils.isNotEmpty(block.meta())) {
                meta = new HashMap<>(block.meta());
                break;
            }
        }
        // 按原始下标归位：key 为文档内原始块下标，value 为该位置产出的 chunk（TreeMap 保证升序）
        TreeMap<Integer, ChunkVO> placed = new TreeMap<>();
        int anchor = indexOfFirstText(blocks);
        if (anchor >= 0) {
            ContentBlockVO whole = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, joined, meta);
            List<ChunkVO> wholeChunks = strategy.split(whole, params, tokenCounter);
            if (CollectionUtils.isNotEmpty(wholeChunks)) {
                placed.put(anchor, wholeChunks.get(0));
            }
        }
        // 多模态（非 TEXT）块各自透传为独立 chunk，锚定在自身原始下标
        for (int index = 0; index < blocks.size(); index++) {
            ContentBlockVO block = blocks.get(index);
            if (ContentBlockVO.TYPE_TEXT.equals(block.type())) {
                continue;
            }
            Unit unit = DelimiterParser.passthroughUnit(block, tokenCounter);
            List<ChunkVO> passthrough = DelimiterParser.toChunkVOs(List.of(unit), tokenCounter);
            if (CollectionUtils.isNotEmpty(passthrough)) {
                placed.put(index, passthrough.get(0));
            }
        }
        return resequenceByIndexOrder(placed);
    }

    /**
     * 按原始下标升序重排全局序号（1..N 连续）。
     *
     * @param placed 原始块下标 → 该位置产出的切片（TreeMap，键升序）
     * @return 全局序号连续的切片列表
     */
    private List<ChunkVO> resequenceByIndexOrder(TreeMap<Integer, ChunkVO> placed) {
        List<ChunkVO> out = new ArrayList<>(placed.size());
        int globalSequence = 1;
        for (ChunkVO chunk : placed.values()) {
            out.add(new ChunkVO(globalSequence++, chunk.text(), chunk.tokens(), chunk.block()));
        }
        return out;
    }

    /**
     * 定位文档内首个 TEXT 内容块的下标（整篇文本块的归位锚点）。
     *
     * @param blocks 解析产出的内容块列表（按文档原序）
     * @return 首个 TEXT 块下标；无 TEXT 块返回 -1
     */
    private int indexOfFirstText(List<ContentBlockVO> blocks) {
        for (int index = 0; index < blocks.size(); index++) {
            if (ContentBlockVO.TYPE_TEXT.equals(blocks.get(index).type())) {
                return index;
            }
        }
        return -1;
    }
}