package com.linkroa.deepdataagent.knowledgebase.api;

/**
 * 摄入解析任务提交契约（跨 BC 服务边界，依赖倒置端口）。
 * <p>提供方为 knowledgebase BC（本契约由知识库定义并发布），实现方为 rag BC
 * （委托其内存摄入任务队列），依赖方向与 {@link ChunkBatchWriter} 同构（rag → knowledgebase 单向），
 * 不新增 knowledgebase → rag 的反向编译依赖。</p>
 *
 * <p>挂点：文档上传落库成功（状态 PENDING = 已入队）与用户重新解析
 * （FAILED 等 → PENDING）两条路径，均须在<b>事务提交后</b>调用本契约提交解析任务，
 * 使解析在有空闲工作方时立即开始，全程无周期扫描。提交失败或异常时由调用方将文档回滚为
 * {@code FAILED} 并写入可辨识的提交失败原因，闭合「PENDING 但队列无对应任务」的孤儿窗口。</p>
 */
public interface IngestionTaskSubmitter {

    /**
     * 提交一篇文档的解析任务到内存摄入队列。
     * <p>幂等：同一文档重复提交（如用户双击重试）不会产生重复任务。返回值语义为
     * <b>「该文档后续确实会被处理」</b>：要么本次调用真实入队，要么队列已持有该文档——
     * 命中去重时投递已登记为待补投（对应任务收尾清理去重表项后必然就地补投一份新任务，
     * 覆盖「在飞任务已写完终态、去重标记尚未清理」的收尾窗口），同样视为成功。
     * 签名与返回类型保持不变，仅「返回成功」的含义收紧（spec ingestion-resubmission-integrity）。
     * 服务停机中提交将抛出 {@code IllegalStateException}，调用方按失败回滚语义处理。</p>
     *
     * @param documentId 目标文档主键，必填
     * @return {@code true} 表示已入队或已登记待补投（该文档必将被处理）；{@code false} 表示入队失败
     * @throws IllegalStateException    服务正在停机，队列拒收新任务
     * @throws IllegalArgumentException documentId 为空
     */
    boolean submit(Long documentId);
}
