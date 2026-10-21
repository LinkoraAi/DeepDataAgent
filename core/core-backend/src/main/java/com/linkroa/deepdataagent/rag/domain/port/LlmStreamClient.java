package com.linkroa.deepdataagent.rag.domain.port;

import reactor.core.publisher.Flux;

/**
 * LLM 流式对话端口（增量输出，供检索作答链路逐 token 消费）。
 * <p>与 {@link LlmClient#chat(LlmChatRequest)} 的关系：二者语义等价，本端口只是不把增量聚合，
 * 由调用方决定何时拼接、何时终止。生产装配由同一实现类提供，缓存装饰（{@code CachingLlmClient}）
 * 不参与本端口——需要缓存回放的场景走 {@link LlmClient#chat(LlmChatRequest)}。</p>
 * <p><b>订阅语义</b>：返回的 {@link Flux} 为冷流，每次订阅才真正发起远程调用；调用方 MUST
 * 在不再需要结果时（客户端断开、用户停止生成）dispose 订阅，以停止继续消耗提供方 token。
 * 流正常结束、出错与取消分别对应 {@link Flux#doOnComplete()}、{@link Flux#doOnError} 与
 * {@link Flux#doOnCancel}。</p>
 * <p>实现方 MUST 保证调用发生在数据库事务之外（事务内严禁远程调用），与
 * {@link LlmClient} 的约束一致。</p>
 *
 * @author DeepDataAgent
 */
public interface LlmStreamClient {

    /**
     * 发起一次流式对话补全。
     *
     * @param request 对话请求（kbId/模型引用/提示词/可选图片）
     * @return 增量文本流；空响应（未产出任何分片）由调用方决定如何降级
     * @throws IllegalArgumentException 请求参数非法（入口同步校验）
     */
    Flux<LlmStreamDelta> stream(LlmChatRequest request);
}