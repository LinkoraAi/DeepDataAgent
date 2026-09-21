package com.linkroa.deepdataagent.rag.application.service;

import com.linkroa.deepdataagent.knowledgebase.application.port.KbAssetStoragePort;
import com.linkroa.deepdataagent.rag.domain.model.MediaFileRef;
import com.linkroa.deepdataagent.rag.domain.model.ParsedDocument;
import com.linkroa.deepdataagent.rag.domain.service.MediaImageObjectKeys;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 解析产物媒体图片持久化服务（摄入管线 Stage 1a 挂钩）。
 * <p>把 {@link ParsedDocument#images()}（MinerU 返回并解码后的图片字节）经
 * {@link KbAssetStoragePort} 逐张写入对象存储（桶概念已退役，落配置固定桶）：
 * 对象键由 {@link MediaImageObjectKeys} 统一约定
 * （{@code rag/{kbId}/{documentId}/images/{img_name}}），内容类型按文件名后缀推断。</p>
 *
 * <p>失败语义（摄入不阻断）：图片名净化失败、图片字节为空、单张写入异常均仅 WARN 留痕并跳过该图，
 * 返回映射中不含该图引用，下游多模态块维持「无引用即文本形态」。</p>
 *
 * <p>同名防线：不同原始图片名净化后得到同一基名且字节不同时，
 * MUST NOT 以覆盖写把两处引用静默收敛成同一张图——该基名的引用一律摘除（两图都不给引用）并 WARN 留痕，
 * 相关块回落无图片引用的文本形态；字节完全相同的重复图片名不算冲突，维持既有覆盖写与单一引用。</p>
 *
 * <p>解析产物不含图片（本地 Tika 解析器路径）时直接返回空映射，<b>不发起任何对象存储交互</b>，
 * 行为与升级前完全一致。返回值只含引用（仅对象键），图片字节不外泄到块 meta 与落库链路。</p>
 *
 * @author DeepDataAgent
 */
@Service
public class MediaImagePersistenceService {

    private static final Logger log = LoggerFactory.getLogger(MediaImagePersistenceService.class);

    /** 内容类型缺省值（后缀无法识别时按二进制流落库）。 */
    private static final String CONTENT_TYPE_DEFAULT = "application/octet-stream";

    /** 文件名扩展名分隔符。 */
    private static final String FILE_EXTENSION_SEPARATOR = ".";

    /** 图片后缀 → 内容类型映射（大小写不敏感，未收录后缀回落缺省值）。 */
    private static final Map<String, String> CONTENT_TYPE_BY_EXTENSION = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "gif", "image/gif",
            "webp", "image/webp");

    /** 知识库对象资产存储访问端口（跨 BC 消费，媒体图片写入通道）。 */
    private final KbAssetStoragePort kbAssetStoragePort;

    /**
     * 构造媒体图片持久化服务。
     *
     * @param kbAssetStoragePort 对象资产存储访问端口
     */
    public MediaImagePersistenceService(KbAssetStoragePort kbAssetStoragePort) {
        this.kbAssetStoragePort = kbAssetStoragePort;
    }

    /**
     * 将解析产物中的媒体图片逐张落对象存储。
     * <p>远程写入位于事务外（Stage 1a 解析段，切片落库事务之前），符合「事务内禁止远程调用」约束。</p>
     * <p>同名防线：两个不同原始图片名净化后得到同一基名时——
     * 字节完全相同视为同一张图重复出现，维持覆盖写与单一引用、不告警；
     * 字节不同则判定冲突，该基名的引用一律摘除（首写引用一并摘除，两图都不给引用）且不再落图，
     * 由 {@code IngestionWorker.bindBlockMediaRef} 的「引用未命中即原样返回块」既有分支回落文本形态。
     * 宁缺勿错：错误图片喂 VLM 会产生不可控的错误答案与图谱污染。</p>
     *
     * @param kbId       知识库主键（对象键前缀组成）
     * @param documentId 文档主键（对象键前缀组成）
     * @param parsed     解析产物（可为 null；无图片载荷时零对象存储交互）
     * @return 图片名（净化后基名）→ 对象存储引用（仅对象键）；无成功写入图片时为空映射（冲突基名不出现在映射中）
     */
    public Map<String, MediaFileRef> persistImages(Long kbId, Long documentId, ParsedDocument parsed) {
        if (ObjectUtils.isEmpty(parsed) || MapUtils.isEmpty(parsed.images())) {
            return Map.of();
        }
        Map<String, MediaFileRef> refs = new LinkedHashMap<>();
        // 基名 → 已落地的图片（原始图片名 + 字节副本），用于同名冲突判定与 WARN 定位
        Map<String, WrittenImage> writtenByName = new LinkedHashMap<>();
        // 已判定冲突的基名：其引用被摘除后，本篇摄入内后续同名图一律不落图、不给引用
        Set<String> conflictedNames = new LinkedHashSet<>();
        for (Map.Entry<String, byte[]> entry : parsed.images().entrySet()) {
            String rawName = entry.getKey();
            String imgName = MediaImageObjectKeys.sanitize(rawName);
            if (StringUtils.isBlank(imgName)) {
                log.warn("媒体图片名不合法（空白或路径穿越），跳过该图, kbId={}, documentId={}, imgName={}",
                        kbId, documentId, rawName);
                continue;
            }
            if (ObjectUtils.isEmpty(entry.getValue())) {
                log.warn("媒体图片字节为空，跳过该图, kbId={}, documentId={}, imgName={}", kbId, documentId, imgName);
                continue;
            }
            if (conflictedNames.contains(imgName)) {
                continue;
            }
            WrittenImage written = writtenByName.get(imgName);
            if (ObjectUtils.isEmpty(written)) {
                MediaFileRef ref = putImage(kbId, documentId, imgName, entry.getValue());
                if (ObjectUtils.isNotEmpty(ref)) {
                    writtenByName.put(imgName, new WrittenImage(rawName, Arrays.copyOf(entry.getValue(),
                            entry.getValue().length)));
                    refs.put(imgName, ref);
                }
                continue;
            }
            if (Arrays.equals(written.content(), entry.getValue())) {
                // 同一张图的重复名字：维持既有覆盖写与单一引用，不告警
                putImage(kbId, documentId, imgName, entry.getValue());
                continue;
            }
            // 同名不同字节：摘除该基名的全部引用（含首写），不覆盖对象、不新增删除动作
            refs.remove(imgName);
            conflictedNames.add(imgName);
            log.warn("媒体图片同名不同字节冲突，该基名引用全部摘除并回落文本形态, kbId={}, documentId={}, "
                            + "conflictName={}, rawNames=[{}, {}], bytes=[{}, {}]",
                    kbId, documentId, imgName, written.rawName(), rawName,
                    written.content().length, entry.getValue().length);
        }
        logPersistenceOutcome(kbId, documentId, parsed, refs, conflictedNames);
        return Map.copyOf(refs);
    }

    /**
     * 输出持久化终态日志：冲突基名做断言式留痕，确保其既不在返回引用中、也不会被后续绑定命中。
     *
     * @param kbId            知识库主键
     * @param documentId      文档主键
     * @param parsed          解析产物
     * @param refs            最终引用映射
     * @param conflictedNames 判定为冲突的基名集合
     */
    private void logPersistenceOutcome(Long kbId, Long documentId, ParsedDocument parsed,
                                       Map<String, MediaFileRef> refs, Set<String> conflictedNames) {
        if (CollectionUtils.isEmpty(conflictedNames)) {
            log.info("媒体图片持久化 | kbId={} documentId={} images={} persisted={}",
                    kbId, documentId, parsed.images().size(), refs.size());
            return;
        }
        for (String conflicted : conflictedNames) {
            if (refs.containsKey(conflicted)) {
                log.error("媒体图片同名防线失效：冲突基名仍残留引用, kbId={}, documentId={}, conflictName={}",
                        kbId, documentId, conflicted);
            }
        }
        log.warn("媒体图片持久化存在同名冲突基名，已全部摘除引用回落文本形态 | kbId={} documentId={} images={} "
                + "persisted={} conflictedNames={}", kbId, documentId, parsed.images().size(), refs.size(),
                conflictedNames);
    }

    /**
     * 已落地的单张媒体图片（原始图片名 + 字节副本），供同名冲突判定与 WARN 定位。
     *
     * @param rawName 解析产物中的原始图片名（可含相对目录）
     * @param content 已写入对象存储的图片字节副本
     */
    private record WrittenImage(String rawName, byte[] content) {
    }

    /**
     * 写入单张媒体图片（图片名已由调用方净化；端口覆盖语义支撑同名重摄入替换旧字节）。
     *
     * @param kbId       知识库主键
     * @param documentId 文档主键
     * @param imgName    净化后的图片文件名
     * @param content    图片字节（非空）
     * @return 对象存储引用（仅对象键）；写入失败返回 null（该图无引用，摄入继续）
     */
    private MediaFileRef putImage(Long kbId, Long documentId, String imgName, byte[] content) {
        String objectKey = MediaImageObjectKeys.keyOf(kbId, documentId, imgName);
        try {
            kbAssetStoragePort.putMedia(objectKey, content, resolveContentType(imgName));
            return new MediaFileRef(objectKey);
        } catch (Exception e) {
            log.warn("媒体图片写对象存储失败，该图不落引用, kbId={}, documentId={}, imgName={}, "
                    + "objectKey={}", kbId, documentId, imgName, objectKey, e);
            return null;
        }
    }

    /**
     * 按图片文件名后缀推断内容类型。
     *
     * @param imgName 净化后的图片文件名
     * @return 内容类型；后缀缺失或不可识别时返回 {@link #CONTENT_TYPE_DEFAULT}
     */
    private String resolveContentType(String imgName) {
        String extension = StringUtils.lowerCase(StringUtils.substringAfterLast(imgName, FILE_EXTENSION_SEPARATOR));
        if (StringUtils.isBlank(extension)) {
            return CONTENT_TYPE_DEFAULT;
        }
        return CONTENT_TYPE_BY_EXTENSION.getOrDefault(extension, CONTENT_TYPE_DEFAULT);
    }
}
