package com.linkroa.deepdataagent.rag.infrastructure;

import com.linkroa.deepdataagent.knowledgebase.api.KbCleanupTaskSubmitter;
import com.linkroa.deepdataagent.rag.application.service.KbCleanupOrchestrationService;
import com.linkroa.deepdataagent.rag.application.task.CleanupInFlightRegistry;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.stereotype.Component;

/**
 * {@link KbCleanupTaskSubmitter} 契约的默认实现（防腐层 / 契约适配器）。
 * <p>契约提供方为 knowledgebase BC（知识库删除受理与重删受理路径在事务提交后投递），实现方为 rag BC。
 * 本类只做契约适配：把「整库清退」投递为 {@link DeletionCleanupTaskExecutor} 上的一个虚拟线程任务
 * （原内存有界队列 + 单消费者机器已退役），任务体即调用既有的
 * {@link KbCleanupOrchestrationService#cleanup(Long)} 清退编排入口——
 * 该服务自身永不抛出（失败以 {@code [KB-CLEANUP]} ERROR 留痕并置 DELETE_FAILED），
 * 执行器另做兜底捕获以保证在飞登记与并发配额一定释放。</p>
 *
 * <p>去重与在飞判定：在飞键取 {@link CleanupInFlightRegistry#kbKey(Long)}，同一知识库在飞期间的重复投递
 * 由执行器登记闸门挡下并返回 {@code false}——本适配器把该返回值<b>原样上抛给受理侧</b>作为
 * 「清退已在飞（幂等跳过）」与「崩溃遗留（本次新建任务重触发）」的唯一判定依据，
 * 与契约「重复提交不产生第二个任务、并在飞判定内聚于返回值」口径一致。</p>
 *
 * <p>投递失败：执行器停机 / 未初始化时抛 {@link IllegalStateException}，本适配器
 * <b>原样上抛、不吞、不重复留痕</b>——契约 Javadoc 已把「仅 ERROR 留痕、库停留 DELETING」
 * 的处置责任明确交给调用方（受理侧事务已提交，回滚无意义），调用方捕获后 ERROR 留痕即可；
 * 停留的 DELETING 库由 {@code CleanupStartupMaintenance} 本实例启动恢复一致性校验收敛为
 * {@code DELETE_FAILED} 兜底（不再自动重触发续跑）。</p>
 *
 * @author DeepDataAgent
 */
@Component
public class DefaultKbCleanupTaskSubmitter implements KbCleanupTaskSubmitter {

    private final DeletionCleanupTaskExecutor deletionCleanupTaskExecutor;

    private final KbCleanupOrchestrationService kbCleanupOrchestrationService;

    /**
     * 构造整库清退任务提交契约实现。
     *
     * @param deletionCleanupTaskExecutor    删除清退虚拟线程执行器（并发配额 + 在飞去重）
     * @param kbCleanupOrchestrationService  整库清退编排服务（任务体调用方）
     */
    public DefaultKbCleanupTaskSubmitter(DeletionCleanupTaskExecutor deletionCleanupTaskExecutor,
                                         KbCleanupOrchestrationService kbCleanupOrchestrationService) {
        this.deletionCleanupTaskExecutor = deletionCleanupTaskExecutor;
        this.kbCleanupOrchestrationService = kbCleanupOrchestrationService;
    }

    /**
     * 向删除清退虚拟线程执行器提交一个知识库的整库清退任务，并把执行器的在飞去重结果回传调用方。
     *
     * @param kbId 待清退知识库主键，必填
     * @return {@code true} 表示本次新建并提交了清退任务；{@code false} 表示该库清退已在飞（幂等跳过）
     * @throws IllegalStateException    执行器正在停机（或尚未初始化），拒收新任务；调用方按契约仅 ERROR 留痕
     * @throws IllegalArgumentException kbId 为空
     */
    @Override
    public boolean submit(Long kbId) {
        if (ObjectUtils.isEmpty(kbId)) {
            throw new IllegalArgumentException("整库清退任务知识库ID不能为空");
        }
        // 执行器返回 false 即该库清退任务已在飞（去重命中，执行器侧已 DEBUG 留痕）：
        // 原样回传给受理侧作为「幂等回删除中」与「崩溃遗留重触发」的判定依据
        return deletionCleanupTaskExecutor.submit(CleanupInFlightRegistry.kbKey(kbId),
                () -> kbCleanupOrchestrationService.cleanup(kbId));
    }
}
