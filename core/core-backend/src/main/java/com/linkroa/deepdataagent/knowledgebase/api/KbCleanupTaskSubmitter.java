package com.linkroa.deepdataagent.knowledgebase.api;

/**
 * 整库清退任务提交契约（跨 BC 服务边界，依赖倒置端口）。
 * <p>提供方为 knowledgebase BC（本契约由知识库定义并发布，与 {@link IngestionTaskSubmitter} 同构），
 * 实现方为 rag BC（委托其<b>删除清退虚拟线程执行器</b>：JDK 21 thread-per-task + 并发配额信号量 +
 * 在飞去重登记，原内存有界队列与单消费者机器已退役）。知识库 BC 仅依赖本接口，
 * 不产生 knowledgebase → rag 的反向编译依赖，跨 BC 依赖方向恒为 rag → knowledgebase 单向。</p>
 *
 * <p>挂点：知识库删除受理的三条源态路径
 * （ACTIVE → DELETING 首删、DELETE_FAILED → DELETING 重删、DELETING 崩溃遗留重触发）
 * 均须在<b>事务提交后</b>调用本契约投递整库清退任务；投递失败不回滚受理、不落任何失败态——
 * 知识库停留 DELETING，由用户重删或本实例启动恢复按状态一致性校验收敛为 {@code DELETE_FAILED} 处置。</p>
 *
 * <p>在飞判定形态：受理侧需要区分「清退已在飞 → 幂等回删除中」
 * 与「崩溃遗留（线程已失）→ 重新触发」。该判定<b>内聚在本方法的返回值</b>而非另设查询方法——
 * 「先查在飞、再提交」两步之间存在竞态窗口（查询与提交之间在飞登记可能刚被释放或刚被占位），
 * 单方法返回在飞判定结果可让受理矩阵一次调用即得到确定结论，端口面亦不额外扩张。</p>
 */
public interface KbCleanupTaskSubmitter {

    /**
     * 提交一个知识库的整库清退任务到删除清退虚拟线程执行器，并回读本库是否已在飞。
     * <p>幂等：同一知识库重复提交（如用户重删并发触达）不产生第二个清退任务——
     * 该库任务已在飞时由执行器登记闸门挡下并返回 {@code false}，本方法不抛异常。
     * 服务停机中提交将抛出 {@code IllegalStateException}，调用方按「仅 ERROR 留痕、库停留 DELETING」
     * 的受理语义处理（由本实例启动恢复一致性校验收敛为 {@code DELETE_FAILED} 兜底，不再自动重触发）。</p>
     *
     * @param kbId 待清退知识库主键，必填
     * @return {@code true} 表示本次调用新建并提交了清退任务（首删受理或崩溃遗留重触发）；
     *         {@code false} 表示该库清退任务已在飞，本次为幂等跳过（未产生新任务）
     * @throws IllegalStateException    服务正在停机，执行器拒收新任务
     * @throws IllegalArgumentException kbId 为空
     */
    boolean submit(Long kbId);
}
