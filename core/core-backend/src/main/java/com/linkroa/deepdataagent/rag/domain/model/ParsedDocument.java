package com.linkroa.deepdataagent.rag.domain.model;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;

import java.util.List;
import java.util.Map;

/**
 * 解析结果中间态（仅内存，不落库）。
 * <p>文档失败或重跑时随「清空重建」重新解析产生，解析结果不缓存到 llm_cache 之外的库表。</p>
 * <p>{@link #images} 为远程解析器（MinerU）随内容块一并返回的媒体图片字节载荷，
 * 仅作为「摄入 Worker 落对象存储」的中间载体：<b>不得</b>写入块 meta，
 * 也就不会随 {@code chunk.original_item} 落库（落库的只有对象存储引用）。</p>
 * <p><b>哈希口径区分</b>：{@link #parsedTextHash} 是<b>解析后文本</b>的 MD5 摘要
 * （32 位小写十六进制，仅作解析幂等与观测参考）；知识库侧的判重内容哈希是
 * <b>上传原始文件字节</b>的 SHA-256（64 位小写十六进制，持久化为独立一等列）。
 * 两者算法与输入均不相同，同名异物已解耦，MUST NOT 互相替代或比对。</p>
 *
 * @param parsedTextHash 解析文本摘要（解析后文本的 MD5，32 位小写十六进制；仅观测/幂等参考）
 * @param blocks         解析出的结构化内容块列表（ContentBlockVO 五类块）
 * @param fileName       源文档文件名（分块扩展名路由与分派留痕日志依据；
 *                       解析器侧不感知文件名，由摄入 Worker 统一包装填充，未包装时为 null）
 * @param images         媒体图片载荷（图片名 → 解码后的图片字节；本地解析器路径恒为空映射）
 */
public record ParsedDocument(String parsedTextHash, List<ContentBlockVO> blocks, String fileName,
                             Map<String, byte[]> images) {

    /**
     * 紧凑构造器：解析结果必须有内容块（空文档视为解析失败，交由调用方按 FAILED 处理）；
     * 图片载荷缺失归一为空映射，调用方无需判空。
     */
    public ParsedDocument {
        if (CollectionUtils.isEmpty(blocks)) {
            throw new IllegalArgumentException("解析结果不能为空");
        }
        images = ObjectUtils.isEmpty(images) ? Map.of() : Map.copyOf(images);
    }

    /**
     * 兼容构造器：解析器侧不感知文件名，且无媒体图片载荷。
     *
     * @param parsedTextHash 解析文本摘要（解析后文本的 MD5，32 位小写十六进制）
     * @param blocks         解析出的结构化内容块列表
     */
    public ParsedDocument(String parsedTextHash, List<ContentBlockVO> blocks) {
        this(parsedTextHash, blocks, null, Map.of());
    }

    /**
     * 兼容构造器：解析器侧不感知文件名，由摄入 Worker 包装填充。
     *
     * @param parsedTextHash 解析文本摘要（解析后文本的 MD5，32 位小写十六进制）
     * @param blocks         解析出的结构化内容块列表
     * @param fileName       源文档文件名
     */
    public ParsedDocument(String parsedTextHash, List<ContentBlockVO> blocks, String fileName) {
        this(parsedTextHash, blocks, fileName, Map.of());
    }

    /**
     * 解析器侧工厂方法：携带媒体图片载荷的解析产物（文件名由摄入 Worker 后续包装）。
     *
     * @param parsedTextHash 解析文本摘要（解析后文本的 MD5，32 位小写十六进制）
     * @param blocks         解析出的结构化内容块列表
     * @param images         媒体图片载荷（图片名 → 图片字节），可为 null
     * @return 解析结果
     */
    public static ParsedDocument withImages(String parsedTextHash, List<ContentBlockVO> blocks,
                                            Map<String, byte[]> images) {
        return new ParsedDocument(parsedTextHash, blocks, null, images);
    }
}