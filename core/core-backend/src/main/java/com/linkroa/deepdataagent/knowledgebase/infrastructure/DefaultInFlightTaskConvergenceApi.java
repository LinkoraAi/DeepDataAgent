package com.linkroa.deepdataagent.knowledgebase.infrastructure;

import com.linkroa.deepdataagent.knowledgebase.api.InFlightTaskConvergenceApi;
import com.linkroa.deepdataagent.knowledgebase.application.service.InFlightTaskConvergenceService;
import org.springframework.stereotype.Component;

/**
 * {@link InFlightTaskConvergenceApi} 契约的默认实现（防腐层 / 契约适配器）。
 * <p>供 rag BC 的启动实例限定恢复与停机收敛流程经知识库发布的契约完成「状态一致性校验 + 条件置态」，
 * 本类只做契约适配，不含任何业务规则：判定与条件更新全部委托窄组件
 * {@link InFlightTaskConvergenceService}。</p>
 *
 * <p><b>依赖收窄（Bean 循环消除）</b>：本类原先直接依赖 {@code DocumentApplicationService} 与
 * {@code KnowledgeBaseApplicationService}，而这两个应用服务持有 rag 实现的跨 BC 出站端口
 * （{@code KbCleanupTaskSubmitter} / {@code IngestionCancellationApi} 等），使知识库与 rag 的
 * Bean 装配图形成闭环、应用无法启动。本类改为只依赖「仅含领域仓储的窄组件」——适配器只依赖
 * 叶子能力，构造器签名即编译期约束，任何人想把应用服务塞回来都会直接编译失败。</p>
 *
 * @author DeepDataAgent
 */
@Component
public class DefaultInFlightTaskConvergenceApi implements InFlightTaskConvergenceApi {

    /** 在飞任务收敛原语窄组件（只依赖领域仓储，不携带任何跨 BC 出站端口） */
    private final InFlightTaskConvergenceService inFlightTaskConvergenceService;

    /**
     * 构造在飞任务收敛契约实现。
     *
     * @param inFlightTaskConvergenceService 在飞任务收敛原语窄组件
     */
    public DefaultInFlightTaskConvergenceApi(InFlightTaskConvergenceService inFlightTaskConvergenceService) {
        this.inFlightTaskConvergenceService = inFlightTaskConvergenceService;
    }

    @Override
    public boolean convergeIngestion(Long documentId, String errorMessage) {
        return inFlightTaskConvergenceService.convergeIngestion(documentId, errorMessage);
    }

    @Override
    public boolean convergeDocumentCleanup(Long documentId, String errorMessage) {
        return inFlightTaskConvergenceService.convergeDocumentCleanup(documentId, errorMessage);
    }

    @Override
    public boolean convergeKbCleanup(Long kbId, String errorMessage) {
        return inFlightTaskConvergenceService.convergeKbCleanup(kbId, errorMessage);
    }
}