package com.linkroa.deepdataagent.knowledgebase.api;

import java.time.Duration;

/**
 * 摄入取消等待契约（跨 BC 服务边界，依赖倒置端口）。
 * <p>提供方为 rag BC（在飞摄入任务注册表归本 BC 所有，实现位于 rag.infrastructure），
 * 消费方为 knowledgebase BC 的文档删除链（当前为 BC 间进程内消费，不提供 REST 端点）。</p>
 *
 * <p>取消语义：文档状态置 DELETING 本身即取消信号——在飞 worker 的阶段边界检查点与
 * chunk 级协作中断轮询到删除链两态（DELETING/DELETE_FAILED）<strong>或行不存在</strong>
 * （DELETED 退出持久状态后的「已删除」表达）后以
 * {@code CANCELLED} 终止且不回写 FAILED（读到即自灭）。本契约仅提供「等待该文档的在飞任务退出」
 * 的确认能力，使删除链可在清退派生数据前尽量收敛在飞写入。</p>
 */
public interface IngestionCancellationApi {

    /**
     * 等待指定文档的在飞摄入任务退出。
     * <p>无在飞任务时立即返回 true（幂等，重复调用无副作用）。
     * 有在飞任务时最多等待 {@code timeout}：任务正常退出返回 true；
     * 超时未退出返回 false，由调用方决定后续动作（WARN 留痕后继续清退）。</p>
     *
     * @param documentId 文档主键，必填
     * @param timeout    等待上限，必填且应为正时长
     * @return true 表示已确认在飞任务退出（或本无在飞任务）；false 表示等待超时
     */
    boolean awaitTermination(Long documentId, Duration timeout);
}
