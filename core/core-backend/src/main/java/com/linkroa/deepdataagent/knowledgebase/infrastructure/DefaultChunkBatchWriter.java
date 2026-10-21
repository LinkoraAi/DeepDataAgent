package com.linkroa.deepdataagent.knowledgebase.infrastructure;

import com.linkroa.deepdataagent.knowledgebase.api.ChunkBatchWriter;
import com.linkroa.deepdataagent.knowledgebase.application.contract.ChunkDraft;
import com.linkroa.deepdataagent.knowledgebase.application.service.ChunkApplicationService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * {@link ChunkBatchWriter} 契约的默认实现（防腐层 / 契约适配器）。
 * <p>供 RAG 上下文的摄入管线通过知识库发布的契约完成「出队领取 → 切片落库 → 成功终态 /
 * 失败标记」状态闭环。本类只做契约适配，不含任何业务规则：
 * 入参校验与文档状态 CAS 流转全部委托给 {@link ChunkApplicationService} 对应方法。</p>
 */
@Component
public class DefaultChunkBatchWriter implements ChunkBatchWriter {

    private final ChunkApplicationService chunkApplicationService;

    public DefaultChunkBatchWriter(ChunkApplicationService chunkApplicationService) {
        this.chunkApplicationService = chunkApplicationService;
    }

    @Override
    public Map<Integer, Long> replaceForDocument(Long documentId, List<ChunkDraft> drafts, Long operatorId) {
        return chunkApplicationService.replaceForDocument(documentId, drafts, operatorId);
    }

    @Override
    public boolean markProcessing(Long documentId) {
        return chunkApplicationService.markProcessing(documentId);
    }

    @Override
    public boolean markProcessed(Long documentId) {
        return chunkApplicationService.markProcessed(documentId);
    }

    @Override
    public void markFailed(Long documentId, String errorMessage) {
        chunkApplicationService.markFailed(documentId, errorMessage);
    }
}
