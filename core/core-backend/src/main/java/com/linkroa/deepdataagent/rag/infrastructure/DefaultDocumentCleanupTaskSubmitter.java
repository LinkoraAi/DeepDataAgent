package com.linkroa.deepdataagent.rag.infrastructure;

import com.linkroa.deepdataagent.knowledgebase.api.DocumentCleanupTaskSubmitter;
import com.linkroa.deepdataagent.rag.application.task.CleanupInFlightRegistry;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.stereotype.Component;

/**
 * {@link DocumentCleanupTaskSubmitter} 契约的默认实现（防腐层 / 契约适配器）。
 * <p>契约提供方为 knowledgebase BC（文档删除受理在事务提交后投递），实现方为 rag BC：
 * 把调用方给出的文档清退任务体投递为 {@link DeletionCleanupTaskExecutor} 上的一个虚拟线程任务
 * ——任务体本身归 knowledgebase BC 所有（掐灭等待 → 资产回收 → 分批 DB 清退 → 收口条件 DELETE），
 * 本适配器不含任何业务规则，只做「在飞键构造 + 执行器委托」。</p>
 *
 * <p>去重：在飞键取 {@link CleanupInFlightRegistry#docKey(Long)}，同一文档在飞期间的重复投递
 * 由执行器登记闸门挡下并返回 {@code false}，本适配器等价透传该结果——受理侧据此回「删除中」幂等。</p>
 *
 * <p>投递失败：执行器停机 / 未初始化时抛 {@link IllegalStateException}，本适配器<b>原样上抛、不吞</b>
 * ——处置责任在调用方（受理事务已提交，回滚无意义）：文档停留 DELETING 并以 ERROR 留痕，
 * 由本实例启动恢复一致性校验收敛为 {@code DELETE_FAILED} 兜底（不再自动重触发）。</p>
 *
 * @author DeepDataAgent
 */
@Component
public class DefaultDocumentCleanupTaskSubmitter implements DocumentCleanupTaskSubmitter {

    private final DeletionCleanupTaskExecutor deletionCleanupTaskExecutor;

    /**
     * 构造文档清退任务提交契约实现。
     *
     * @param deletionCleanupTaskExecutor 删除清退虚拟线程执行器（并发配额 + 在飞去重）
     */
    public DefaultDocumentCleanupTaskSubmitter(DeletionCleanupTaskExecutor deletionCleanupTaskExecutor) {
        this.deletionCleanupTaskExecutor = deletionCleanupTaskExecutor;
    }

    /**
     * 向删除清退虚拟线程执行器提交一个文档的清退任务体。
     *
     * @param documentId  待清退文档主键，必填
     * @param cleanupTask 清退任务体（调用方自带全量 catch 与失败留痕），必填
     * @return {@code true} 表示已提交异步执行；{@code false} 表示该文档清退任务已在飞（去重命中）
     * @throws IllegalArgumentException 文档ID为空
     */
    @Override
    public boolean submit(Long documentId, Runnable cleanupTask) {
        if (ObjectUtils.isEmpty(documentId)) {
            throw new IllegalArgumentException("文档清退任务文档ID不能为空");
        }
        // 任务体空值校验与停机 IllegalStateException 均由执行器统一抛出，本适配器不重复实现
        return deletionCleanupTaskExecutor.submit(CleanupInFlightRegistry.docKey(documentId), cleanupTask);
    }
}
