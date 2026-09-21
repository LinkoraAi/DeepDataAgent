package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import org.apache.commons.lang3.ObjectUtils;

/**
 * 抽取缓存重放的单分块输入（{@link EntityExtractionService#replayExtractedChunks} 的分块侧入参）。
 * <p>字段集合按「删除期可构造」口径收敛：存活分块的库内行直接提供主键、正文与块 meta；
 * 多模态分块所需的<b>定形主实体名</b>按块随入参携带（该值与 {@code ctx.mediaPrimaryEntityName}
 * 同源，抽取期 sidecar 归属边注入消费的即此定形名，重放必须由调用方提供同等值才能与抽取同形）。
 * 摄入期一次性状态（所属文档上下文、提示词、模型引用）不在本记录范围内——解析链不消费它们。</p>
 *
 * @param chunkId                 分块真实主键（必填，重放记录的溯源来源键与归属追溯键）
 * @param text                    分块正文（重放解析不消费，按任务口径随分块输入完整携带，
 *                                供调用方与实现侧留痕定位使用）
 * @param block                   来源内容块（携带多模态类型判定所需的块 meta，可为 null 表示纯文本块）
 * @param mediaPrimaryEntityName  该分块的定形多模态主实体名（仅多模态块重放 sidecar 归属边所需；
 *                                空白时对多模态块跳过归属边注入并 WARN，与抽取期同一口径）
 * @author DeepDataAgent
 */
public record ReplayChunkInput(
        Long chunkId,
        String text,
        ContentBlockVO block,
        String mediaPrimaryEntityName) {

    /**
     * 紧凑构造器：不变量校验。
     */
    public ReplayChunkInput {
        if (ObjectUtils.isEmpty(chunkId)) {
            throw new IllegalArgumentException("重放分块输入 chunkId 不能为空");
        }
    }
}
