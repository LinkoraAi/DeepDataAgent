package com.linkroa.deepdataagent.rag.infrastructure;

import com.linkroa.deepdataagent.knowledgebase.api.IngestionTaskSubmitter;
import com.linkroa.deepdataagent.rag.application.task.IngestionTaskQueue;
import org.springframework.stereotype.Component;

/**
 * {@link IngestionTaskSubmitter} 契约的默认实现（防腐层 / 契约适配器）。
 * <p>提供方为 knowledgebase BC（契约定义与调用挂点在其上传 / 重新解析路径），实现方为 rag BC：
 * 本类只做契约适配，解析任务提交直接委托 {@link IngestionTaskQueue#submit(Long)}
 * 的内存队列（幂等去重、停机拒收语义见队列与契约 Javadoc）。</p>
 *
 * @author DeepDataAgent
 */
@Component
public class DefaultIngestionTaskSubmitter implements IngestionTaskSubmitter {

    private final IngestionTaskQueue ingestionTaskQueue;

    /**
     * 构造摄入任务提交契约实现。
     *
     * @param ingestionTaskQueue 摄入任务内存队列
     */
    public DefaultIngestionTaskSubmitter(IngestionTaskQueue ingestionTaskQueue) {
        this.ingestionTaskQueue = ingestionTaskQueue;
    }

    @Override
    public boolean submit(Long documentId) {
        return ingestionTaskQueue.submit(documentId);
    }
}
