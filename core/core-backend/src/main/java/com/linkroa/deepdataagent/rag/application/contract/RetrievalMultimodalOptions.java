package com.linkroa.deepdataagent.rag.application.contract;

/**
 * 检索多模态通路参数值对象。
 * <p>承载两条多模态通路的独立开关，随 {@link RetrievalQuery} 契约单点下传至编排层
 * （通路 A 生效点：{@code RetrievalApplicationService} Stage 0 前置转译分支）与
 * 作答层（通路 B 生效点：{@code DefaultAnswerGenerator} 原图解析入口），
 * HTTP 端点与进程内调用方共享同一语义。两开关为基元 boolean（契约内非空、无三态），
 * 请求体缺省字段由 controller 层归一为 true 后再构造本值对象。</p>
 *
 * @param queryImageTranscribe  通路 A（检索附图转译）开关：{@code true} = 维持 
 *                              条件自动触发行为（仅请求携带附图时触发转译增强查询）；
 *                              {@code false} = 显式关闭，请求仍带附图时忽略全部附图并输出 WARN，
 *                              按纯文本查询继续检索，SHALL NOT 拒绝请求
 * @param answerImageDirectRead 通路 B（作答原图直读）开关：{@code true} = 维持 
 *                              条件自动触发行为（仅检索上下文含有效图片引用且视觉模型已配置时直读）；
 *                              {@code false} = 显式关闭，作答阶段跳过原图引用解析与原图直读，
 *                              零对象存储读取开销，直接走既有纯文本作答链路
 */
public record RetrievalMultimodalOptions(boolean queryImageTranscribe, boolean answerImageDirectRead) {

    /**
     * 默认工厂：双开关全开（与无条件自动触发的基线行为逐字节等价，
     * 亦即请求体缺省两开关字段时的语义，保证既有调用方零改动兼容）。
     *
     * @return 双 true 的默认值对象
     */
    public static RetrievalMultimodalOptions defaults() {
        return new RetrievalMultimodalOptions(true, true);
    }
}
