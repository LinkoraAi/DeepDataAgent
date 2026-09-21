package com.linkroa.deepdataagent.knowledgebase.api;

/**
 * 文档清退任务提交契约（跨 BC 服务边界，依赖倒置端口）。
 * <p>提供方为 knowledgebase BC（本契约由知识库定义并发布，与 {@link KbCleanupTaskSubmitter} 同构，
 * 端口方向恒为 KB → api → rag），实现方为 rag BC（委托其<b>删除清退虚拟线程执行器</b>：
 * JDK 21 thread-per-task + 并发配额信号量 + 在飞去重登记）。知识库 BC 仅依赖本接口，
 * 不产生 knowledgebase → rag 的反向编译依赖。</p>
 *
 * <p>挂点：文档删除受理（CAS 推入 DELETING）的短事务<b>提交后</b>由删除链调用本契约投递清退任务体，
 * 请求线程 MUST NOT 执行任何清退步骤。任务体自带全量 catch（fire-and-forget 契约），
 * 其失败留痕与状态推进由任务体自身经仓储完成，不经本契约回传。</p>
 *
 * <p>与整库清退提交契约（{@link KbCleanupTaskSubmitter}）的差异：任务体归属 knowledgebase BC
 * （文档链逻辑在 KB 侧），故以 {@code Runnable} 形态经端口穿越到 rag 侧执行器上运行；
 * 在飞去重键（{@code doc:{documentId}}）由实现方构造，同一文档在飞期间的重复投递被去重挡下。</p>
 */
public interface DocumentCleanupTaskSubmitter {

    /**
     * 向删除清退虚拟线程执行器提交一个文档的清退任务体。
     * <p>幂等：同一文档的清退任务已在飞（正常删除在途、或用户重删并发触达）时
     * 不产生第二个任务，直接返回 {@code false}，调用方按「删除中」幂等受理。</p>
     *
     * @param documentId  待清退文档主键，必填
     * @param cleanupTask 清退任务体（调用方保证自带全量 catch 与失败留痕），必填
     * @return {@code true} 表示已受理并提交异步执行（含崩溃遗留 DELETING 行的重触发）；
     *         {@code false} 表示该文档清退任务已在飞，本次提交被去重（幂等跳过）
     * @throws IllegalStateException    服务正在停机，执行器拒收新任务；调用方按
     *                                  「文档停留 DELETING、ERROR 留痕、由本实例启动恢复收敛」处置
     * @throws IllegalArgumentException documentId 为空或任务体为空
     */
    boolean submit(Long documentId, Runnable cleanupTask);
}
