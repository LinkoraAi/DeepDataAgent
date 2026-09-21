package com.linkroa.deepdataagent.knowledgebase.application.service;

import com.linkroa.deepdataagent.knowledgebase.application.port.KbAssetStoragePort;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Document;
import com.linkroa.deepdataagent.knowledgebase.domain.model.S3File;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 媒体图片对象清理服务（knowledgebase BC）。
 * <p>把「按前缀清空媒体图片资产」原语下沉为单一组件，供三条删除路径（文档级删除链、
 * 知识库生命周期清退调度、上传判重换版清退）复用同一份前缀口径与清理动作，
 * 三处覆盖面互不重叠。前缀由 {@link MediaImageObjectPrefixes} 集中构造，与 rag 侧写入约定由契约测试锁定。</p>
 * <p>本服务同时承载文档源文件对象的定点回收（{@link #cleanupSourceFiles(Document)}）
 * 与切片级图片对象回收（{@link #recycleChunkMediaObject(S3File)}），
 * 统一经 {@link KbAssetStoragePort} 访问对象存储（桶概念已退役，仅按对象键 / 前缀定位）。</p>
 * <p>清理动作均为对象存储远程 IO，MUST 由调用方置于数据库事务之外执行（本服务自身不开启事务）；
 * 前缀清退与单对象删除均为幂等（对象不存在不报错）。</p>
 * <p>人工切片删除采用「Storage 先删、DB 后删」硬顺序：{@link #recycleChunkMediaObject(S3File)}
 * MUST NOT 吞异常——回收失败即抛业务异常终止本次删除（数据库零写入），由用户重删续跑，
 * 「对象不存在」视为成功（重删自愈半态）。</p>
 *
 * @author DeepDataAgent
 */
@Service
public class MediaImageCleanupService {

    private static final Logger log = LoggerFactory.getLogger(MediaImageCleanupService.class);

    /** 存储引用 JSON 中的对象键字段名 */
    private static final String FIELD_S3_OBJECT_KEY = "objectKey";

    /** 切片图片对象回收失败提示（事务前硬顺序：失败即终止，数据库零写入，可由用户重删自愈） */
    private static final String CHUNK_MEDIA_RECYCLE_ERROR = "切片图片对象回收失败，数据库未做任何变更，请重新执行删除";

    /** 存储引用 JSON 解析器（无状态，进程内共享） */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 知识库对象资产存储访问端口 */
    private final KbAssetStoragePort kbAssetStoragePort;

    /**
     * 构造媒体图片对象清理服务。
     *
     * @param kbAssetStoragePort 对象资产存储访问端口
     */
    public MediaImageCleanupService(KbAssetStoragePort kbAssetStoragePort) {
        this.kbAssetStoragePort = kbAssetStoragePort;
    }

    /**
     * 清理单文档前缀下的媒体图片对象（原 {@code DocumentApplicationService.cleanupMediaImages} 语义迁入）。
     * <p>按 {@code rag/{kbId}/{documentId}/images/} 前缀整体清退，不解析 chunk 行数据（纯前缀级）；
     * 前缀仅由 ID 派生，无需任何存储引用即可定位（桶概念已退役）。</p>
     * <p>前缀无法定位（ID 异常）视为「本就无图片资产」，WARN 跳过，MUST NOT 因 ID 异常阻断删除；
     * 存储异常上抛由删除链按既有失败留痕（{@code step=media_cleanup}）与用户手动重删幂等续跑口径处置。</p>
     *
     * @param document 待清理媒体图片的文档聚合根
     */
    public void cleanupDocumentImages(Document document) {
        if (ObjectUtils.isEmpty(document)) {
            log.warn("文档为空，跳过媒体图片对象清理");
            return;
        }
        String prefix = MediaImageObjectPrefixes.documentPrefix(document.kbId(), document.id());
        if (StringUtils.isBlank(prefix)) {
            log.warn("无法定位文档媒体图片前缀（ID 异常），跳过清理, kbId={}, documentId={}",
                    document.kbId(), document.id());
            return;
        }
        kbAssetStoragePort.cleanupPrefix(prefix);
        log.info("文档媒体图片对象清理完成, documentId={}, prefix={}", document.id(), prefix);
    }

    /**
     * 定点清理单文档的源文件对象。
     * <p>按文档登记的存储引用（{@code document.s3_file} JSON：{@code {objectKey}}）精确定位
     * 对象键执行定点删除——对象键与文档严格 1:1，永不跨文档共享，回收安全。
     * 存储行（引用 JSON）随文档行逻辑删隐身，本方法 MUST NOT 先行清除。</p>
     * <p>幂等语义：对象不存在视为成功（端口删除天然幂等）；引用缺失 / 非法 JSON / 缺对象键
     * 均视为「本就无源文件对象」，WARN 留痕跳过，MUST NOT 因缺引用阻断删除。</p>
     * <p>失败语义：存储删除异常原样上抛，由调用方按各自路径处置——文档终删链进入既有
     * DELETE_FAILED 留痕重删续跑；换版级联路径由调用方 try-catch WARN 尽力而为。
     * 本方法为对象存储远程 IO，MUST 由调用方置于数据库事务之外。</p>
     *
     * @param document 待回收源文件对象的文档聚合根
     */
    public void cleanupSourceFiles(Document document) {
        if (ObjectUtils.isEmpty(document)) {
            log.warn("文档为空，跳过源文件对象清理");
            return;
        }
        S3File reference = resolveSourceReferenceQuietly(document);
        if (ObjectUtils.isEmpty(reference)) {
            log.warn("文档无有效源文件存储引用，视为无源文件对象，跳过清理, documentId={}", document.id());
            return;
        }
        kbAssetStoragePort.delete(reference.objectKey());
        log.info("文档源文件对象清理完成, documentId={}, objectKey={}", document.id(), reference.objectKey());
    }

    /**
     * 宽松解析多模态图片对象存储引用 JSON（{@code chunk.s3_file}：{@code {objectKey}}）。
     * <p>口径与文档源文件引用一致：空白引用、非法 JSON、缺对象键一律返回 {@code null}
     * （文本切片本就无图片引用，属预期），由调用方按「无可回收对象」跳过，MUST NOT 抛异常阻断删除。</p>
     *
     * @param referenceJson 图片对象引用 JSON，可为空白
     * @return 图片对象引用值对象；无法完整定位时返回 {@code null}
     */
    public S3File parseMediaReference(String referenceJson) {
        JsonNode reference = parseReferenceQuietly(referenceJson);
        if (ObjectUtils.isEmpty(reference)) {
            return null;
        }
        String objectKey = reference.path(FIELD_S3_OBJECT_KEY).stringValue(null);
        if (StringUtils.isBlank(objectKey)) {
            return null;
        }
        return new S3File(objectKey);
    }

    /**
     * 事务前回收单条切片多模态图片对象（「Storage 先删、DB 后删」）。
     * <p>调用时序前置约束：本方法 MUST 在删除事务开启之前调用，且引用计数（同文档是否仍有存活切片
     * 引用同一对象）由调用方先行判定——本方法只负责「确定要回收」的那一个对象。</p>
     * <p>失败语义：<b>不吞异常</b>。对象不存在视为成功（端口删除天然幂等，重删即自愈半态）；
     * 存储侧任何其他异常一律转换为业务异常上抛，令本次删除在数据库零写入的前提下整体终止，
     * 用户重新执行删除即可续跑。</p>
     *
     * @param mediaObject 待回收的图片对象引用，可为 {@code null}（无引用即无可回收对象，跳过）
     * @throws DeepDataAgentException 存储回收失败（已终止删除，数据库零写入）
     */
    public void recycleChunkMediaObject(S3File mediaObject) {
        if (ObjectUtils.isEmpty(mediaObject)) {
            return;
        }
        try {
            kbAssetStoragePort.delete(mediaObject.objectKey());
        } catch (RuntimeException e) {
            log.error("切片图片对象回收失败（终止本次删除，数据库零写入）, objectKey={}",
                    mediaObject.objectKey(), e);
            throw new DeepDataAgentException(CHUNK_MEDIA_RECYCLE_ERROR + "：" + e.getMessage());
        }
        log.info("切片图片对象回收完成, objectKey={}", mediaObject.objectKey());
    }

    /**
     * 按整库前缀清退该库全部对象资产（知识库生命周期清退调度专用）。
     * <p>单次前缀清退 {@code rag/{kbId}/} 即覆盖该库全部源文件与文档级媒体前缀
     * （由契约测试锁定），不依赖逐篇文档 / 逐条切片的引用解析；重复清退幂等（重删即自愈半态）。</p>
     * <p>失败语义：存储异常原样上抛，由调度器按「WARN 留痕 + 本轮暂缓该库 DB 清退（下轮重入）」
     * 口径处置。</p>
     *
     * @param kbId 知识库 ID（为空时 WARN 跳过，MUST NOT 抛异常阻断清退调度）
     */
    public void cleanupKnowledgeBaseImages(Long kbId) {
        String prefix = MediaImageObjectPrefixes.knowledgeBasePrefix(kbId);
        if (StringUtils.isBlank(prefix)) {
            log.warn("无法定位整库媒体图片前缀（kbId 异常），跳过清理, kbId={}", kbId);
            return;
        }
        kbAssetStoragePort.cleanupPrefix(prefix);
        log.info("整库媒体图片对象清理完成, kbId={}, prefix={}", kbId, prefix);
    }

    /**
     * 宽松解析文档源文件完整存储引用（对象键，定点清理源文件对象的定位依据）。
     * <p>引用缺失 / 非法 JSON / 缺对象键任一即返回 {@code null}，交由调用方按
     * 「无源文件对象」WARN 跳过，MUST NOT 抛异常阻断删除。</p>
     *
     * @param document 文档聚合根
     * @return 源文件存储引用值对象；无法完整定位时返回 {@code null}
     */
    private S3File resolveSourceReferenceQuietly(Document document) {
        return parseMediaReference(document.s3File());
    }

    /**
     * 宽松解析存储引用 JSON 为树节点：空白与非法 JSON 均返回 {@code null}（记 WARN 留痕），MUST NOT 抛异常。
     *
     * @param referenceJson 存储引用 JSON，可为空白
     * @return 引用 JSON 树节点；无法解析时返回 {@code null}
     */
    private JsonNode parseReferenceQuietly(String referenceJson) {
        if (StringUtils.isBlank(referenceJson)) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readTree(referenceJson);
        } catch (JacksonException e) {
            log.warn("存储引用非法 JSON，无法定位对象存储引用，原始值={}", referenceJson, e);
            return null;
        }
    }
}
