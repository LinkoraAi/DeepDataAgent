package com.linkroa.deepdataagent.rag.application.task;

import com.linkroa.deepdataagent.knowledgebase.api.InFlightTaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 删除清退启动恢复（实例限定收敛，不再自动重触发续跑）。
 * <p>清退任务只活在内存里的虚拟线程中：崩溃重启后线程与在飞登记一并消失，但跨进程注册表键
 * 仍留有未完成凭据。应用就绪阶段按<b>本实例标识</b>直接读取两个清退键集合
 * （文档清退 {@code DOC_CLEANUP} / 整库清退 {@code KB_CLEANUP}，经
 * {@link InFlightTaskConverger#convergeAllOfType(InFlightTaskType, String)}），逐个完成
 * 「状态一致性校验 → 条件置 {@code DELETE_FAILED} → 留痕 → 移除注册表成员」的收敛动作。</p>
 *
 * <p>能力退化换取多实例下不自伤：本阶段 MUST NOT 自动重触发续跑（不再全表扫描 {@code DELETING}
 * 后重新投递清退任务），残留处置权归用户重删——用户在列表看到失败态后手动重删即可。读取只命中
 * 本实例的键，MUST NOT 触碰其他实例的残留。</p>
 *
 * <p>态覆盖与兜底：{@code DELETE_FAILED} 恒不自动重试——零重试形态下失败处置权归用户。
 * 系统 MUST NOT 为此引入任何常驻定时任务。</p>
 *
 * <p>时序：{@code @Order(210)} 严格晚于摄入消费线程启动（{@code IngestionTaskQueue} 的
 * {@code @Order(200)}），保证恢复动作不抢先于摄入链路就位；因恢复已改为按本实例键读取，
 * 不再受「先清理后消费」约束。</p>
 *
 * <p>失败语义：单条收敛的内部异常由收敛器自行留痕并保留成员，本阶段整体异常亦仅 ERROR 留痕，
 * MUST NOT 阻断应用启动——残留继续停留原状态，由用户重删或下次启动恢复兜底。</p>
 *
 * @author DeepDataAgent
 */
@Component
public class CleanupStartupMaintenance {

    private static final Logger log = LoggerFactory.getLogger(CleanupStartupMaintenance.class);

    /** 文档清退启动恢复留痕（须以此前缀开头，与删除链既有留痕口径一致）。 */
    private static final String DOC_CLEANUP_RECOVERY_TRACE = "[DELETE-FAILED] step=startup_recovery";

    /** 知识库清退启动恢复留痕。 */
    private static final String KB_CLEANUP_RECOVERY_TRACE =
            "[KB-CLEANUP] step=STARTUP_RECOVERY: 服务重启中断，请重新执行删除";

    /** 在飞任务收敛器（启动恢复与停机收敛共用的收敛原语）。 */
    private final InFlightTaskConverger inFlightTaskConverger;

    /**
     * 构造删除清退启动恢复器。
     *
     * @param inFlightTaskConverger 在飞任务收敛器（按本实例键读取残留并按状态一致性校验收敛）
     */
    public CleanupStartupMaintenance(InFlightTaskConverger inFlightTaskConverger) {
        this.inFlightTaskConverger = inFlightTaskConverger;
    }

    /**
     * 应用就绪钩子：按本实例标识收敛两类清退残留（可置态者置 {@code DELETE_FAILED} 并留痕，不重触发续跑）。
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(210)
    public void retriggerDeletingKnowledgeBasesOnReady() {
        try {
            int convergedDocs =
                    inFlightTaskConverger.convergeAllOfType(InFlightTaskType.DOC_CLEANUP, DOC_CLEANUP_RECOVERY_TRACE);
            int convergedKbs =
                    inFlightTaskConverger.convergeAllOfType(InFlightTaskType.KB_CLEANUP, KB_CLEANUP_RECOVERY_TRACE);
            log.info("[删除清退启动恢复] 本实例残留收敛完成: 文档残留(置DELETE_FAILED)={}, "
                    + "知识库残留(置DELETE_FAILED)={}", convergedDocs, convergedKbs);
        } catch (Exception e) {
            log.error("[删除清退启动恢复] 本实例残留收敛失败，不阻断应用启动"
                    + "（残留停留原状态，可由用户重删或下次启动恢复兜底）", e);
        }
    }
}