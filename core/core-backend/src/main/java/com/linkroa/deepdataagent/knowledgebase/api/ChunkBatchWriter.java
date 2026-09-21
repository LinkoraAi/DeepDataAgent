package com.linkroa.deepdataagent.knowledgebase.api;

import com.linkroa.deepdataagent.knowledgebase.application.contract.ChunkDraft;

import java.util.List;
import java.util.Map;

/**
 * 切片批量回写契约（跨 BC 服务边界，未来 Feign 落点）。
 * <p>提供方为 knowledgebase BC（切片及其 1:1 派生表示归本 BC 所有），消费方为 rag BC 的摄入管线。
 * 语义为「整篇替换」：一次调用即把某文档的全部切片收敛为入参给定的集合，等价于
 * 「删除该文档旧切片及其向量/全文表示 + 批量写入新切片及其向量/全文表示」，
 * 并在同一事务内把 {@code document.chunkCount} 原子更新为入参条数。</p>
 * <p><b>整篇替换不区分分块来源（显式语义）</b>：删除的是该文档<b>全部</b>旧分块，
 * <b>含人工新增的分块</b>（来源标识 {@code MANUAL} 不豁免）；重建分块一律打「解析产生」标、
 * 序号按解析结果连续分配。消费方发起重新解析前 MUST 确保用户已知悉人工分块将被清除
 * （前端二次确认为发布前置条件）。</p>
 * <p>写入的分块来源标识恒为「解析产生」（{@code source_type = PARSED}），
 * 与人工新增入口（{@code MANUAL}）互斥；该标识是图谱账本收敛触发推导与人工删除准入的
 * 唯一数据依据。</p>
 * <p><b>整篇替换不写文档状态</b>：切片写入成功只表示切片已就绪，其后的实体关系抽取与图合并
 * 仍属摄入管线未完成部分，成功终态由 {@link #markProcessed(Long)} 在管线收尾统一写入。</p>
 *
 * <p>失败语义：整批原子。任一条目非法（文档不存在、集合为空、超出单批上限、序号重复）都会
 * 导致整批回滚，既有切片保持不变，由消费方决定重试。清空某文档的切片不走本契约，
 * 而走文档删除/知识库删除的清退路径。</p>
 *
 * <p>状态语义（内存队列调度 + 数据库条件更新作为跨实例互斥手段）：
 * {@code PENDING = 已入队待解析}、{@code PROCESSING = 摄入管线在飞（从出队领取一直到图谱构建结束）}。
 * 摄入调度不再依赖定时扫表与处理租约，本契约只提供队列模型所需的最小状态迁移面：
 * {@link #markProcessing(Long) 出队领取}（PENDING → PROCESSING）、
 * {@link #markProcessed} 成功终态（PROCESSING → PROCESSED，仅由管线收尾写入）、
 * {@link #markFailed} 失败回写（仅 PROCESSING 生效）、
 * {@link #failNonTerminal(String) 非终态批量收敛}（PENDING / PROCESSING → FAILED；
 * 保留能力，启动恢复已改为按本实例在飞注册表键逐条一致性校验收敛，本方法当前无生产调用点）。
 * {@link #replaceForDocument} 要求文档处于 PROCESSING，脱离处理中状态的迟到回写在数据库层被拒。</p>
 */
public interface ChunkBatchWriter {

    /**
     * 整篇替换某文档的切片集合（摄入管线的切片落库步）。
     * <p>成功写入后只更新 {@code document.chunkCount} 并清除残留的 {@code errorMessage}
     * （幂等兜底：上一轮 FAILED 的失败原因不跨本次落库存活），
     * <b>不改变文档状态</b>——成功终态由 {@link #markProcessed(Long)} 在管线收尾写入。</p>
     * <p>前置条件：文档状态必须为 {@code PROCESSING}（即调用方已经出队领取成功），
     * 否则抛冲突业务异常——文档已被删除链置 {@code DELETING}、被本实例启动恢复置 {@code FAILED}
     * 或已重新入队等场景下的迟到回写即由此收口。</p>
     *
     * @param documentId 目标文档主键，必填；文档不存在时抛业务异常
     * @param drafts     切片草稿列表，按 {@code sequence} 升序，不得为空且不得超出单批上限；
     *                   多模态草稿的图片对象引用（meta 中 {@code mediaObjectKey}，桶概念已退役）
     *                   由提供方提取落 {@code chunk.s3_file} 一等列
     * @param operatorId 操作人ID，可为空（空则审计字段由系统兜底值填充）
     * @return 本批切片的「序号 → 落库主键」映射（与入参草稿一一对应，恒非 null）；
     *         消费方（RAG 摄入管线）据此直取主键完成溯源回填，无需再次回查数据库
     */
    Map<Integer, Long> replaceForDocument(Long documentId, List<ChunkDraft> drafts, Long operatorId);

    /**
     * 出队领取：原子条件更新 {@code PENDING → PROCESSING} 并清除 {@code errorMessage}。
     * <p>内存队列消费方取出任务时调用，命中即确认独占开始解析；未命中（文档已被删除链置
     * {@code DELETING}、已被本实例启动恢复置 {@code FAILED} 或记录不存在）返回 {@code false}，
     * 调用方 SHALL 静默丢弃该任务且不产生任何回写。跨实例的任务互斥由本条条件更新自身承担——
     * 单条 CAS 在并发领取下仅有一方命中，未命中方静默丢弃，故不需要领取者标识、租约与有效期概念
     * （本方案不做任务在实例间的自动转移）。</p>
     *
     * @param documentId 目标文档主键，必填
     * @return {@code true} 表示领取成功（文档已进入 PROCESSING）；{@code false} 表示条件更新未命中
     */
    boolean markProcessing(Long documentId);

    /**
     * 写入摄入成功终态：条件更新 {@code PROCESSING → PROCESSED} 并清除 {@code errorMessage}。
     * <p>由 RAG 上下文摄入管线在<b>收尾时</b>调用，此前切片落库、实体与关系抽取、图合并
     * （图谱与向量入库）均须已成功结束——切片落库本身不写状态，本方法是成功终态的唯一写入点。</p>
     * <p>条件前置即并发收口：文档已被删除链置 {@code DELETING / DELETE_FAILED}、已被启动恢复置
     * {@code FAILED}、已被用户重新解析置 {@code PENDING} 或行已不存在时零行命中，调用方 SHALL
     * 静默放弃本次写入（状态归持有方，不回退、不覆盖）。</p>
     *
     * @param documentId 目标文档主键，必填
     * @return {@code true} 表示命中并已置 {@code PROCESSED}；{@code false} 表示条件更新未命中
     *         （状态已被删除链 / 启动恢复 / 用户操作推进，或文档行已不存在）
     */
    boolean markProcessed(Long documentId);

    /**
     * 标记文档摄入失败并回写失败原因。
     * <p>仅当文档当前状态为 {@code PROCESSING} 时置为 {@code FAILED} 并写入
     * {@code errorMessage}，避免覆盖用户重新解析（PENDING）等更新的状态。</p>
     *
     * @param documentId   目标文档主键，必填
     * @param errorMessage 失败原因描述，必填非空白；空白时抛业务异常
     */
    void markFailed(Long documentId, String errorMessage);

    /**
     * 非终态批量收敛：把全部未删除且状态为非终态（{@code PENDING / PROCESSING}）的文档一次性置为
     * {@code FAILED} 并写入失败原因。
     * <p><b>保留能力，当前无生产调用点</b>：启动恢复链路已改按本实例在飞注册表键逐条读取残留，
     * 经数据库权威状态一致性校验后条件置态，本方法不再被启动链路使用，也不再自称「启动清理专用」。
     * 原崩溃残留收敛语义保留：进程崩溃重启后内存队列已丢失，DB 中冻结的「已入队未开始」与「在飞」
     * 文档均无对应任务，统一以本方法收敛为 FAILED，不重建队列、不自动重跑，由用户手动重新解析。
     * 批量单条 UPDATE，天然幂等（无命中影响 0 行）。</p>
     *
     * @param errorMessage 失败原因描述（写入 {@code document.errorMessage}），空白时抛业务异常
     * @return 实际置为 FAILED 的文档数（0 表示无残留）
     */
    int failNonTerminal(String errorMessage);
}
