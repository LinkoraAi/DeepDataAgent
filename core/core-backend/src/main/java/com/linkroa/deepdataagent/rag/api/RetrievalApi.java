package com.linkroa.deepdataagent.rag.api;

import com.linkroa.deepdataagent.rag.application.contract.RetrievalQuery;
import com.linkroa.deepdataagent.rag.application.contract.RetrievalResult;

/**
 * RAG 检索服务契约（跨 BC 服务边界，未来 Feign 落点）。
 * <p>提供方为 rag BC（检索编排 Stage 0~5 归本 BC 所有），消费方为需要「知识库检索增强问答」
 * 能力面的上游 BC（如 runtime / agent）。与 {@code KnowledgeBaseApi}（知识库自有读侧检索通道）
 * 不同，本契约承载 rag BC 的<b>完整检索编排</b>：查询理解 → 三路召回 → 融合 → 精排 →
 * 上下文构建 → 答案生成，产出可直接作答的检索结果。</p>
 *
 * <p>当前由 {@code DefaultRetrievalApi} 进程内实现（薄委托检索编排应用服务），未来接入 Feign
 * 时仅需在本接口追加 {@code @FeignClient} 注解并提供远程实现，消费方无需改动。</p>
 *
 * <p>依赖方向：消费方依赖本契约、由 rag BC 提供实现，与 {@code IngestionTaskSubmitter} 等
 * knowledgebase 定义端口相反（本契约由 rag 定义并发布），不引入 rag → 消费方的反向编译依赖。</p>
 */
public interface RetrievalApi {

    /**
     * 执行一次完整的知识库检索（Stage 0~5 全链路，同步返回检索结果）。
     * <p>前置：知识库须存在且处于 ACTIVE 状态，否则以业务异常拒绝；各阶段（召回到答案生成）
     * 异常按编排层降级矩阵兜底，仅以 {@code RetrievalResult.degraded} 标记，不向调用方抛出。</p>
     * <p>附图（通路 A）：{@code query.images} 为<b>已校验解码</b>的内联图片，仅由查询理解前置阶段
     * 消费；跨 BC 进程内消费方如需附图检索，应在构造 {@link RetrievalQuery} 前完成解码。</p>
     *
     * @param query 检索执行请求（kbId 与 query 必填，预算/开关字段由契约紧凑构造器归一），不可为空
     * @return 检索结果（{@code answer} 可为空——答案生成降级时；{@code context} 永不为空），永不为空
     * @throws IllegalArgumentException   请求对象为空
     * @throws com.linkroa.deepdataagent.shared.exception.DeepDataAgentException 知识库不存在或不处于 ACTIVE 状态
     */
    RetrievalResult search(RetrievalQuery query);
}
