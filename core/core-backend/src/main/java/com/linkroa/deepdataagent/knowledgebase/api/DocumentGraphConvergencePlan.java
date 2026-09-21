package com.linkroa.deepdataagent.knowledgebase.api;

/**
 * 图谱贡献收敛的不透明计划句柄（跨 BC 契约类型，OpenSpec rebuild-kg-on-document-delete / 组 6）。
 *
 * <p><b>为什么是句柄而非值对象</b>：删除链已重排为「事务外重建计算 → 事务内写回与收敛」两段式
 * （design D3）。事务外段要完成抽取缓存重放、描述摘要与向量化（远程调用，事务规范 1.1 严禁入
 * 事务），其产出是一个只对本库本轮删除批次有意义的内存计划；该计划的类型族（重建上下文 /
 * 重建计划）归 rag BC 所有，MUST NOT 出现在本契约签名上（knowledgebase BC 不得依赖 rag 类型）。
 * 故以本接口承载「已算好、待落库」的中间态：消费方（删除链）只负责在正确时机把它交给
 * {@link DocumentGraphConvergenceApi#apply}，不解析、不修改、不跨批次复用。</p>
 *
 * <p><b>生命周期契约</b>：句柄由 {@link DocumentGraphConvergenceApi#prepare} 在同一线程内产出、
 * 紧随其后的 {@link DocumentGraphConvergenceApi#apply} 一次性消费（幂等重入场景下 prepare 可
 * 重新执行以重新推导——句柄本身不是恢复凭据，可重入依据永远是「条目账本 ∩ 被删分块」）。
 * 实现方（rag.infrastructure）内部包装 rag 侧重建上下文与重建计划快照，对消费方保持不透明。</p>
 *
 * @author DeepDataAgent
 */
public interface DocumentGraphConvergencePlan {
}
