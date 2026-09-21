package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.knowledgebase.application.port.KbAssetStoragePort;
import com.linkroa.deepdataagent.rag.domain.port.LlmImage;
import com.linkroa.deepdataagent.rag.domain.port.MultimodalConstraints;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 作答侧原图直读解析器（命中图片原图直读作答）。
 *
 * <p><b>职责</b>：接收从检索上下文 chunk 中<b>已提取</b>的媒体图片引用（{@link MediaReference}，
 * 按上下文相关性序），完成「装载前收敛 + 逐引用读原图」两步，产出可直接进入
 * {@link com.linkroa.deepdataagent.rag.domain.port.LlmChatRequest#images()} 的图片载荷列表：</p>
 * <ol>
 *   <li>去重：以 {@code objectKey} 为唯一键保序去重（同一图片被多条上下文命中只读一次）；</li>
 *   <li>截断：去重后引用数超过 {@link MultimodalConstraints#MAX_IMAGES_PER_ANSWER_REQUEST} 时，
 *       按上下文序仅保留上限内的引用，并输出含截断数量的 INFO；</li>
 *   <li>读取：逐个引用经 {@link KbAssetStoragePort#open} 读取对象字节，
 *       读成功且不超过 {@link MultimodalConstraints#MAX_IMAGE_BYTES} 才纳入结果；
 *       读失败 / 对象不存在 / 内容为空 / 超字节上限时跳过该图并 WARN 留痕
 *       （含 {@code chunkId} / {@code objectKey} 定位，对应）。</li>
 * </ol>
 *
 * <p><b>零开销契约</b>：入参为空列表时直接返回空结果，<b>不发起任何对象存储交互</b>；
 * 「视觉模型是否配置」由调用方（{@code DefaultAnswerGenerator}）判定后再决定是否调用本解析器，
 * 避免未配置场景产生读取开销。</p>
 *
 * <p><b>内容类型推断</b>：存储端口只回字节，MIME 依据 {@code objectKey}
 * 文件后缀推断（与摄入侧 {@code MediaImagePersistenceService} 写入时同一后缀口径），
 * 无法识别回落 {@code application/octet-stream}。</p>
 *
 * @author DeepDataAgent
 */
@Component
public class AnswerImageResolver {

    /** 日志器 */
    private static final Logger log = LoggerFactory.getLogger(AnswerImageResolver.class);

    /** 内容类型缺省值（后缀无法识别时按二进制流传递） */
    private static final String CONTENT_TYPE_DEFAULT = "application/octet-stream";

    /** 文件名扩展名分隔符 */
    private static final String FILE_EXTENSION_SEPARATOR = ".";

    /** 图片后缀 → 内容类型映射（大小写不敏感，与摄入侧写入口径一致，未收录后缀回落缺省值） */
    private static final Map<String, String> CONTENT_TYPE_BY_EXTENSION = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "gif", "image/gif",
            "webp", "image/webp");

    /** 知识库对象资产存储访问端口（跨 BC 消费，进程内实现，读取原图字节） */
    private final KbAssetStoragePort kbAssetStoragePort;

    /**
     * 构造原图直读解析器。
     *
     * @param kbAssetStoragePort 对象资产存储访问端口
     */
    public AnswerImageResolver(KbAssetStoragePort kbAssetStoragePort) {
        this.kbAssetStoragePort = kbAssetStoragePort;
    }

    /**
     * 解析上下文媒体引用为可直读的原图载荷列表。
     * <p>去重、按 {@link MultimodalConstraints#MAX_IMAGES_PER_ANSWER_REQUEST} 截断后逐引用读取原图；
     * 全部无效（读取失败 / 不存在 / 超限）时返回空列表，由调用方回落纯文本作答。</p>
     *
     * @param references 上下文来源的媒体引用列表（按相关性序，可空 / 可空列表）
     * @return 直读成功的图片载荷列表（顺序与截断后的引用一致）；无有效图片时为空列表
     */
    public List<LlmImage> resolve(List<MediaReference> references) {
        if (CollectionUtils.isEmpty(references)) {
            return List.of();
        }
        List<MediaReference> distinct = dedupByObjectKey(references);
        if (CollectionUtils.isEmpty(distinct)) {
            return List.of();
        }
        int limit = MultimodalConstraints.MAX_IMAGES_PER_ANSWER_REQUEST;
        List<MediaReference> toLoad = distinct;
        if (distinct.size() > limit) {
            toLoad = new ArrayList<>(distinct.subList(0, limit));
            log.info("上下文原图引用超过单次直读上限，已按相关性序截断: distinct={}, limit={}, truncated={}",
                    distinct.size(), limit, distinct.size() - limit);
        }
        List<LlmImage> images = new ArrayList<>(toLoad.size());
        for (MediaReference reference : toLoad) {
            LlmImage image = loadOne(reference);
            if (ObjectUtils.isNotEmpty(image)) {
                images.add(image);
            }
        }
        return images;
    }

    /**
     * 以 {@code objectKey} 为唯一键保序去重，跳过对象键空白的无效引用。
     *
     * @param references 原始引用列表（非空）
     * @return 去重后的有效引用列表（保持首次出现的相对顺序）
     */
    private List<MediaReference> dedupByObjectKey(List<MediaReference> references) {
        List<MediaReference> distinct = new ArrayList<>(references.size());
        Set<String> seenKeys = new LinkedHashSet<>(references.size());
        for (MediaReference reference : references) {
            if (ObjectUtils.isEmpty(reference) || StringUtils.isBlank(reference.objectKey())) {
                continue;
            }
            if (seenKeys.add(reference.objectKey())) {
                distinct.add(reference);
            }
        }
        return distinct;
    }

    /**
     * 读取单个引用对应的原图字节并封装为 {@link LlmImage}；任何不可用情形均返回 {@code null}（跳过该图）。
     *
     * @param reference 媒体引用（对象键已由调用方保证非空白）
     * @return 图片载荷；读取失败 / 对象不存在 / 内容为空 / 超字节上限时返回 {@code null}
     */
    private LlmImage loadOne(MediaReference reference) {
        try (InputStream in = kbAssetStoragePort.open(reference.objectKey())
                .map(KbAssetStoragePort.OpenedObject::content).orElse(null)) {
            if (ObjectUtils.isEmpty(in)) {
                log.warn("原图对象不存在（读取返回空流），跳过该图: chunkId={}, objectKey={}",
                        reference.chunkId(), reference.objectKey());
                return null;
            }
            // 有界读取——至多读「上限 + 1」字节即判超限，
            // 超大对象不再全量缓冲入堆；超限/空内容/读败的 WARN 与跳过语义不变
            byte[] content = in.readNBytes((int) (MultimodalConstraints.MAX_IMAGE_BYTES + 1));
            if (ObjectUtils.isEmpty(content)) {
                log.warn("原图内容为空，跳过该图: chunkId={}, objectKey={}",
                        reference.chunkId(), reference.objectKey());
                return null;
            }
            if ((long) content.length > MultimodalConstraints.MAX_IMAGE_BYTES) {
                log.warn("原图超过单图字节上限，跳过该图: chunkId={}, objectKey={}, bytes={}, maxBytes={}",
                        reference.chunkId(), reference.objectKey(), content.length,
                        MultimodalConstraints.MAX_IMAGE_BYTES);
                return null;
            }
            return new LlmImage(resolveContentType(reference.objectKey()), content);
        } catch (IOException | RuntimeException e) {
            log.warn("读取原图失败，跳过该图: chunkId={}, objectKey={}",
                    reference.chunkId(), reference.objectKey(), e);
            return null;
        }
    }

    /**
     * 依据对象键文件后缀推断图片内容类型。
     *
     * @param objectKey 对象键
     * @return 内容类型；后缀缺失或不可识别时返回 {@link #CONTENT_TYPE_DEFAULT}
     */
    private String resolveContentType(String objectKey) {
        String extension = StringUtils.lowerCase(
                StringUtils.substringAfterLast(objectKey, FILE_EXTENSION_SEPARATOR), Locale.ROOT);
        if (StringUtils.isBlank(extension)) {
            return CONTENT_TYPE_DEFAULT;
        }
        return CONTENT_TYPE_BY_EXTENSION.getOrDefault(extension, CONTENT_TYPE_DEFAULT);
    }

    /**
     * 上下文来源的单个媒体图片引用（值对象）：由 {@code DefaultAnswerGenerator} 从检索 chunk 的
     * {@code original_item} 解析得出，携带可选 {@code chunkId} 用于跳过图片时的 WARN 定位
     * （仅对象键定位，桶概念已退役）。
     *
     * @param chunkId   来源 chunk 主键（可为空，仅用于日志定位）
     * @param objectKey 对象键（{@code mediaObjectKey}）
     */
    public record MediaReference(Long chunkId, String objectKey) {
    }
}
