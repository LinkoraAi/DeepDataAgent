package com.linkroa.deepdataagent.rag.infrastructure;

import com.linkroa.deepdataagent.knowledgebase.api.DocumentGraphConvergenceApi;
import com.linkroa.deepdataagent.knowledgebase.api.DocumentGraphConvergencePlan;
import com.linkroa.deepdataagent.knowledgebase.api.DocumentGraphConvergenceResult;
import com.linkroa.deepdataagent.rag.application.service.DocumentGraphConvergenceService;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * {@link DocumentGraphConvergenceApi} 契约的默认实现（防腐层 / 契约适配器）。
 * <p>本类只做契约适配与不透明句柄的收口，不含业务规则：入参 null 防御、跨实现句柄类型校验在此
 * 完成，三步执行委托 rag BC 应用服务 {@link DocumentGraphConvergenceService}，复用其两段式
 * 事务边界（prepare 事务外远程计算零写入 / apply 单事务写回+收缩+缓存回收 / reconcile 提交后收口，
 * 见契约 Javadoc 的调用次序与时机声明）。句柄以 {@link DocumentGraphConvergenceService.ConvergencePlan}
 * （实现 {@link DocumentGraphConvergencePlan} 标记接口）承载 rag 侧重建上下文与重建计划，
 * 对 knowledgebase BC 消费方保持不透明。</p>
 *
 * <p>消费方为 knowledgebase BC 删除原语与摄入换代链（进程内调用）；失败异常原样上抛，
 * 由消费方决定留痕与重试策略（删除链零重试语义，用户重删即自 prepare 重推）。</p>
 *
 * @author DeepDataAgent
 */
@Component
public class DefaultDocumentGraphConvergenceApi implements DocumentGraphConvergenceApi {

    private final DocumentGraphConvergenceService documentGraphConvergenceService;

    /**
     * 构造图谱贡献收敛契约实现。
     *
     * @param documentGraphConvergenceService 图谱贡献收敛应用服务
     */
    public DefaultDocumentGraphConvergenceApi(DocumentGraphConvergenceService documentGraphConvergenceService) {
        this.documentGraphConvergenceService = documentGraphConvergenceService;
    }

    @Override
    public DocumentGraphConvergencePlan prepare(Long kbId, Long deletedDocumentId,
                                                Collection<Long> removedChunkIds, String operator) {
        return documentGraphConvergenceService.prepare(kbId, deletedDocumentId, removedChunkIds, operator);
    }

    @Override
    public DocumentGraphConvergenceResult apply(DocumentGraphConvergencePlan plan) {
        return documentGraphConvergenceService.apply(castPlan(plan));
    }

    @Override
    public void reconcilePendingVectorContent(DocumentGraphConvergencePlan plan,
                                              DocumentGraphConvergenceResult result) {
        documentGraphConvergenceService.reconcilePendingVectorContent(castPlan(plan), result);
    }

    /**
     * 契约句柄 → rag 侧实现类型（消费方 MUST 只使用 {@code prepare} 返回的同一实例；
     * 外部伪造或跨实现句柄按非法参数拒绝，MUST NOT 静默降级）。
     *
     * @param plan 契约句柄，可为 null（reconcile 场景按零操作放行，apply 场景由服务侧拒绝）
     * @return 收敛计划句柄实现；入参为 null 或非本实现类型时返回 {@code null}
     * @throws IllegalArgumentException plan 非 null 且类型非法
     */
    private DocumentGraphConvergenceService.ConvergencePlan castPlan(DocumentGraphConvergencePlan plan) {
        if (ObjectUtils.isEmpty(plan)) {
            return null;
        }
        if (!(plan instanceof DocumentGraphConvergenceService.ConvergencePlan convergencePlan)) {
            throw new IllegalArgumentException("非法的收敛计划句柄：MUST 使用 prepare 返回的同一实例");
        }
        return convergencePlan;
    }
}
