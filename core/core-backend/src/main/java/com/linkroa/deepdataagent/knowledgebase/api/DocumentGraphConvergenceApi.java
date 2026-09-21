package com.linkroa.deepdataagent.knowledgebase.api;

import java.util.Collection;

/**
 * 图谱贡献收敛契约（跨 BC 服务边界，依赖倒置端口；OpenSpec rebuild-kg-on-document-delete / 组 6）。
 * <p>提供方为 rag BC（图谱向量账本与抽取缓存归本 BC 所有，实现位于 rag.infrastructure），
 * 消费方为 knowledgebase BC 的删除链与摄入换代链（进程内消费，不提供 REST 端点）。</p>
 *
 * <p><b>两段式调用契约（design D3，事务边界归属本契约声明）</b>：重建所需的重放、描述摘要与
 * 向量化是远程调用，事务规范 1.1 严禁其进入任何数据库事务，故收敛拆为三步、由调用方掌握时机：</p>
 * <ol>
 *   <li>{@link #prepare}——<b>MUST 在事务外调用</b>：以「条目账本 ∩ 被删分块」推导受影响条目、
 *       重放抽取缓存并完成摘要与向量化（全部仅内存），产出不透明计划句柄；零写入、零事务；</li>
 *   <li>{@link #apply}——<b>MUST 在调用方删除事务内调用</b>（消费方未开事务时本步自持单事务）：
 *       句柄中的重建结果写回（含剔空物理删）、账本收缩、图行展示来源列收缩、归属清理与按引用
 *       计数的缓存回收，全部纯 DB 操作、与调用方后续的分块行删除同事务原子生效；返回聚合计数
 *       结果（{@link DocumentGraphConvergenceResult}）；</li>
 *   <li>{@link #reconcilePendingVectorContent}——<b>MUST 在事务提交后、事务外调用</b>：对 apply
 *       结果中的「待向量收口」清单做最终一致化（内含向量化远程调用）；清单为空时零操作。
 *       收口失败不影响已提交的删除事实。</li>
 * </ol>
 *
 * <p><b>顺序不变量与幂等重入</b>：分块行 MUST 晚于重建读取抽取缓存才被物理删除——本契约三步
 * 内不删分块行，分块行删除由调用方（删除原语）排在 apply 之后、同一事务内完成。prepare 与 apply
 * 之间崩溃零写入残留；apply 事务失败整体回滚、分块行仍在——重试从「账本 ∩ 被删分块」重新推导
 * 同一批受影响条目（MUST NOT 依赖句柄或任何日志、待办记录），各步重复执行第二次起零影响。</p>
 *
 * <p><b>权重语义（RQ-22 已平账）</b>：条目权重按存活来源<b>全额重算</b>（回扣已实现，design D6），
 * 抽取缓存不可用时按降级保留原值并计入降级报告；MUST NOT 再把「不回扣」当作与上游 LightRAG
 * 一致的语义。</p>
 *
 * <p><b>扫描面按知识库收窄</b>：清退/收缩/回收语句带 {@code kb_id} 等值条件。被清退分块 MUST
 * 全部属于入参 {@code kbId}（调用方保证批次不跨库、按库分组后逐库调用）。缓存回收仅限同库的
 * {@code EXTRACT} 分类，且只回收「从本批归属记录追溯到且引用已归零」的缓存行——MUST NOT 因
 * 「查不到归属」删除任何历史缓存行。</p>
 *
 * @author DeepDataAgent
 */
public interface DocumentGraphConvergenceApi {

    /**
     * 事务外准备：推导受影响条目并完成重建计算（重放 + 摘要 + 向量化，全部仅内存、零写入）。
     * <p>失败语义：计算中的远程失败照实上抛（运行失败，调用方此时 MUST NOT 已开启任何事务，
     * 重试从本步重新开始即可）；库级重建配置缺失或非法（摘要/向量模型引用未配置）时不抛异常——
     * 产出「跳过重建」的句柄并 WARN 留痕，apply 仍执行账本收缩与缓存回收（删除链不因重建
     * 能力缺失而阻断）。</p>
     *
     * @param kbId              被清退分块所属知识库主键，必填；被清退分块须全部属于该知识库
     * @param deletedDocumentId 本批被删分块所属文档ID（审计锚点；整库清退等跨文档批次取批次内
     *                          最小文档ID，仅用于日志定位、不参与任何判定，可为 null——按
     *                          「重建能力缺失」同款降级处置）
     * @param removedChunkIds   被清退的切片ID集合，可为空（空集合产出零句柄，apply 零影响）
     * @param operator          操作人标识，可为空（重建写回的审计字段透传）
     * @return 不透明计划句柄（MUST 交给 {@link #apply} 消费，不解析、不跨批次复用）
     */
    DocumentGraphConvergencePlan prepare(Long kbId, Long deletedDocumentId,
                                         Collection<Long> removedChunkIds, String operator);

    /**
     * 事务内应用：重建结果写回 + 账本收缩 + 展示列收缩 + 归属清理与按引用计数的缓存回收。
     * <p>本方法自持一个编程式事务（传播 REQUIRED：调用方已有事务时并入同一事务、不新起），
     * 事务体内全部为纯 DB 操作、零远程调用；任一步失败整体回滚并上抛（由调用方按删除链零重试
     * 语义处置，重试从 {@link #prepare} 重新推导）。重复执行幂等：账本已收缩、条目已消失、
     * 归属已清理，第二次起全部语句零影响。</p>
     *
     * @param plan {@link #prepare} 产出的句柄；非本契约实现产出的句柄将抛非法参数异常
     * @return 聚合计数结果（删除/重建/降级/回收行数与待收口清单）
     * @throws IllegalArgumentException 句柄为空或类型非法
     */
    DocumentGraphConvergenceResult apply(DocumentGraphConvergencePlan plan);

    /**
     * 事务提交后收口：对 {@code apply} 结果中的待向量收口清单做「内容与最终描述一致」修复。
     * <p>MUST 在 apply 所在事务<b>提交后、事务外</b>调用（内含向量化远程调用，事务规范 1.1）。
     * 收口原语自身降级不抛（仅 WARN），MUST NOT 影响已提交的删除事实；清单为空时零操作。</p>
     *
     * @param plan   与 {@code result} 配对的原句柄（提供重建上下文）
     * @param result {@link #apply} 的返回结果（提供待收口清单）
     */
    void reconcilePendingVectorContent(DocumentGraphConvergencePlan plan,
                                       DocumentGraphConvergenceResult result);
}
