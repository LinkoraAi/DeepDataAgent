package com.linkroa.deepdataagent.rag.application.task;

import com.linkroa.deepdataagent.knowledgebase.api.ChunkBatchWriter;
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
 * <p><b>数据库兜底（无条件、无开关）</b>：注册表收敛之后，本组件再经
 * {@link ChunkBatchWriter#failNonTerminal(String)} 把库内全部 {@code PENDING/PROCESSING} 文档
 * 一次性置 {@code FAILED}。这是「启动恢复不依赖注册表凭据」的权威兜底——注册表键可能因
 * 优雅停机清键、缓存数据丢失、进程被强杀而缺失，仅靠键收敛会留下永久悬挂的非终态文档；
 * 数据库扫描覆盖上述全部场景，与键收敛互不依赖（一路失败不影响另一路）。收敛为条件 UPDATE
 * （仅命中 {@code PENDING/PROCESSING}），对已终态、已删除、已被并发推进的行零影响。</p>
 * <p><b>多实例说明</b>：本兜底为无条件行为（按产品口径不设开关）。多实例共享同一数据库部署时，
 * 启动即会收敛其他实例仍在飞的 {@code PROCESSING} 文档——该场景须由部署方避免（本项目为单实例
 * 部署），MUST NOT 依赖注册表键的实例隔离来「豁免」本兜底。</p>
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

    /** 在飞任务收敛器（按本实例标识读取残留、一致性校验后条件置态；快速路径）。 */
    private final InFlightTaskConverger inFlightTaskConverger;

    /** 切片批写契约（提供非终态批量收敛原语，数据库兜底路径使用）。 */
    private final ChunkBatchWriter chunkBatchWriter;

    /**
     * 构造摄入启动恢复器。
     *
     * @param inFlightTaskConverger 在飞任务收敛器（实例限定的残留读取与条件置态）
     * @param chunkBatchWriter      切片批写契约（非终态批量收敛原语）
     */
    public IngestionStartupMaintenance(InFlightTaskConverger inFlightTaskConverger,
                                       ChunkBatchWriter chunkBatchWriter) {
        this.inFlightTaskConverger = inFlightTaskConverger;
        this.chunkBatchWriter = chunkBatchWriter;
    }

    /**
     * 应用就绪钩子：两路互不依赖地收敛崩溃残留，任一路异常均只留痕、不阻断启动。
     * <ol>
     *   <li><b>注册表快速路径</b>：按本实例在飞键逐条做数据库权威状态一致性校验后条件置
     *       {@code FAILED}（可顺带移除键成员）；</li>
     *   <li><b>数据库兜底</b>：无条件把库内全部 {@code PENDING/PROCESSING} 文档置
     *       {@code FAILED}（覆盖键丢失、缓存数据丢失、进程被强杀等一切凭据缺失场景）。</li>
     * </ol>
     * <p>两路顺序执行：先键收敛（精确、可带实例归属语义），后数据库扫尾（保证零悬挂）。
     * 不重建队列、不自动重跑，失败文档的后续处置仅由用户手动重新解析触发。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    public void convergeCrashResidueOnReady() {
        int convergedByRegistry = convergeRegistryResidue();
        int convergedBySweep = convergeAllNonTerminalDocuments();
        log.info("[摄入启动恢复] 完成：注册表收敛={} 篇，数据库非终态兜底收敛={} 篇（均置 FAILED，需手动重新解析）",
                convergedByRegistry, convergedBySweep);
    }

    /**
     * 注册表快速路径：按本实例键收敛崩溃残留。
     *
     * @return 实际置态篇数；异常时返回 0（异常仅留痕，不阻断后续数据库兜底）
     */
    private int convergeRegistryResidue() {
        try {
            return inFlightTaskConverger.convergeAllOfType(InFlightTaskType.DOC_INGESTION, STARTUP_ERROR_MESSAGE);
        } catch (Exception e) {
            log.error("[摄入启动恢复] 注册表残留收敛失败（继续执行数据库非终态兜底）", e);
            return 0;
        }
    }

    /**
     * 数据库兜底路径：无条件把全部 {@code PENDING/PROCESSING} 文档置 {@code FAILED}。
     *
     * @return 实际置态篇数；异常时返回 0（异常仅留痕，不阻断应用启动）
     */
    private int convergeAllNonTerminalDocuments() {
        try {
            return chunkBatchWriter.failNonTerminal(STARTUP_ERROR_MESSAGE);
        } catch (Exception e) {
            log.error("[摄入启动恢复] 数据库非终态兜底收敛失败，应用启动不阻断"
                    + "（残留由下次启动重试或用户重新解析处置）", e);
            return 0;
        }
    }
}