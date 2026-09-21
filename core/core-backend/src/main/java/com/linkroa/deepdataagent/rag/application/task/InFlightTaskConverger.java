package com.linkroa.deepdataagent.rag.application.task;

import com.linkroa.deepdataagent.knowledgebase.api.InFlightTaskConvergenceApi;
import com.linkroa.deepdataagent.knowledgebase.api.InFlightTaskRegistry;
import com.linkroa.deepdataagent.knowledgebase.api.InFlightTaskType;
import org.apache.commons.lang3.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 在飞任务收敛器（启动恢复与停机收敛共用的「一致性校验 + 条件置态 + 移除成员」原语）。
 * <p>把两个收敛场景（应用就绪后的崩溃残留收敛、停机宽限期满后的未完成收敛）的公共时序收敛为一处，
 * 避免两处各写一遍而出现语义漂移：</p>
 * <ol>
 *   <li>读取数据库权威状态并与注册表凭据所隐含的在飞态比对（由
 *       {@link InFlightTaskConvergenceApi} 在 KB 侧完成，MUST NOT 以「集合中仍有该 ID」为
 *       直接依据改写数据库状态）；</li>
 *   <li>一致 → 条件置目标态；不一致（已是终态 / 已被并发推进 / 行已不存在）→ 不改动数据库状态，
 *       且不产生任何数据库写操作；</li>
 *   <li>置态<strong>成功</strong>或本就无需置态 → 移除注册表成员；置态写入失败 → 保留成员 + ERROR 留痕，
 *       交由下次启动重试（否则会产生「状态停在中间态而注册表无迹可寻」的永久不可见悬挂）。</li>
 * </ol>
 *
 * <p>本组件不是停机协调机制：两个在飞执行器仍各自等待各自的宽限期，保持既有销毁路径不变。
 * 它只提供收敛动作的公共实现（含「停机第一时间清空本实例键」的入口）。</p>
 *
 * @author DeepDataAgent
 */
@Component
public class InFlightTaskConverger {

    private static final Logger log = LoggerFactory.getLogger(InFlightTaskConverger.class);

    private final InFlightTaskRegistry inFlightTaskRegistry;

    private final InFlightTaskConvergenceApi inFlightTaskConvergenceApi;

    /**
     * 构造在飞任务收敛器。
     *
     * @param inFlightTaskRegistry       在飞任务注册表（成员移除与停机清空）
     * @param inFlightTaskConvergenceApi 状态一致性校验与条件置态契约（KB 侧实现）
     */
    public InFlightTaskConverger(InFlightTaskRegistry inFlightTaskRegistry,
                                 InFlightTaskConvergenceApi inFlightTaskConvergenceApi) {
        this.inFlightTaskRegistry = inFlightTaskRegistry;
        this.inFlightTaskConvergenceApi = inFlightTaskConvergenceApi;
    }

    /**
     * 收敛单个在飞任务：一致性校验 → 条件置态 → 移除注册表成员。
     *
     * @param taskType    任务类型，必填
     * @param taskId      任务主体主键，必填
     * @param failMessage 置态时写入的失败原因 / 留痕，可为空
     * @return {@code true} 表示一致且已置目标态；{@code false} 表示不一致（零写操作）或置态失败（成员已保留）
     */
    public boolean converge(InFlightTaskType taskType, Long taskId, String failMessage) {
        if (ObjectUtils.isEmpty(taskType) || ObjectUtils.isEmpty(taskId)) {
            return false;
        }
        boolean converged;
        try {
            converged = applyConvergence(taskType, taskId, failMessage);
        } catch (RuntimeException e) {
            log.error("[在飞任务收敛] 置态失败，保留注册表成员待下次启动重试: taskType={}, taskId={}",
                    taskType, taskId, e);
            return false;
        }
        // 一致置态成功、或本就无需置态（已是终态 / 已被并发推进 / 行已不存在）→ 两种情况成员均须移除
        inFlightTaskRegistry.unregister(taskType, taskId);
        if (converged) {
            log.info("[在飞任务收敛] 已置目标态并移除成员: taskType={}, taskId={}", taskType, taskId);
        } else {
            log.info("[在飞任务收敛] 状态不一致，未改动数据库状态（零写操作），仅移除成员: taskType={}, taskId={}",
                    taskType, taskId);
        }
        return converged;
    }

    /**
     * 按本实例标识读取某类任务的全部残留并逐个收敛（启动实例限定恢复入口）。
     * <p>读取只命中本实例的键，MUST NOT 触碰其他实例的残留。</p>
     *
     * @param taskType    任务类型，必填
     * @param failMessage 置态时写入的失败原因 / 留痕，可为空
     * @return 实际置态的任务数（不一致分支不计入）
     */
    public int convergeAllOfType(InFlightTaskType taskType, String failMessage) {
        Set<Long> taskIds = inFlightTaskRegistry.findInFlightTaskIds(taskType);
        if (ObjectUtils.isEmpty(taskIds)) {
            return 0;
        }
        int converged = 0;
        for (Long taskId : taskIds) {
            if (converge(taskType, taskId, failMessage)) {
                converged++;
            }
        }
        return converged;
    }

    /**
     * 清空本实例全部注册表键（停机流程收到终止信号后的<strong>首个动作</strong>，先于宽限等待）。
     * <p>目的是使「新进程读到非空键」等价于「真崩溃（来不及清空）」，消除滚动发布期间新旧进程并存
     * 造成的误判。清空失败仅 ERROR 留痕，MUST NOT 阻断停机链的后续等待与收敛。</p>
     */
    public void clearInstanceRegistryQuietly() {
        try {
            inFlightTaskRegistry.clearInstanceRegistry();
            log.info("[在飞任务注册表] 停机第一时间已清空本实例全部注册表键");
        } catch (RuntimeException e) {
            log.error("[在飞任务注册表] 清空本实例注册表键失败，滚动发布窗口未消除（已登记的已知风险）", e);
        }
    }

    /**
     * 分派任务类型对应的收敛动作。
     *
     * @param taskType    任务类型
     * @param taskId      任务主体主键
     * @param failMessage 失败原因 / 留痕
     * @return {@code true} 表示一致且已置目标态；{@code false} 表示不一致（零写操作）
     */
    private boolean applyConvergence(InFlightTaskType taskType, Long taskId, String failMessage) {
        return switch (taskType) {
            case DOC_INGESTION -> inFlightTaskConvergenceApi.convergeIngestion(taskId, failMessage);
            case DOC_CLEANUP -> inFlightTaskConvergenceApi.convergeDocumentCleanup(taskId, failMessage);
            case KB_CLEANUP -> inFlightTaskConvergenceApi.convergeKbCleanup(taskId, failMessage);
        };
    }
}