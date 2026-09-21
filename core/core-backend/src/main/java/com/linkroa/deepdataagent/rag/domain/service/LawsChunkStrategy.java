package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.rag.domain.model.ChunkVO;
import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import com.linkroa.deepdataagent.rag.domain.service.DelimiterParser.Unit;
import com.linkroa.deepdataagent.rag.domain.service.HierarchyParser.LevelLine;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 法条层级分块策略（method={@code laws}，/ 参考 depth=2）。
 * <p>块级 {@link #split} 保留历史逐块实现：对单个文本块按行判定层级，取「出现过的标题层级」
 * 第 2 小者为目标层级构建 Node 树并 DFS 展平为「标题路径 + 正文」前缀块
 * （如 {@code 第X章 > 第X条 > 正文}），每块独立成 chunk；laws 口径不做短块吸收。</p>
 * <p>文档级 {@link #splitDocument} 覆写为跨块收敛形态（BOOK/LAWS 文档级收敛与降级出口）：
 * 以整篇全部文本块为样本，先经 {@link HierarchyParser#removeContentsTable} 去目录再组型投票与
 * 层级树合并（depth=2，无吸收），合并块按「首行回查」
 * 归属到来源内容块后按原块序输出，多模态/非文本块原位整体透传；投票失败或无标题行时降级为
 * 固定 256 token 预算 + 中文句界分隔符的合并切分（不吃 {@code params} 的预算与重叠）。</p>
 */
@Component
public class LawsChunkStrategy implements ChunkStrategy {

    /** 策略方法名（注册表键，与 {@code ChunkStrategyRegistry} 映射一致）。 */
    public static final String METHOD = "laws";

    /** 文档级降级合并的重叠百分比（层级策略不施加 overlap，恒 0）。 */
    private static final int FALLBACK_OVERLAP_PERCENT = 0;

    /**
     * 文档级降级切分的常量正则（分隔符来自编译期常量
     * {@link ChunkParams#HIERARCHY_FALLBACK_DELIMITER}，预编译一次静态复用，
     * 降级路径不再逐文档重编译）。
     */
    private static final Pattern FALLBACK_DELIMITER_PATTERN = DelimiterParser.buildPattern(
            DelimiterParser.parseDelimiterField(ChunkParams.HIERARCHY_FALLBACK_DELIMITER));

    /** 日志器（降级出口留痕：纯直通后不再有 dispatch 级依据枚举，改由策略内 WARN）。 */
    private static final Logger log = LoggerFactory.getLogger(LawsChunkStrategy.class);

    @Override
    public String method() {
        return METHOD;
    }

    @Override
    public List<ChunkVO> split(ContentBlockVO block, ChunkParams params, TokenCounter counter) {
        List<LevelLine> lines = HierarchyParser.detectLevels(block.text());
        List<Unit> units = new ArrayList<>();
        if (HierarchyParser.hasHeading(lines)) {
            List<String> merged = HierarchyParser.treeMerge(lines, HierarchyParser.LAWS_DEPTH);
            for (String text : merged) {
                units.add(new Unit(text, counter.count(text), false, block));
            }
        } else {
            // 无标题兜底：整块单 chunk
            units.add(DelimiterParser.passthroughUnit(block, counter));
        }
        return DelimiterParser.toChunkVOs(units, counter);
    }

    @Override
    public boolean supports(ContentBlockVO block) {
        return ContentBlockVO.TYPE_TEXT.equals(block.type());
    }

    /**
     * 文档级分块：整篇文本块汇聚建树（depth=2，无吸收），降级走固定预算 naive。
     * <p>刻意忽略 {@code params} 的 token 预算与重叠比例（层级语义优先，overlap 恒 0）。</p>
     *
     * @param blocks  文档全部内容块（按文档顺序，可为 null / 空）
     * @param params  分块参数（本实现不消费其预算与重叠）
     * @param counter 真实 token 计数端口
     * @return 全局序号连续的分块结果（不返回 null）
     */
    @Override
    public List<ChunkVO> splitDocument(List<ContentBlockVO> blocks, ChunkParams params, TokenCounter counter) {
        if (CollectionUtils.isEmpty(blocks)) {
            return new ArrayList<>();
        }
        List<String> texts = new ArrayList<>();
        for (ContentBlockVO block : blocks) {
            if (supports(block)) {
                texts.add(block.text());
            }
        }
        List<String> merged = mergeDocumentHierarchy(texts, counter);
        if (CollectionUtils.isNotEmpty(merged)) {
            return assembleHierarchyChunks(blocks, merged, counter);
        }
        log.warn("LAWS 文档级层级合并未产出（组型投票失败或无标题行），降级固定 {} token + 中文句界合并切分",
                ChunkParams.HIERARCHY_FALLBACK_TOKEN_NUM);
        return fallbackDocumentChunks(blocks, counter);
    }

    /**
     * 文档级层级树合并（laws 口径：投票前先去目录，depth=2，不做短块吸收）。
     *
     * @param texts   整篇全部文本块文本（按文档顺序）
     * @param counter 真实 token 计数端口（本口径不消费，仅保持与 book 签名一致）
     * @return 合并后的块文本列表；组型投票失败、无标题行或输出为空时返回 null 表示需降级
     */
    private List<String> mergeDocumentHierarchy(List<String> texts, TokenCounter counter) {
        List<String> voteSamples = HierarchyParser.removeContentsTable(texts);
        int bull = HierarchyParser.bulletsCategory(voteSamples);
        if (bull < 0) {
            return null;
        }
        List<LevelLine> lines = HierarchyParser.detectLevelsInGroup(voteSamples, bull);
        if (!HierarchyParser.hasHeading(lines)) {
            return null;
        }
        List<String> merged = HierarchyParser.treeMerge(lines, HierarchyParser.LAWS_DEPTH);
        if (CollectionUtils.isEmpty(merged)) {
            return null;
        }
        return merged;
    }

    /**
     * 将层级合并块按「首行回查」归属到来源内容块，并按原始块序组装输出。
     * <p>溯源保障：先按文档序登记「剥离坐标标签并 trim 后的行文本 → 首个登记该行的块下标」
     * （{@link LinkedHashMap#putIfAbsent} 先登记者优先），每个合并块取首个可命中的非空有效行
     * 反查来源块；全部行均未命中时回落到首个文本块。合并块与透传块最终经
     * {@link DelimiterParser#toChunkVOs} 单次输出，保证全局序号连续。</p>
     *
     * @param blocks  文档全部内容块（按文档顺序，非空）
     * @param merged  层级合并后的块文本列表（非空）
     * @param counter 真实 token 计数端口
     * @return 全局序号连续的分块结果
     */
    private List<ChunkVO> assembleHierarchyChunks(List<ContentBlockVO> blocks, List<String> merged,
                                                  TokenCounter counter) {
        Map<String, Integer> lineOwner = new LinkedHashMap<>();
        int firstTextIndex = -1;
        for (int i = 0; i < blocks.size(); i++) {
            ContentBlockVO block = blocks.get(i);
            if (!supports(block)) {
                continue;
            }
            if (firstTextIndex < 0) {
                firstTextIndex = i;
            }
            for (String line : normalizeLines(block.text())) {
                String key = DelimiterParser.stripTags(line).trim();
                if (StringUtils.isNotEmpty(key)) {
                    lineOwner.putIfAbsent(key, i);
                }
            }
        }
        Map<Integer, List<Unit>> attributed = new LinkedHashMap<>();
        for (String text : merged) {
            int owner = resolveOwnerIndex(text, lineOwner, firstTextIndex);
            attributed.computeIfAbsent(owner, k -> new ArrayList<>())
                    .add(new Unit(text, counter.count(text), false, blocks.get(owner)));
        }
        List<Unit> ordered = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            ContentBlockVO block = blocks.get(i);
            if (supports(block)) {
                ordered.addAll(attributed.getOrDefault(i, new ArrayList<>()));
            } else {
                ordered.add(DelimiterParser.passthroughUnit(block, counter));
            }
        }
        return DelimiterParser.toChunkVOs(ordered, counter);
    }

    /**
     * 反查合并块的首个有效行所属来源块下标；未命中任何已登记行时回落首个文本块下标。
     *
     * @param text           合并块文本
     * @param lineOwner      行文本 → 来源块下标映射（先登记者优先）
     * @param firstTextIndex 首个文本块在 blocks 中的下标（merged 非空蕴含其存在，恒 ≥0）
     * @return 归属来源块下标
     */
    private int resolveOwnerIndex(String text, Map<String, Integer> lineOwner, int firstTextIndex) {
        for (String line : normalizeLines(text)) {
            String key = DelimiterParser.stripTags(line).trim();
            if (StringUtils.isEmpty(key)) {
                continue;
            }
            Integer owner = lineOwner.get(key);
            if (ObjectUtils.isNotEmpty(owner)) {
                return owner;
            }
        }
        return firstTextIndex;
    }

    /**
     * 按行拆分文本（换行符归一为 {@code \n}，保留空行以维持行序）。
     *
     * @param text 原始文本
     * @return 行文本列表
     */
    private List<String> normalizeLines(String text) {
        return List.of(text.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1));
    }

    /**
     * 文档级降级出口：投票失败/无标题行时，按固定 256 token 预算与中文句界分隔符做 naive 切分。
     * <p>逐块 {@link DelimiterParser#buildUnits} 携带来源块引用以保留溯源，跨块单次
     * {@link DelimiterParser#mergeUnits}（overlap 恒 0）；非文本块透传单元在合并链中自然
     * 中断累积并保持原位置。</p>
     *
     * @param blocks  文档全部内容块（非空）
     * @param counter 真实 token 计数端口
     * @return 全局序号连续的分块结果
     */
    private List<ChunkVO> fallbackDocumentChunks(List<ContentBlockVO> blocks, TokenCounter counter) {
        Pattern pattern = FALLBACK_DELIMITER_PATTERN;
        List<Unit> units = new ArrayList<>();
        for (ContentBlockVO block : blocks) {
            if (supports(block)) {
                units.addAll(DelimiterParser.buildUnits(block.text(), pattern,
                        ChunkParams.HIERARCHY_FALLBACK_TOKEN_NUM, counter, block));
            } else {
                units.add(DelimiterParser.passthroughUnit(block, counter));
            }
        }
        List<Unit> merged = DelimiterParser.mergeUnits(units,
                ChunkParams.HIERARCHY_FALLBACK_TOKEN_NUM, FALLBACK_OVERLAP_PERCENT, counter);
        return DelimiterParser.toChunkVOs(merged, counter);
    }
}
