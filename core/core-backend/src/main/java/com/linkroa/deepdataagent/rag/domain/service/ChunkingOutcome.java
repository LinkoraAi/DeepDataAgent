package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentChunkMode;
import com.linkroa.deepdataagent.rag.domain.model.ChunkVO;
import org.apache.commons.collections4.CollectionUtils;

import java.util.List;

/**
 * 一次分块调用的完整结果。
 * <p>纯直通下「生效模式」恒等于用户/库级显式模式（未配置记 GENERAL），不再存在
 * 「显式模式 ≠ 生效模式」的路由覆盖事件，故本记录只携带切片列表与生效模式两项
 * （原决策依据枚举已随选路机制一并删除）。</p>
 *
 * @param chunks       切片列表（全局序号从 1 递增），null 归一化为空列表
 * @param resolvedMode 最终生效的分块模式（非空；未配置模式记 GENERAL）
 */
public record ChunkingOutcome(List<ChunkVO> chunks, DocumentChunkMode resolvedMode) {

    /**
     * 紧凑构造器：对 null / 空切片列表容错，统一归一化为不可变空列表。
     */
    public ChunkingOutcome {
        if (CollectionUtils.isEmpty(chunks)) {
            chunks = List.of();
        }
    }
}
