package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentChunkMode;
import com.linkroa.deepdataagent.rag.domain.model.ChunkVO;
import com.linkroa.deepdataagent.rag.domain.model.ParsedDocument;

import java.util.List;

/**
 * 分块编排端口。
 * <p>文档级主管线：遍历解析产出的内容块，按文档分块模式从 {@link ChunkStrategyRegistry}
 * 取策略，并统一执行四条全局契约（delimiter 即边界 / 不原子切分 / OVER_CAP 合并 /
 * overlap 前缀）、坐标标签剥离入元数据与全局序号编排。</p>
 * <p><b>纯直通</b>：分块结果 100% 由「所选方法 + 配置参数」决定——不做文件后缀改道、
 * 不做内容形态投票改道、不在方法之间互相救援。{@code chunkWithDispatch} 为主干入口
 * （返回生效模式），两个 {@code chunk} 重载为其便捷门面（default 委托，仅取切片列表）。</p>
 */
public interface ChunkingService {

    /**
     * 按默认分块模式（未配置模式，等同 GENERAL）执行分块。
     *
     * @param parsed 解析结果（含结构化内容块），非空
     * @param params 分块参数（软目标 token、重叠比例、分隔符），非空
     * @return 分块结果列表，sequence 从 1 递增；含全白块的文档返回空列表
     */
    default List<ChunkVO> chunk(ParsedDocument parsed, ChunkParams params) {
        return chunk(parsed, params, null);
    }

    /**
     * 按指定分块模式执行分块（{@link #chunkWithDispatch} 的仅取切片列表门面）。
     * <p>模式为 null 或未显式选择（GENERAL）时按 GENERAL 算法直通执行；该模式策略未注册时
     * （{@link ChunkStrategyRegistry#resolve} 防御回退）回退 general 策略并输出 WARN，不抛异常。</p>
     *
     * @param parsed 解析结果（含结构化内容块），非空
     * @param params 分块参数（软目标 token、重叠比例、分隔符），非空
     * @param mode   文档分块模式，可为 null（null 等同 GENERAL）
     * @return 分块结果列表，sequence 从 1 递增
     */
    default List<ChunkVO> chunk(ParsedDocument parsed, ChunkParams params, DocumentChunkMode mode) {
        return chunkWithDispatch(parsed, params, mode).chunks();
    }

    /**
     * 分块主干入口（纯直通）。
     * <p>规则只有一条：显式模式恒为生效模式，模式缺省（null）等同 GENERAL；
     * 生效模式对应的策略直接执行，不因文件后缀或内容形态改道，也不跨方法救援。
     * 唯一保留的分流是<b>块类型</b>分流（解析层产物驱动）：多模态块摘出独立成块，
     * 其余文本块走所选策略，两路产物按源块顺序归并并重排全局序号。</p>
     *
     * @param parsed 解析结果（含结构化内容块与文件名），非空
     * @param params 分块参数（软目标 token、重叠比例、分隔符），非空
     * @param mode   用户显式选择的分块模式，可为 null（null 等同 GENERAL）
     * @return 分块结果（切片列表 + 生效模式），非空
     */
    ChunkingOutcome chunkWithDispatch(ParsedDocument parsed, ChunkParams params, DocumentChunkMode mode);
}