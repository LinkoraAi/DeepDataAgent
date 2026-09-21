package com.linkroa.deepdataagent.rag.infrastructure;

import com.linkroa.deepdataagent.knowledgebase.api.IngestionCancellationApi;
import com.linkroa.deepdataagent.rag.application.task.IngestionTaskQueue;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * {@link IngestionCancellationApi} 契约的默认实现（防腐层 / 契约适配器）。
 * <p>本类只做契约适配，不含业务规则：null 参数防御在此完成
 * （documentId 为空视为「无任务」直接返回 true；timeout 为空退化为零时长仅探测），
 * 实际确认逻辑委托 rag BC 的 {@link IngestionTaskQueue}（
 * 排队任务可撤下、在飞任务等待其于阶段边界自灭，「先登记闩、后领取」的竞态闭合由队列保证）。</p>
 *
 * <p>消费方为 knowledgebase BC 文档删除链（进程内调用，短事务受理之后的事务外阶段）；
 * 超时返回 false 不抛异常，由消费方决定 WARN 留痕后是否继续清退。</p>
 *
 * @author DeepDataAgent
 */
@Component
public class DefaultIngestionCancellationApi implements IngestionCancellationApi {

    private final IngestionTaskQueue ingestionTaskQueue;

    /**
     * 构造摄入取消等待契约实现。
     *
     * @param ingestionTaskQueue 摄入任务内存队列（排队撤下 + 在飞注册表持有方）
     */
    public DefaultIngestionCancellationApi(IngestionTaskQueue ingestionTaskQueue) {
        this.ingestionTaskQueue = ingestionTaskQueue;
    }

    @Override
    public boolean awaitTermination(Long documentId, Duration timeout) {
        if (ObjectUtils.isEmpty(documentId)) {
            return true;
        }
        Duration effectiveTimeout = ObjectUtils.isEmpty(timeout) ? Duration.ZERO : timeout;
        return ingestionTaskQueue.awaitTermination(documentId, effectiveTimeout);
    }
}
