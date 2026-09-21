package com.linkroa.deepdataagent.rag.domain.port;

/**
 * LLM 多模态请求约束常量。
 * <p>本类是图片数量与体积上限的<b>唯一权威定义处</b>：三个场景的图片数上限互不相同，
 * 消费方（组 9 摄入媒体描述、组 10 通路 A 检索附图、组 11 通路 B 作答直读）各自引用
 * 对应常量做装载前校验；超限语义统一为「跳过该图并 WARN」，不阻断主流程。
 * 本组（基建②）只提供常量，不接入任何调用点。</p>
 *
 * @author DeepDataAgent
 */
public final class MultimodalConstraints {

    /**
     * 单次媒体描述请求允许携带的图片数上限（摄入链路 {@code MediaDescriptorService.describe}）。
     * <p>多模态块与图片一比一引用，逐块描述只需单图；批量塞图会污染描述归属。</p>
     */
    public static final int MAX_IMAGES_PER_DESCRIBE_REQUEST = 1;

    /**
     * 单次检索附图转译请求允许携带的图片数上限（通路 A：RAG 检索入参 {@code images}）。
     * <p>请求体按 base64 膨胀（约 4/3 倍），上限过大会击穿 HTTP 请求体限制。</p>
     */
    public static final int MAX_IMAGES_PER_QUERY_REQUEST = 3;

    /**
     * 单次作答请求允许携带的图片数上限（通路 B：作答侧原图直读）。
     * <p>直读增加尾延迟，仅按检索上下文序取前 2 张有效引用图。</p>
     */
    public static final int MAX_IMAGES_PER_ANSWER_REQUEST = 2;

    /**
     * 单张图片字节上限（4MB）。
     * <p>超限图片一律跳过，避免单图撑爆请求体与缓存摘要计算成本。</p>
     */
    public static final long MAX_IMAGE_BYTES = 4L * 1024 * 1024;

    private MultimodalConstraints() {
        // 常量类不允许构造实例
    }
}
