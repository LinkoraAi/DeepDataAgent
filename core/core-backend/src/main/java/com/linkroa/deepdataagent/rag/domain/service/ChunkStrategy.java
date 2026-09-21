package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.rag.domain.model.ChunkVO;
import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;

import org.apache.commons.collections4.CollectionUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 分块策略端口。
 * <p>策略只负责<b>单个内容块</b>的切分；跨块的遍历、全局序号编排与坐标标签剥离由
 * {@code ChunkingService} 负责。各策略以 {@code method()} 返回值作为注册表键，
 * 由 {@link ChunkStrategyRegistry} 按分块模式路由。</p>
 */
public interface ChunkStrategy {

    /**
     * 策略方法名（注册表键）。
     * <p>命名约定：本策略实现类固定返回 {@code naive} / {@code qa} / {@code book} /
     * {@code laws} / {@code table} / {@code presentation} / {@code one} 之一；同一方法名仅允许
     * 一个策略 bean，重复注册在构造注册表时 fail-fast。</p>
     *
     * @return 策略方法名，非空
     */
    String method();

    /**
     * 对单个内容块执行分块。
     * <p>返回列表内 {@code sequence} 由策略自身维持从 1 递增（块内局部序号），最终由
     * {@code ChunkingService} 在文档级做全局重编号；不得返回 null 或空列表——无法切分的块由
     * 策略负责兜底为单块。</p>
     *
     * @param block   待切分内容块（多模态块由对应策略决定是否支持）
     * @param params  分块参数（软目标 token 数、重叠比例、分隔符等，来自 rag_engine_config）
     * @param counter 真实 token 计数端口（窗口边界计算必须走真实 encode）
     * @return 切分结果列表，逐项满足 {@code ChunkVO} 不变量
     */
    List<ChunkVO> split(ContentBlockVO block, ChunkParams params, TokenCounter counter);

    /**
     * 判断块类型是否属于本策略的支持范围。
     * <p>供 {@code ChunkingService} 按块分流：同一文档内不同类型块可落入不同策略
     * （如文本块走 naive、图片块走多模态模板化），避免策略内部硬编码类型分支。</p>
     *
     * @param block 待判别内容块，非空
     * @return true 表示本策略可处理该类型
     */
    boolean supports(ContentBlockVO block);

    /**
     * 对整篇文档的内容块列表执行文档级分块。
     * <p>默认实现为「逐块处理」形态：按文档顺序遍历内容块，{@link #supports} 为 true 的块调用
     * {@link #split} 收集结果，其余块整体透传为单 chunk（与 {@code GeneralChunkingService}
     * 原有逐块行为等价），最后统一重排全局序号（从 1 连续递增）。
     * 需要跨块上下文的策略（如 {@link BookChunkStrategy} / {@link LawsChunkStrategy}）覆写本方法，
     * 以整篇文本块为样本做层级投票与树合并。</p>
     *
     * @param blocks  文档全部内容块（按文档顺序，可为 null / 空）
     * @param params  分块参数（来自 rag_engine_config，覆写实现可忽略 token 预算与重叠）
     * @param counter 真实 token 计数端口
     * @return 全局序号连续的分块结果（不返回 null）
     */
    default List<ChunkVO> splitDocument(List<ContentBlockVO> blocks, ChunkParams params, TokenCounter counter) {
        List<ChunkVO> out = new ArrayList<>();
        if (CollectionUtils.isEmpty(blocks)) {
            return out;
        }
        int globalSequence = 1;
        for (ContentBlockVO block : blocks) {
            List<ChunkVO> blockChunks;
            if (supports(block)) {
                blockChunks = split(block, params, counter);
            } else {
                blockChunks = DelimiterParser.toChunkVOs(
                        List.of(DelimiterParser.passthroughUnit(block, counter)), counter);
            }
            for (ChunkVO chunk : blockChunks) {
                out.add(new ChunkVO(globalSequence++, chunk.text(), chunk.tokens(), chunk.block()));
            }
        }
        return out;
    }
}