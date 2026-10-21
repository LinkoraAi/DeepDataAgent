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
 * <p><b>不做全库扫描</b>：多实例部署下任何实例都可能持有其他实例正在执行的任务，按全库非终态
 * 批量置失败会误杀其他实例；恢复范围严格限定为本实例登记的在飞成员。</p>
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
     * 应用就绪钩子：按本实例标识收敛摄入崩溃残留。
     * <p>只读取本实例在飞键，逐个做数据库权威状态一致性校验后条件置 {@code FAILED}；
     * 不重建队列、不自动重跑，失败文档的后续处置仅由用户手动重新解析触发。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    public void convergeCrashResidueOnReady() {
        try {
            int converged = inFlightTaskConverger.convergeAllOfType(
                    InFlightTaskType.DOC_INGESTION, STARTUP_ERROR_MESSAGE);
            log.info("[摄入启动恢复] 完成：本实例注册表收敛={} 篇（均置 FAILED，需手动重新解析）", converged);
        } catch (Exception e) {
            log.error("[摄入启动恢复] 本实例注册表残留收敛失败（残留由下次启动重试或用户重新解析处置）", e);
        }
    }
}