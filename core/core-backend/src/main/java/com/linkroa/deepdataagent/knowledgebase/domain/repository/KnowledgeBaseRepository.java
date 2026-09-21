package com.linkroa.deepdataagent.knowledgebase.domain.repository;

import com.linkroa.deepdataagent.knowledgebase.domain.model.KnowledgeBase;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.KnowledgeBaseSortField;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.LifecycleStatus;

import java.util.List;
import java.util.Optional;

/**
 * 知识库仓储接口。
 */
public interface KnowledgeBaseRepository {

    KnowledgeBase save(KnowledgeBase kb);

    KnowledgeBase update(KnowledgeBase kb);

    /**
     * 更新知识库并显式指定操作人（用于跨 BC 契约透传审计信息）。
     *
     * @param kb       待更新的知识库聚合，不得为空
     * @param updatedBy 操作人标识，可为空；为空时退化为 {@link #update(KnowledgeBase)}，由审计填充器落默认值
     * @return 更新后的知识库聚合
     */
    KnowledgeBase update(KnowledgeBase kb, String updatedBy);

    Optional<KnowledgeBase> findById(Long id);

    Optional<KnowledgeBase> findByIdForUpdate(Long id);

    Optional<KnowledgeBase> findByName(String name);

    /**
     * 条件分页查询（排序白名单字段 + 方向，恒以 ID 倒序兜底保证分页稳定）。
     *
     * @param keyword   搜索关键字，可为空
     * @param status    生命周期状态，可为空
     * @param sortField 排序字段（name/createdAt/updatedAt），为空时按创建时间
     * @param ascending 是否升序
     * @param page      页码，从 1 开始
     * @param size      每页条数
     * @return 知识库列表
     */
    List<KnowledgeBase> findByCondition(String keyword, LifecycleStatus status,
                                        KnowledgeBaseSortField sortField, boolean ascending, int page, int size);

    long countByCondition(String keyword, LifecycleStatus status);

    void deleteById(Long id);

    /**
     * 统计各状态的库数量。
     */
    long countByStatus(LifecycleStatus status);

    /**
     * 按生命周期状态列举全部知识库 ID（id 升序，读侧只读投影，保留能力）。
     * <p>已收口库行物理不存在，天然不返回；启动恢复链路已改为按本实例在飞注册表键读取残留并一致性收敛，
     * 本方法当前无生产调用点（原用途为清退启动恢复一次性取全量）。行数天然有界，无需 limit。</p>
     *
     * @param status 生命周期状态（如 DELETING），必填；为空时返回空列表
     * @return 命中的知识库 ID 列表（升序）；无命中返回空列表
     */
    List<Long> findIdsByLifecycle(LifecycleStatus status);

    /**
     * 生命周期状态 CAS 迁移：单条 UPDATE 条件更新 {@code from → to}。
     * <p>不做先查后写：{@code WHERE id = ? AND lifecycle_status = ?} 单语句原子判定，
     * 并发重复调用（如用户重删与在飞状态收敛并发）只可能有一方命中，未命中方返回
     * {@code false} 由调用方幂等空转，杜绝读-改-写竞态窗口。命中时同步刷新 {@code updated_at}；
     * 已收口库行物理不存在，天然不可达。</p>
     *
     * @param kbId          目标知识库主键，必填
     * @param fromLifecycle 迁移起点状态（当前持有值），必填
     * @param toLifecycle   迁移目标状态，必填
     * @return {@code true} 表示条件更新命中（状态已迁移）；{@code false} 表示未命中
     *         （状态不符或记录不存在）
     */
    boolean transitLifecycle(Long kbId, LifecycleStatus fromLifecycle, LifecycleStatus toLifecycle);

    /**
     * 生命周期状态 CAS 迁移并<b>同语句清除</b>失败留痕（重删受理专用）。
     * <p>与 {@link #transitLifecycle} 同为单条条件 UPDATE、不做先查后写；差别在于本方法以
     * {@code error_message = NULL} 一并落库——DELETE_FAILED → DELETING 的重删必须清除上一轮失败留痕，
     * 否则 {@code updateById} 的空字段跳过策略（MyBatis-Plus 缺省 NOT_NULL）无法把该列写回空值。
     * 非源态零行命中返回 {@code false}，由调用方按并发未命中处理。</p>
     *
     * @param kbId          目标知识库主键，必填
     * @param fromLifecycle 迁移起点状态（当前持有值，重删场景为 DELETE_FAILED），必填
     * @param toLifecycle   迁移目标状态（重删场景为 DELETING），必填
     * @return {@code true} 表示条件更新命中（状态已迁移且留痕已清）；{@code false} 表示未命中
     *         （状态不符或记录不存在）
     */
    boolean transitLifecycleClearingFailure(Long kbId, LifecycleStatus fromLifecycle, LifecycleStatus toLifecycle);

    /**
     * 执行删除收口：单条条件物理 DELETE {@code id = ? AND lifecycle_status IN (DELETING, DELETE_FAILED)}
     * 命中的行（清退完成后的生命周期收口）。
     * <p>彻底物理删体系（无 is_deleted 列、无 @TableLogic）：MyBatis-Plus delete(wrapper) 即
     * {@code DELETE FROM}，行消失即「已删除」的唯一表达（删除完成即释放 uk_kb_name 名称唯一性，
     * 删库后同名立即可用）。0 行命中（状态非两可收口态、行已不存在）按「已收口」幂等空转处理。</p>
     *
     * @param kbId 目标知识库主键，必填
     * @return {@code true} 表示命中并完成收口；{@code false} 表示未命中（幂等空转或入参非法）
     */
    boolean executeDelete(Long kbId);

    /**
     * 清退失败留痕 CAS：单条 UPDATE 完成 {@code DELETING → DELETE_FAILED} 并写入 {@code error_message}
     * （零重试语义的失败落点）。
     * <p>与 {@link #transitLifecycle} 同为单语句原子判定、不做先查后写；非 DELETING 源态
     * （已收口行缺失、已是 DELETE_FAILED 或 ACTIVE）零行命中返回 {@code false} 由调用方幂等空转，
     * MUST NOT 覆盖并发链已写入的状态。命中时同步刷新 {@code updated_at}。</p>
     *
     * @param kbId         目标知识库主键，必填
     * @param errorMessage 失败步骤与原因摘要留痕（调用方负责截断），可为空白
     * @return {@code true} 表示 CAS 命中（已置 DELETE_FAILED 并留痕）；{@code false} 表示未命中
     *         （非源态、记录不存在或入参非法）
     */
    boolean markFailed(Long kbId, String errorMessage);
}
