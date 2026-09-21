package com.linkroa.deepdataagent.knowledgebase.api;

import java.util.Set;

/**
 * 在飞任务注册表契约（跨 BC 服务边界，依赖倒置端口）。
 * <p>提供方为 knowledgebase BC（上传 / 重新解析 / 删除受理路径需要留下在飞凭据），
 * 实现方为 rag BC（缓存承载的在飞任务注册表）。与 {@link IngestionTaskSubmitter} /
 * {@link DocumentCleanupTaskSubmitter} 同构，不产生 knowledgebase → rag 的反向编译依赖。</p>
 *
 * <p>语义边界：注册表只承载「本实例还有哪些异步任务尚未完成」这一归属凭据，
 * <strong>不承载业务状态</strong>（状态权威恒在数据库），<strong>不提供跨实例查询</strong>，
 * 也不得用作任务互斥（跨实例互斥仍由数据库状态条件更新承担）。三种任务类型的成员集合
 * 语义统一为「未完成」，同时覆盖排队中与执行中两种情形。</p>
 *
 * <p>键结构与成员形态由实现方决定（当前实现为「实例标识 : 业务域 : 任务类型」三段式键 + 任务
 * ID 集合），契约层不暴露键形，调用方只按 {@link InFlightTaskType} 操作。</p>
 *
 * @author DeepDataAgent
 */
public interface InFlightTaskRegistry {

    /**
     * 登记一个在飞任务（幂等：同一任务重复登记不产生重复成员）。
     * <p>调用时机 MUST 由调用方保证在「受理事务提交之后、任务投递之前」——
     * 登记先于投递，使投递失败（含停机窗口拒收）也留有残留凭据；且 MUST NOT 位于数据库事务内
     * （事务内禁止远程调用）。</p>
     *
     * @param taskType 任务类型，必填
     * @param taskId   任务主体主键（文档ID 或 知识库ID），必填
     * @throws IllegalArgumentException 任务类型或任务ID为空
     * @throws RuntimeException         注册表不可用（写入失败）；调用方 SHALL 走各自链的既有失败兜底
     *                                  （解析置 FAILED / 清退置 DELETE_FAILED），MUST NOT 静默放行
     */
    void register(InFlightTaskType taskType, Long taskId);

    /**
     * 移除一个在飞任务成员（任务已收敛到终态时调用）。
     * <p>调用前提：该残留已被确认为<strong>已收敛</strong>——状态已成功写入终态、或数据行已不存在、
     * 或本就无需置态。状态收敛写入失败时 MUST NOT 调用本方法，否则会产生「状态停在中间态而
     * 注册表无迹可寻」的永久不可见悬挂。</p>
     * <p>集合清空后键由缓存自动消失，无需额外清理；对不存在的键或成员执行移除为无害空操作。</p>
     *
     * @param taskType 任务类型，必填
     * @param taskId   任务主体主键，必填
     * @throws IllegalArgumentException 任务类型或任务ID为空
     */
    void unregister(InFlightTaskType taskType, Long taskId);

    /**
     * 读取本实例某类任务的全部未完成任务主键（直接读取，无需扫描）。
     * <p>读取范围<strong>严格限定在本实例标识对应的键</strong>，MUST NOT 读取或改动其他实例的键。</p>
     *
     * @param taskType 任务类型，必填
     * @return 未完成任务主键集合（不可变集合）；无成员或注册表不可用时为空集合
     * @throws IllegalArgumentException 任务类型为空
     */
    Set<Long> findInFlightTaskIds(InFlightTaskType taskType);

    /**
     * 清空本实例的全部在飞任务键（停机流程在收到终止信号的第一时间调用）。
     * <p>目的：使「新进程读到非空键」等价于「真崩溃（来不及清空）」，从而消除滚动发布期间
     * 新旧进程并存造成的误判。清空键 MUST NOT 被理解为放弃任务——宽限期内自然结束的任务仍正常
     * 置成功态，宽限期满仍未结束的与队列中排队的任务由本进程显式置失败态。</p>
     *
     * @throws RuntimeException 注册表不可用（清空失败）；停机流程按尽力而为处理，
     *                          MUST NOT 阻断停机链的后续等待与收敛
     */
    void clearInstanceRegistry();
}