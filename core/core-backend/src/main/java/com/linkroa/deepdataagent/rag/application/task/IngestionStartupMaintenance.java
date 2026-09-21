package com.linkroa.deepdataagent.rag.application.task;

import com.linkroa.deepdataagent.knowledgebase.api.InFlightTaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 摄入启动恢复（实例限定）。
 * <p>崩溃重启后内存队列已丢失（不做队列重建），DB 中冻结的 {@code PENDING}（已入队未开始）
 * 与 {@code PROCESSING}（在飞）文档无对应内存任务。本组件在应用就绪后经
 * {@link InFlightTaskConverger#convergeAllOfType(InFlightTaskType, String)} 按<strong>本实例标识</strong>
 * 直接读取在飞注册表的 {@link InFlightTaskType#DOC_INGESTION} 键，逐个执行
 * 「数据库权威状态一致性校验 → 条件置 {@code FAILED}」并写入「服务重启中断」原因：
 * 读取只命中本实例的键，MUST NOT 触碰其他实例的在飞任务；<b>不重建队列、不自动重跑</b>，
 * 失败任务的后续处置仅由用户手动重新解析触发。</p>
 *
 * <p>历史方案（全表收敛 + 可关闭开关）已移除：原实现经
 * {@code ChunkBatchWriter#failNonTerminal(String)} 把全表 {@code PENDING/PROCESSING} 一次性置
 * {@code FAILED}，并由 {@code app.rag.ingestion.startup.fail-non-terminal} 开关控制启停。
 * 全表收敛在多实例下会误伤其他实例正在执行的在飞任务，故改为实例限定恢复后开关失去意义，
 * 该配置项已从 {@code application.yaml} 下线、本组件不再读取。</p>
 *
 * <p>时序与幂等：{@code @Order(100)} 先于 {@link IngestionTaskQueue#startConsumers()}
 * （{@code @Order(200)}）执行，保证「先恢复、后消费」；即便与就绪后新上传存在竞态，
 * 出队领取的条件更新（仅 PENDING 命中）天然挡住对已 FAILED 行的复活。恢复天然幂等，
 * 无残留（集合为空、影响 0 篇）时静默。恢复异常仅 ERROR 留痕，MUST NOT 阻断应用启动。</p>
 *
 * @author DeepDataAgent
 */
@Component
public class IngestionStartupMaintenance {

    private static final Logger log = LoggerFactory.getLogger(IngestionStartupMaintenance.class);

    /** 启动恢复回写的失败原因：可辨识前缀 [STARTUP]，提示用户手动重新解析。 */
    private static final String STARTUP_ERROR_MESSAGE = "[STARTUP] 服务重启中断，请重新解析";

    /** 在飞任务收敛器（按本实例标识读取残留、一致性校验后条件置态）。 */
    private final InFlightTaskConverger inFlightTaskConverger;

    /**
     * 构造摄入启动恢复器。
     *
     * @param inFlightTaskConverger 在飞任务收敛器（实例限定的残留读取与条件置态）
     */
    public IngestionStartupMaintenance(InFlightTaskConverger inFlightTaskConverger) {
        this.inFlightTaskConverger = inFlightTaskConverger;
    }

    /**
     * 应用就绪钩子：收敛本实例崩溃残留的在飞文档为 FAILED；异常不改动剩余状态、不阻断启动。
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    public void convergeCrashResidueOnReady() {
        try {
            int converged = inFlightTaskConverger.convergeAllOfType(
                    InFlightTaskType.DOC_INGESTION, STARTUP_ERROR_MESSAGE);
            if (converged > 0) {
                log.info("[摄入启动恢复] 完成，本实例崩溃残留的 {} 篇在飞文档已置 FAILED（需用户手动重新解析）",
                        converged);
            } else {
                log.info("[摄入启动恢复] 完成，无本实例崩溃残留（在飞注册表无未完成文档）");
            }
        } catch (Exception e) {
            log.error("[摄入启动恢复] 失败，不阻断应用启动（残留凭据保留在注册表，"
                    + "可由下次启动重试或用户重新解析处置）", e);
        }
    }
}