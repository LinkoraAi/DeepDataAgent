package com.linkroa.deepdataagent.rag.infrastructure.client;

import java.util.List;

/**
 * 库级重排批量打分端口（检索侧新增，精排 Stage 3 消费）。
 * <p>一次调用提交全部候选正文，由服务端在同一次远程请求内完成全部打分——开启重排的检索
 * 只比关闭重排多一次远程调用，而非每候选一次。实现可选用库级 reranker 模型
 * （modelProfileId 引用 agent BC 模型注册表），也可以在服务不可用时抛异常
 * （由 {@code Reranker} 捕获后降级直出粗排）。</p>
 *
 * <p><b>对齐契约（实现方必须满足）</b>：返回列表与入参 {@code passages} <b>等长且同序</b>，
 * 即返回值第 i 项恒为 {@code passages} 第 i 项的相关分，且不含 null。服务端响应乱序时，
 * 实现必须以响应 {@code index} 字段回填下标，禁止按响应数组的自然顺序返回；
 * 响应缺项、数量不符、index 越界或分数非数值一律抛异常，不得以零分占位——
 * 零分会让该候选被相似阈值静默过滤，属于不可察觉的错误降级。</p>
 */
public interface RerankClient {

    /**
     * 一次调用批量计算 query 与多个候选 passage 的相关分（数值越高越相关）。
     *
     * @param modelProfileId reranker 模型配置 profileId（必填，空白即抛异常）
     * @param query          用户查询文本（改写后 query）
     * @param passages       候选 chunk 正文列表，顺序即回填顺序；为空列表时不发起远程调用
     * @return 相关分列表，与 {@code passages} 等长同序；入参为空列表时返回空列表
     * @throws IllegalArgumentException 参数非法（modelProfileId 空白）
     * @throws RuntimeException         reranker 远程调用失败/超时，或响应协议异常（缺结果、数量不符、index 越界、分数非数值）
     */
    List<Double> scoreBatch(String modelProfileId, String query, List<String> passages);
}
