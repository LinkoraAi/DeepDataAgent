package com.linkroa.deepdataagent.knowledgebase.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linkroa.deepdataagent.knowledgebase.api.ChunkEmbeddingApi;
import com.linkroa.deepdataagent.knowledgebase.application.contract.ChunkDraft;
import com.linkroa.deepdataagent.knowledgebase.application.command.CreateChunkCommand;
import com.linkroa.deepdataagent.knowledgebase.application.command.DeleteChunkCommand;
import com.linkroa.deepdataagent.knowledgebase.application.command.DeleteChunksCommand;
import com.linkroa.deepdataagent.knowledgebase.application.command.UpdateChunkCommand;
import com.linkroa.deepdataagent.knowledgebase.application.port.KbAssetStoragePort;
import com.linkroa.deepdataagent.knowledgebase.application.query.ListChunkQuery;
import com.linkroa.deepdataagent.knowledgebase.application.result.ChunkMediaResult;
import com.linkroa.deepdataagent.knowledgebase.application.validation.DocumentValidator;
import com.linkroa.deepdataagent.knowledgebase.application.validation.KnowledgeBaseValidator;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Chunk;
import com.linkroa.deepdataagent.knowledgebase.domain.model.ChunkRepresentation;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Document;
import com.linkroa.deepdataagent.knowledgebase.domain.model.KnowledgeBase;
import com.linkroa.deepdataagent.knowledgebase.domain.model.S3File;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ChunkContentType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ChunkSource;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentStatus;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.ChunkRepresentationRepository;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.ChunkRepository;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.DocumentRepository;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.KnowledgeBaseRepository;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import com.linkroa.deepdataagent.shared.util.BatchSplitter;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 切片应用服务（切片新增 / 修改 / 删除 / 查询）。
 * <p>切片由 RAG 上下文的异步摄入任务产出，本服务负责切片本身的增删改查，
 * 并在切片数量发生变化时同步回写所属文档的分块计数，保证列表展示口径一致。</p>
 * <p>人工删除入口时序（「Storage 先删、DB 后删」）：
 * 闸门校验（知识库非 ACTIVE / 文档进入删除链拒绝，切片或文档行缺失 404）→ <b>来源准入</b>
 * （本批必须全为「人工新增」来源，含解析产生的切片即整批拒绝并引导改用重新解析 / 删除文档——
 * 解析块单独删除会让图谱账本永久引用已消失的分块）→ 同文档图片对象引用计数 →
 * 无他引则<b>事务外</b>回收对象存储图片（对象不存在=成功；回收失败即抛「请重新执行删除」，数据库零写入）→
 * 经 {@link ChunkPhysicalDeleteService#deleteChunks} 原语完成物理清退
 * （图谱账本收敛与缓存回收 → 表示 → 切片行 → 分块计数回写同事务原子生效；收敛段由批次数据
 * 推导，经准入的人工批自然零图谱读写、零远程）。</p>
 * <p>人工新增入口：序号由服务端计算为该文档当前最大序号加一（客户端传入的序号被忽略，
 * 人工块恒位于文档末尾），来源恒打「人工新增」标；重新解析的整篇替换会清除该文档
 * <b>全部</b>分块（含人工新增），该语义由接口契约显式声明并由前端二次确认承接。</p>
 * <p>人工新增 / 编辑时序（同步写表示）：闸门与入参校验 → <b>事务外</b>调用
 * {@link ChunkEmbeddingApi} 取该切片正文的向量字面量（远程调用，事务规范 1.1；
 * 未配置嵌入模型时返回空，降级为仅写全文表示）→ 单事务内写切片行 + UPSERT 1:1 派生表示
 * （{@code chunk_vector} 与 {@code chunk_tsv}）+ 回写文档分块计数。切片行与派生表示同事务
 * 提交或整体回滚，不存在「内容已变更而表示未更新」的可见状态；向量计算失败即零写入。</p>
 */
@Service
public class ChunkApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ChunkApplicationService.class);

    /** s3_file 引用 JSON 解析器（读取 {@link ChunkDraft#metadata()} 中的媒体引用键） */
    private static final ObjectMapper S3_REFERENCE_MAPPER = new ObjectMapper();

    /** 跨 BC 契约媒体引用键：对象键（由 rag 摄入管线注入 {@link ChunkDraft#metadata()}；桶概念已退役） */
    private static final String META_KEY_MEDIA_OBJECT_KEY = "mediaObjectKey";

    /** s3_file 落库 JSON 字段名：对象键（对齐 document.s3_file 形态） */
    private static final String FIELD_S3_OBJECT_KEY = "objectKey";

    /** 切片列表默认每页条数 */
    private static final int DEFAULT_LIST_SIZE = 20;

    /** 切片列表默认页码 */
    private static final int DEFAULT_LIST_PAGE = 1;

    /** 出队领取（markProcessing）的唯一合法源状态：PENDING（已入队）→ PROCESSING（开始解析）；FAILED 须先经用户重新解析回到 PENDING */
    private static final Set<DocumentStatus> MARK_PROCESSING_FROM_STATUSES = Set.of(DocumentStatus.PENDING);

    /** 成功终态写入（markProcessed）的唯一合法源状态：PROCESSING（已出队领取、管线在飞）→ PROCESSED（管线完整结束） */
    private static final Set<DocumentStatus> MARK_PROCESSED_FROM_STATUSES = Set.of(DocumentStatus.PROCESSING);

    /** 非终态批量收敛覆盖的状态集合：PENDING（已入队）与 PROCESSING（在飞）在进程重启后均无对应内存任务，由本实例启动恢复统一收敛为 FAILED */
    private static final Set<DocumentStatus> NON_TERMINAL_STATUSES =
            Set.of(DocumentStatus.PENDING, DocumentStatus.PROCESSING);

    /** 已进入删除链的状态集合：DELETING / DELETE_FAILED 两态拒绝切片回写（删除链两态闸门，
     *  DELETED 常量已移除，行缺失由文档行锁读取按 404 前置兜底） */
    private static final Set<DocumentStatus> DELETION_CHAIN_STATUSES =
            Set.of(DocumentStatus.DELETING, DocumentStatus.DELETE_FAILED);

    /** 人工批量删除单批上限（事务规范「批量 500~1000 条/批」，与删除原语批次口径一致） */
    private static final int DELETE_BATCH_SIZE = 500;

    /** 媒体图片内容类型兜底值：对象键扩展缺失或不可识别时按二进制流传递 */
    private static final String DEFAULT_MEDIA_CONTENT_TYPE = "application/octet-stream";

    /** 媒体图片对象键扩展名 → 内容类型映射（与 rag 侧持久化落图时的类型口径同源） */
    private static final Map<String, String> MEDIA_CONTENT_TYPE_BY_EXTENSION = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "gif", "image/gif",
            "webp", "image/webp");

    /** 对象键扩展名分隔符 */
    private static final String FILE_EXTENSION_SEPARATOR = ".";

    /** 所属知识库不可用时回传给媒体预览端点的业务错误消息 */
    private static final String ERROR_KB_UNAVAILABLE = "知识库不可用，禁止访问切片媒体";

    /** 错误文案：人工删除入口拒删解析产生的切片（附引导，见来源准入校验）。 */
    private static final String ERROR_PARSED_CHUNK_DELETION_FORBIDDEN =
            "解析产生的切片不支持单独删除（其图谱账本贡献会失效），"
                    + "请通过「重新解析」整篇重建或删除整个文档来处理";

    /** 切片仓储 */
    private final ChunkRepository chunkRepository;

    /** 切片 1:1 派生表示仓储（向量 / 全文表示读写） */
    private final ChunkRepresentationRepository chunkRepresentationRepository;

    /** 文档仓储（文档行锁读取与分块计数回写） */
    private final DocumentRepository documentRepository;

    /** 知识库仓储（库级闸门校验） */
    private final KnowledgeBaseRepository knowledgeBaseRepository;

    /** 编程式事务模板（切片写入与计数回写的精确事务边界） */
    private final TransactionTemplate transactionTemplate;

    /** 切片物理删除原语（事务内核；人工删除入口以独立事务调用并回写分块计数） */
    private final ChunkPhysicalDeleteService chunkPhysicalDeleteService;

    /** 媒体图片对象清理服务（切片图片对象的事务前硬顺序回收与宽松引用解析） */
    private final MediaImageCleanupService mediaImageCleanupService;

    /** 知识库对象资产存储访问端口（切片媒体预览的只读代理通道） */
    private final KbAssetStoragePort kbAssetStoragePort;

    /** 切片向量化契约（跨 BC 端口，实现位于 rag BC；事务外同步取向量字面量，未配置模型返回空） */
    private final ChunkEmbeddingApi chunkEmbeddingApi;

    /**
     * 构造切片应用服务。
     *
     * @param chunkRepository               切片仓储
     * @param chunkRepresentationRepository 切片 1:1 派生表示仓储
     * @param documentRepository            文档仓储
     * @param knowledgeBaseRepository       知识库仓储
     * @param transactionTemplate           编程式事务模板
     * @param chunkPhysicalDeleteService    切片物理删除原语
     * @param mediaImageCleanupService      媒体图片对象清理服务
     * @param kbAssetStoragePort            知识库对象资产存储访问端口
     * @param chunkEmbeddingApi             切片向量化契约（跨 BC 端口，实现位于 rag BC）
     */
    public ChunkApplicationService(ChunkRepository chunkRepository,
                                   ChunkRepresentationRepository chunkRepresentationRepository,
                                   DocumentRepository documentRepository,
                                   KnowledgeBaseRepository knowledgeBaseRepository,
                                   TransactionTemplate transactionTemplate,
                                   ChunkPhysicalDeleteService chunkPhysicalDeleteService,
                                   MediaImageCleanupService mediaImageCleanupService,
                                   KbAssetStoragePort kbAssetStoragePort,
                                   ChunkEmbeddingApi chunkEmbeddingApi) {
        this.chunkRepository = chunkRepository;
        this.chunkRepresentationRepository = chunkRepresentationRepository;
        this.documentRepository = documentRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.transactionTemplate = transactionTemplate;
        this.chunkPhysicalDeleteService = chunkPhysicalDeleteService;
        this.mediaImageCleanupService = mediaImageCleanupService;
        this.kbAssetStoragePort = kbAssetStoragePort;
        this.chunkEmbeddingApi = chunkEmbeddingApi;
    }

    /**
     * 新增切片（人工来源打标）：校验所属知识库与文档存在且知识库可写，落库后同事务写入
     * 1:1 派生表示并回写文档分块计数。
     * <p><b>序号由服务端分配</b>：客户端传入的序号一律忽略，新切片序号恒为该文档当前最大序号加一
     * （人工块因此恒位于文档分块序列末尾）。序号计算位于事务内「文档行锁之后」——同文档并发新增
     * 被行锁串行化，天然不触发 {@code uk_chunk_doc_seq} 唯一约束冲突，与整篇替换的行锁同一顺序、
     * 无交叉死锁。</p>
     * <p><b>来源标识恒为「人工新增」</b>（经 {@link Chunk#create} 工厂打标，写入即定）：
     * 人工块构造过程不产生任何图谱贡献，是人工删除入口的准入依据。</p>
     * <p>向量计算位于数据库事务之外（远程调用，事务规范 1.1）；向量计算失败即抛异常、数据库零写入。</p>
     *
     * @param command 新增切片命令（{@code sequence} 字段被忽略，序号由服务端计算）
     * @return 落库后的切片聚合根
     * @throws ResourceNotFoundException 知识库 / 文档不存在（404）
     * @throws DeepDataAgentException    内容形态非法或知识库处于删除流程（400）
     */
    public Chunk create(CreateChunkCommand command) {
        KnowledgeBase knowledgeBase = knowledgeBaseRepository.findById(command.kbId())
                .orElseThrow(() -> new ResourceNotFoundException("知识库不存在"));
        KnowledgeBaseValidator.validateNotDeleting(knowledgeBase);
        Document document = documentRepository.findById(command.documentId())
                .orElseThrow(() -> new ResourceNotFoundException("文档不存在"));
        if (!ObjectUtils.equals(document.kbId(), command.kbId())) {
            throw new DeepDataAgentException("文档不属于该知识库");
        }
        ChunkContentType contentType = parseContentTypeOrNull(command.chunkContentType());
        // 事务外：按切片正文取向量字面量（未配置嵌入模型返回 null，降级为仅写全文表示）；
        // 入参 sequence 刻意不消费——序号在事务内持锁后由服务端计算
        String vectorLiteral = chunkEmbeddingApi.embedLiteral(command.kbId(), command.chunkContent());
        return transactionTemplate.execute(status -> {
            // 文档行锁：与整篇替换同一把锁、同一顺序，串行化同文档并发新增，杜绝序号竞态
            documentRepository.findByIdForUpdate(command.documentId())
                    .orElseThrow(() -> new ResourceNotFoundException("文档不存在"));
            Integer maxSequence = chunkRepository.findMaxSequenceByDocumentId(command.documentId());
            int nextSequence = ObjectUtils.isEmpty(maxSequence) ? 0 : maxSequence + 1;
            Chunk created = Chunk.create(command.kbId(), command.documentId(), nextSequence,
                    command.tokens(), command.chunkContent(), command.originalItem(),
                    contentType, command.sourceFileName());
            Chunk persisted = chunkRepository.save(created);
            chunkRepresentationRepository.upsert(buildRepresentation(persisted, vectorLiteral), null);
            refreshDocumentChunkCount(persisted.documentId());
            return persisted;
        });
    }

    /**
     * 更新切片内容（人工修正解析结果）：事务外取向量后，于单事务内更新切片行、
     * UPSERT 1:1 派生表示并保持切片与表示同步。
     * <p>事务外预读一次切片行以取得库 / 文档身份用于组装派生表示；事务内再次读取以既有行锁语义
     * 应用变更，避免并发编辑互相覆盖。向量计算失败即抛异常、数据库零写入。</p>
     *
     * @param command 更新切片命令
     * @return 更新后的切片聚合根
     * @throws ResourceNotFoundException 切片不存在（404）
     * @throws DeepDataAgentException    切片内容为空（400）
     */
    public Chunk update(UpdateChunkCommand command) {
        // 事务外预读：取得库 / 文档身份（本阶段零写入）
        Chunk current = chunkRepository.findById(command.id())
                .orElseThrow(() -> new ResourceNotFoundException("切片不存在"));
        if (StringUtils.isBlank(command.chunkContent())) {
            throw new DeepDataAgentException("切片内容不能为空");
        }
        // 事务外：按新正文取向量字面量（未配置嵌入模型返回 null，降级为仅写全文表示）
        String vectorLiteral = chunkEmbeddingApi.embedLiteral(current.kbId(), command.chunkContent());
        return transactionTemplate.execute(status -> {
            Chunk latest = chunkRepository.findById(command.id())
                    .orElseThrow(() -> new ResourceNotFoundException("切片不存在"));
            Integer tokens = ObjectUtils.defaultIfNull(command.tokens(), latest.tokens());
            Chunk updated = chunkRepository.update(latest.withContent(command.chunkContent(), tokens));
            chunkRepresentationRepository.upsert(buildRepresentation(updated, vectorLiteral), null);
            return updated;
        });
    }

    /**
     * 人工删除单条切片（「Storage 先删、DB 后删」时序）。
     * <p>闸门校验（含来源准入）→ 同文档图片对象引用计数 → 无他引则事务外回收对象 → 删除原语事务。
     * 对象回收失败即抛业务异常终止本次删除（数据库零写入，用户重删自愈）。</p>
     *
     * @param command 删除切片命令（切片ID必填）
     * @throws DeepDataAgentException    切片ID为空（400）或目标为解析产生的切片（400，附引导文案）
     * @throws ResourceNotFoundException 切片 / 文档 / 知识库行不存在（404）
     * @throws ResourceConflictException 文档已进入删除链（409）
     */
    public void delete(DeleteChunkCommand command) {
        if (ObjectUtils.isEmpty(command) || ObjectUtils.isEmpty(command.id())) {
            throw new DeepDataAgentException("切片ID不能为空");
        }
        doDelete(List.of(command.id()));
    }

    /**
     * 人工批量删除切片（同一套闸门 + Storage 前置时序，服务端去重后按批清退）。
     * <p>去重保序后按 {@value #DELETE_BATCH_SIZE} 条/批切分，每批一个独立删除事务；
     * 任一批失败即终止剩余批次（异常上抛），已提交批次不回滚，由用户重删幂等续跑。
     * 混批（含解析产生的切片）在来源准入处整批拒绝。</p>
     *
     * @param command 批量删除切片命令（ID 集合去重后非空）
     * @throws DeepDataAgentException    ID 集合为空或全部为空白元素（400）；批内含解析产生的切片（400，附引导文案）
     * @throws ResourceNotFoundException 任一切片 / 文档 / 知识库行不存在（404）
     * @throws ResourceConflictException 任一所属文档已进入删除链（409）
     */
    public void deleteBatch(DeleteChunksCommand command) {
        if (ObjectUtils.isEmpty(command) || CollectionUtils.isEmpty(command.ids())) {
            throw new DeepDataAgentException("切片ID集合不能为空");
        }
        List<Long> chunkIds = new ArrayList<>(new LinkedHashSet<>(command.ids()));
        chunkIds.removeIf(ObjectUtils::isEmpty);
        if (CollectionUtils.isEmpty(chunkIds)) {
            throw new DeepDataAgentException("切片ID集合不能为空");
        }
        for (List<Long> batch : BatchSplitter.split(chunkIds, DELETE_BATCH_SIZE)) {
            doDelete(batch);
        }
    }

    /**
     * 单批人工删除的统一执行序（硬顺序）：闸门校验 → 来源准入校验 → 事务外图片对象回收 → 原语事务。
     * <p>入参集合已由调用方去重且非空。人工入口无用户身份上下文，operator 传 null
     * 由持久层回落系统缺省操作人。</p>
     *
     * @param chunkIds 本批待删除切片ID集合（去重、非空）
     * @throws DeepDataAgentException 批内含解析产生的切片（400，整批拒绝并附引导文案）
     */
    private void doDelete(List<Long> chunkIds) {
        Map<Long, List<Long>> chunkIdsByDocument = validateDeletionGate(chunkIds);
        validateManualSourceOnly(chunkIds);
        // Storage 先删：事务外回收本批独占引用的图片对象（失败即抛，数据库零写入）
        for (Map.Entry<Long, List<Long>> entry : chunkIdsByDocument.entrySet()) {
            recycleUnsharedMediaObjects(entry.getKey(), entry.getValue());
        }
        // DB 后删：原语两段式清退（事务外重建计算 → 事务内「图谱账本收敛与缓存回收 → 表示 →
        // 切片行 → 分块计数回写」）；事务边界由原语自持，本链调用处 MUST NOT 预先开启事务——
        // 组 6 起重建含 LLM 远程调用，落入本方法事务即违反事务规范 1.1（人工入口经来源准入后
        // 本批恒为人工块，原语推导结果天然是「跳过收敛」，零远程，无需调用方声明）
        chunkPhysicalDeleteService.deleteChunks(chunkIds, null, true);
    }

    /**
     * 人工删除来源准入校验：本批切片必须全部为「人工新增」来源，含任何「解析产生」的切片即整批拒绝。
     * <p>理由（分块引用完整性不变量的人工删除路径）：解析产生的切片确有图谱贡献，
     * 单独删除会使图谱两份账本永久引用已消失的分块标识；其「清理某个解析块」的需求由
     * 重新解析（整篇重建）或删除文档（删除链同事务收敛）承接。</p>
     * <p>投影缺失的行（并发下已物理消失）不构成本校验的拒绝依据——行已消失即无账本可失效，
     * 与既有存在性闸门的 404 口径一致。</p>
     *
     * @param chunkIds 本批待删除切片ID集合（去重、非空）
     * @throws DeepDataAgentException 批内含解析产生的切片（400，附引导文案）
     */
    private void validateManualSourceOnly(List<Long> chunkIds) {
        Map<Long, ChunkSource> sourceByChunk = chunkRepository.findSourcesByChunkIds(chunkIds);
        for (Long chunkId : chunkIds) {
            if (ChunkSource.PARSED.equals(sourceByChunk.get(chunkId))) {
                throw new DeepDataAgentException(ERROR_PARSED_CHUNK_DELETION_FORBIDDEN);
            }
        }
    }

    /**
     * 人工删除入口状态闸门（切片删除入口的状态闸门）。
     * <p>切片行缺失（已物理删）→ 404；所属文档行缺失（已收口）→ 404；知识库行缺失 → 404；
     * 知识库非 ACTIVE（DELETING / DELETE_FAILED）→ 拒绝；文档进入删除链
     * （DELETING / DELETE_FAILED）→ 409 拒绝。校验通过后按文档分组返回本批切片，
     * 分组保持切片入参顺序，使多文档批次的处理顺序稳定。</p>
     *
     * @param chunkIds 本批待删除切片ID集合（去重、非空）
     * @return 文档ID → 本批该文档下待删切片ID 映射（文档首现顺序）
     * @throws ResourceNotFoundException 切片 / 文档 / 知识库不存在（404）
     * @throws DeepDataAgentException    知识库已进入删除流程（400）
     * @throws ResourceConflictException 文档已进入删除链（409）
     */
    private Map<Long, List<Long>> validateDeletionGate(List<Long> chunkIds) {
        Map<Long, Long> documentIdByChunk = chunkRepository.findDocumentIdsByChunkIds(chunkIds);
        Map<Long, List<Long>> chunkIdsByDocument = new LinkedHashMap<>();
        for (Long chunkId : chunkIds) {
            Long documentId = documentIdByChunk.get(chunkId);
            if (ObjectUtils.isEmpty(documentId)) {
                throw new ResourceNotFoundException("切片不存在或已被删除");
            }
            chunkIdsByDocument.computeIfAbsent(documentId, key -> new ArrayList<>()).add(chunkId);
        }
        for (Long documentId : chunkIdsByDocument.keySet()) {
            Document document = documentRepository.findById(documentId)
                    .orElseThrow(() -> new ResourceNotFoundException("文档不存在"));
            KnowledgeBase knowledgeBase = knowledgeBaseRepository.findById(document.kbId())
                    .orElseThrow(() -> new ResourceNotFoundException("知识库不存在"));
            KnowledgeBaseValidator.validateNotDeleting(knowledgeBase);
            DocumentValidator.validateChunkOperationAllowed(document);
        }
        return chunkIdsByDocument;
    }

    /**
     * 回收本批切片独占引用的多模态图片对象（同文档引用计数，事务前置步骤）。
     * <p>口径：以本批所属文档的全部存活图片引用为账本——同对象若仍被本批之外的存活切片引用则跳过
     * （不删对象，直接进入事务）；仅无他引的对象在事务外回收。引用缺失 / 非法 JSON 宽松跳过
     * （文本切片属预期）。回收失败由 {@link MediaImageCleanupService#recycleChunkMediaObject}
     * 抛业务异常终止本次删除，数据库零写入；对象不存在视为成功（重删自愈半态）。</p>
     *
     * @param documentId  本批切片所属文档ID
     * @param removingIds 本批该文档下待删除切片ID集合
     */
    private void recycleUnsharedMediaObjects(Long documentId, List<Long> removingIds) {
        Map<Long, String> mediaReferenceByChunk = chunkRepository.findMediaReferencesByDocumentId(documentId);
        if (CollectionUtils.isEmpty(mediaReferenceByChunk)) {
            return;
        }
        Set<Long> removing = new HashSet<>(removingIds);
        // 存活引用（本批之外）：决定对象是否仍被共享；本批内的相互引用随批同删，不构成他引
        Set<S3File> survivingReferences = new HashSet<>();
        // 待回收候选（本批切片引用的去重集合，保持首次出现顺序，保证多对象回收顺序稳定）
        Set<S3File> removingReferences = new LinkedHashSet<>();
        for (Map.Entry<Long, String> entry : mediaReferenceByChunk.entrySet()) {
            S3File reference = mediaImageCleanupService.parseMediaReference(entry.getValue());
            if (ObjectUtils.isEmpty(reference)) {
                continue;
            }
            if (removing.contains(entry.getKey())) {
                removingReferences.add(reference);
            } else {
                survivingReferences.add(reference);
            }
        }
        for (S3File reference : removingReferences) {
            if (survivingReferences.contains(reference)) {
                log.info("同文档仍有存活切片引用图片对象，跳过对象回收, documentId={}, objectKey={}",
                        documentId, reference.objectKey());
                continue;
            }
            mediaImageCleanupService.recycleChunkMediaObject(reference);
        }
    }

    /**
     * 按主键查询切片详情。
     *
     * @param id 切片ID
     * @return 切片聚合根
     * @throws ResourceNotFoundException 切片不存在（404）
     */
    public Chunk get(Long id) {
        return chunkRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("切片不存在"));
    }

    /**
     * 打开切片媒体图片内容流（在线预览只读代理，全程事务外）。
     * <p>前置闸门：切片不存在 404；所属知识库缺失或非 ACTIVE 回传业务错误且不触碰对象存储；
     * 切片未登记媒体引用或引用对象缺失回传 404。内容类型按对象键扩展推断，
     * 不可识别兜底为 {@value #DEFAULT_MEDIA_CONTENT_TYPE}。</p>
     *
     * @param id 切片ID
     * @return 媒体内容结果（内容类型、字节数、内容流；流由调用方负责关闭）
     * @throws ResourceNotFoundException 切片不存在 / 无媒体引用 / 媒体对象缺失（404）
     * @throws DeepDataAgentException    所属知识库不可用（400）
     */
    public ChunkMediaResult openMedia(Long id) {
        Chunk chunk = chunkRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("切片不存在"));
        KnowledgeBase knowledgeBase = knowledgeBaseRepository.findById(chunk.kbId())
                .orElseThrow(() -> new DeepDataAgentException(ERROR_KB_UNAVAILABLE));
        if (!knowledgeBase.isActive()) {
            throw new DeepDataAgentException(ERROR_KB_UNAVAILABLE);
        }
        S3File mediaReference = mediaImageCleanupService.parseMediaReference(chunk.s3File());
        if (ObjectUtils.isEmpty(mediaReference)) {
            throw new ResourceNotFoundException("切片无媒体图片引用");
        }
        KbAssetStoragePort.OpenedObject opened = kbAssetStoragePort.open(mediaReference.objectKey())
                .orElseThrow(() -> new ResourceNotFoundException("切片媒体图片对象不存在"));
        return new ChunkMediaResult(resolveMediaContentType(mediaReference.objectKey()),
                opened.size(), opened.content());
    }

    /**
     * 按对象键扩展名推断媒体图片内容类型。
     *
     * @param objectKey 媒体对象键
     * @return 内容类型（扩展缺失或不可识别时为 {@value #DEFAULT_MEDIA_CONTENT_TYPE}）
     */
    private String resolveMediaContentType(String objectKey) {
        String extension = StringUtils.lowerCase(
                StringUtils.substringAfterLast(objectKey, FILE_EXTENSION_SEPARATOR));
        if (StringUtils.isBlank(extension)) {
            return DEFAULT_MEDIA_CONTENT_TYPE;
        }
        return MEDIA_CONTENT_TYPE_BY_EXTENSION.getOrDefault(extension, DEFAULT_MEDIA_CONTENT_TYPE);
    }

    /**
     * 分页查询知识库内的切片列表。
     *
     * @param query 列表查询条件
     * @return 切片列表，按文档与序号升序
     */
    public List<Chunk> list(ListChunkQuery query) {
        return chunkRepository.findByKbId(query.kbId(), query.documentId(), query.sequence(),
                query.keyword(), query.page(), query.size());
    }

    /**
     * 统计知识库内切片列表命中总数。
     *
     * @param kbId       知识库ID
     * @param documentId 文档ID，可为空
     * @param sequence   切片序号，可为空
     * @param keyword    切片内容关键字，可为空
     * @return 命中记录数
     */
    public long count(Long kbId, Long documentId, Integer sequence, String keyword) {
        return chunkRepository.countByKbId(kbId, documentId, sequence, keyword);
    }

    /**
     * 分页查询指定文档下的切片列表。
     *
     * @param documentId 文档ID
     * @param page       页码，从 1 开始
     * @param size       每页条数
     * @return 切片列表，按序号升序
     */
    public List<Chunk> listByDocument(Long documentId, int page, int size) {
        int safePage = page < DEFAULT_LIST_PAGE ? DEFAULT_LIST_PAGE : page;
        int safeSize = size < 1 ? DEFAULT_LIST_SIZE : size;
        return chunkRepository.findByDocumentId(documentId, safePage, safeSize);
    }

    /**
     * 整篇替换回写：清空该文档旧切片及其派生表示，按草稿重建，并在同一事务内原子更新
     * 文档分块计数。
     * <p><b>本方法不写文档状态</b>：切片写入成功只表示切片已就绪，其后的实体关系抽取与
     * 图合并仍属摄入管线未完成部分，成功终态由摄入管线收尾经
     * {@link #markProcessed(Long)} 统一写入。</p>
     * <p>由 RAG 上下文的摄入管线通过 {@code ChunkBatchWriter} 契约调用。知识库侧事务内
     * <b>仅包含自有表 CRUD</b>，向量与全文表示的字面量由入参草稿携带，不发起任何远程调用。</p>
     * <p><b>整篇替换不区分分块来源（显式语义）</b>：清空的是该文档<b>全部</b>旧切片（含人工新增
     * 的切片，来源标识 {@code MANUAL} 不豁免）；重建切片一律打「解析产生」标（经
     * {@link Chunk#createForRebuild} 工厂），序号按草稿给定的解析顺序连续分配。</p>
     * <p>入参校验（文档缺失、切片集合为空、序号重复）在事务开启前完成，
     * 任一不合法即整批拒绝且原切片保持不变；切片数量不设上限，事务内批量写入由
     * {@code saveBatch} 分片承担（事务规范 3.3）；派生清退与重建在同一事务内原子提交。</p>
     *
     * <p>处理中前置：整篇替换仅允许由「出队领取成功」的
     * 摄入执行方发起，文档状态非 {@code PROCESSING}（已被本实例启动恢复置 FAILED、被删除链置 DELETING、
     * 被重新解析置 PENDING 等）一律 409 拒绝，是「脱离处理中状态的迟到回写不得复活覆盖」的
     * 数据库层收口。</p>
     *
     * @param documentId 目标文档ID
     * @param drafts     切片草稿集合，非空（数量不设上限，批量写入由 saveBatch 分片承担）
     * @param operatorId 操作人ID，可空（落 updated_by / created_by）
     *
     * @return 本批切片的「序号 → 落库主键」映射（与事务内 saveBatch 回读结果同源，恒非 null）；
     *         供摄入管线直取主键完成溯源回填，消费方 MUST NOT 再为此目的二次回查数据库
     *
     * @throws ResourceNotFoundException 文档不存在（404）
     * @throws DeepDataAgentException    入参非法或知识库处于删除流程（400）
     * @throws ResourceConflictException 文档已进入删除链或已脱离处理中状态（409）
     */
    public Map<Integer, Long> replaceForDocument(Long documentId, List<ChunkDraft> drafts, Long operatorId) {
        if (ObjectUtils.isEmpty(documentId)) {
            throw new DeepDataAgentException("文档ID不能为空");
        }
        if (ObjectUtils.isEmpty(drafts)) {
            throw new DeepDataAgentException("切片草稿集合不能为空");
        }
        validateSequenceUnique(drafts);
        String operator = auditOperator(operatorId);
        Map<Integer, Long> sequenceToChunkId = transactionTemplate.execute(status -> {
            Document document = documentRepository.findByIdForUpdate(documentId)
                    .orElseThrow(() -> new ResourceNotFoundException("文档不存在"));
            KnowledgeBase knowledgeBase = knowledgeBaseRepository.findById(document.kbId())
                    .orElseThrow(() -> new ResourceNotFoundException("知识库不存在"));
            KnowledgeBaseValidator.validateNotDeleting(knowledgeBase);
            // DELETING / DELETE_FAILED 两态闸门：删除链已启动、切片正被分批清退，禁止任何摄入回写，
            // 避免在飞管线的落库与删除链清退互相覆盖（双保险）
            validateNotInDeletionChain(document);
            // 处理中前置：状态非 PROCESSING（已被超时回收 / 重新置为待处理）拒绝僵尸回写
            validateProcessing(document);
            // 先清退旧切片及其 1:1 派生表示，再按草稿重建
            chunkRepository.deleteByDocumentId(documentId);
            chunkRepresentationRepository.deleteByDocumentId(documentId);
            List<Chunk> rebuilt = new ArrayList<>(drafts.size());
            for (ChunkDraft draft : drafts) {
                // 整篇重建写入即最新态，重建信号一律置净，不触发下游重复重建；
                // 图片对象引用自 meta 提取写一等列 s3_file（过渡期与 meta 双写）
                rebuilt.add(Chunk.createForRebuild(document.kbId(), documentId, draft.sequence(),
                        draft.tokens(), draft.content(), draft.metadata(),
                        parseContentTypeOrNull(draft.chunkContentType()), document.fileName(),
                        extractS3FileReference(draft.metadata())));
            }
            List<Chunk> persisted = chunkRepository.saveBatch(rebuilt, operator);
            chunkRepresentationRepository.saveBatch(buildRepresentations(document.kbId(), documentId,
                    persisted, drafts), operator);
            // 落库只回写分块计数并清除残留的失败原因，文档状态零触碰：
            // 切片写入成功不等于摄入完成，其后的实体关系抽取与图合并由管线收尾统一置终态
            documentRepository.update(document.withChunkCount(persisted.size())
                    .withErrorMessage(null));
            // 主键在落库事务内即回传（与 saveBatch 回读结果同源）：消费方无需再回查数据库取映射
            Map<Integer, Long> identityMap = new LinkedHashMap<>(persisted.size());
            for (Chunk chunk : persisted) {
                identityMap.put(chunk.sequence(), chunk.id());
            }
            return identityMap;
        });
        return sequenceToChunkId;
    }

    /**
     * 出队领取：原子条件更新 PENDING → PROCESSING 并清除失败原因。
     * <p>内存队列消费方取出任务时调用，命中即确认独占开始解析；未命中（文档已被删除链置 DELETING、
     * 已被本实例启动恢复置 FAILED 或记录不存在）返回 false，调用方静默丢弃该任务。
     * 单条条件 UPDATE 即原子完成，无需事务包裹；跨实例的任务互斥由本条条件更新自身承担
     * （并发领取下仅一方命中），故不存在租约、领取者标识与有效期概念。</p>
     *
     * @param documentId 目标文档ID，必填
     * @return true 表示领取成功（文档已进入 PROCESSING）；false 表示条件更新未命中
     * @throws DeepDataAgentException 文档ID为空（400）
     */
    public boolean markProcessing(Long documentId) {
        if (ObjectUtils.isEmpty(documentId)) {
            throw new DeepDataAgentException("文档ID不能为空");
        }
        // errorMessage 传 null：transitStatus 无条件写 error_message，即领取成功同批清除残留失败原因
        return documentRepository.transitStatus(documentId, MARK_PROCESSING_FROM_STATUSES,
                DocumentStatus.PROCESSING, null);
    }

    /**
     * 摄入成功终态写入：以 {@code PROCESSING → PROCESSED} 条件流转把文档置为可用
     * （解析与图谱构建全部完成）并清除残留的失败原因。
     * <p>由 RAG 上下文摄入管线在收尾时通过 {@code ChunkBatchWriter} 契约调用，位于切片落库、
     * 实体关系抽取与图合并全部成功之后——切片落库本身不写状态，成功终态的唯一写入点是本方法。</p>
     * <p>条件前置即并发收口：文档已被删除链置 {@code DELETING / DELETE_FAILED}、已被本实例启动
     * 恢复置 {@code FAILED}、已被用户重新解析置 {@code PENDING} 或行已不存在时，条件更新零行命中，
     * 调用方据此静默放弃本次写入（状态由持有方负责，不回退、不覆盖）。
     * 单条条件 UPDATE 即原子完成，无需事务包裹。</p>
     *
     * @param documentId 目标文档ID，必填
     * @return {@code true} 表示命中并已置 {@code PROCESSED}；{@code false} 表示条件更新未命中
     *         （状态已被删除链 / 启动恢复 / 用户操作推进，或文档行已不存在）
     * @throws DeepDataAgentException 文档ID为空（400）
     */
    public boolean markProcessed(Long documentId) {
        if (ObjectUtils.isEmpty(documentId)) {
            throw new DeepDataAgentException("文档ID不能为空");
        }
        // errorMessage 传 null：transitStatus 无条件写 error_message，即成功终态同批清除残留失败原因
        return documentRepository.transitStatus(documentId, MARK_PROCESSED_FROM_STATUSES,
                DocumentStatus.PROCESSED, null);
    }

    /**
     * 非终态批量收敛：把全部非终态（PENDING / PROCESSING）未删除文档一次性置为 FAILED 并回写失败原因
     * <p><b>保留能力，当前无生产调用点</b>：启动恢复链路已改按本实例在飞注册表键逐条读取残留，
     * 经数据库权威状态一致性校验后条件置态，本方法不再被启动链路使用，也不再自称「启动清理专用」。
     * 原崩溃残留收敛语义保留：进程崩溃重启后内存队列丢失，DB 中冻结的两态文档均无对应任务，
     * 统一收敛为 FAILED；不重建队列、不自动重跑，由用户手动重新解析。
     * 单条批量 UPDATE，天然幂等（无命中影响 0 行）。</p>
     *
     * @param errorMessage 失败原因描述，非空白
     * @return 实际置为 FAILED 的文档数（0 表示无残留）
     * @throws DeepDataAgentException 失败原因为空白（400）
     */
    public int failNonTerminal(String errorMessage) {
        if (StringUtils.isBlank(errorMessage)) {
            throw new DeepDataAgentException("失败原因不能为空");
        }
        return documentRepository.failNonTerminal(NON_TERMINAL_STATUSES, DocumentStatus.FAILED, errorMessage);
    }

    /**
     * 标记文档摄入失败（CAS）：仅当当前状态为 PROCESSING 时置为 FAILED 并回写失败原因，
     * 避免覆盖用户重新解析（PENDING）等更新的状态。
     * <p>单条条件 UPDATE 即原子完成；CAS 未命中视为状态已被更新，静默忽略。</p>
     *
     * @param documentId   目标文档ID，必填
     * @param errorMessage 失败原因描述，非空白
     * @throws DeepDataAgentException 文档ID为空或失败原因为空白（400）
     */
    public void markFailed(Long documentId, String errorMessage) {
        if (ObjectUtils.isEmpty(documentId)) {
            throw new DeepDataAgentException("文档ID不能为空");
        }
        if (StringUtils.isBlank(errorMessage)) {
            throw new DeepDataAgentException("失败原因不能为空");
        }
        documentRepository.transitStatus(documentId, Set.of(DocumentStatus.PROCESSING),
                DocumentStatus.FAILED, errorMessage);
    }

    /**
     * 校验文档未进入删除链：DELETING / DELETE_FAILED 两态一律拒绝切片回写
     * （DELETED 常量已移除，行缺失由上游行锁读取按 404 前置兜底）。
     * <p>调用时文档行锁已持有，拒绝后事务回滚，既有切片与文档状态不受影响。</p>
     *
     * @param document 目标文档（已行锁，非空）
     * @throws ResourceConflictException 文档已进入删除链（409）
     */
    private void validateNotInDeletionChain(Document document) {
        if (DELETION_CHAIN_STATUSES.contains(document.status())) {
            throw new ResourceConflictException("文档「" + document.fileName() + "」已进入删除流程，禁止切片回写");
        }
    }

    /**
     * 校验文档处于处理中状态：切片整篇替换仅允许由出队领取成功后的摄入执行方（PROCESSING）发起。
     * <p>调用时文档行锁已持有；状态非 PROCESSING（已被本实例启动恢复置 FAILED、被删除链置 DELETING、
     * 被重新解析置 PENDING 等）说明发起方已失去独占资格，拒绝后事务回滚，既有切片不受影响。</p>
     *
     * @param document 目标文档（已行锁，非空）
     * @throws ResourceConflictException 文档已脱离处理中状态（409）
     */
    private void validateProcessing(Document document) {
        if (!ObjectUtils.equals(DocumentStatus.PROCESSING, document.status())) {
            throw new ResourceConflictException("文档「" + document.fileName()
                    + "」已脱离处理中状态（可能已被本实例启动恢复或删除链改写），拒绝切片回写");
        }
    }

    /**
     * 从切片草稿元数据 JSON 中提取多模态图片对象存储引用，组装为 {@code {"objectKey"}} 形态
     * （对齐 {@code document.s3_file}，桶概念已退役）。
     * <p>宽松口径：元数据为空、非法 JSON、缺引用键或键值为空白一律返回 null（文本切片属预期），
     * 绝不因提取失败中断整批回写；缺列数据由过渡期 {@code original_item} 双写兜底。</p>
     *
     * @param metadata 切片草稿元数据 JSON（{@link ChunkDraft#metadata()}），可为空
     * @return s3_file 引用 JSON 字符串；无有效引用返回 null
     */
    private String extractS3FileReference(String metadata) {
        if (StringUtils.isBlank(metadata)) {
            return null;
        }
        try {
            JsonNode root = S3_REFERENCE_MAPPER.readTree(metadata);
            String objectKey = textValue(root, META_KEY_MEDIA_OBJECT_KEY);
            if (StringUtils.isBlank(objectKey)) {
                return null;
            }
            ObjectNode reference = S3_REFERENCE_MAPPER.createObjectNode();
            reference.put(FIELD_S3_OBJECT_KEY, objectKey);
            return S3_REFERENCE_MAPPER.writeValueAsString(reference);
        } catch (Exception e) {
            log.warn("切片元数据解析图片对象引用失败，s3_file 置空（meta 双写兜底）, metadata={}", metadata, e);
            return null;
        }
    }

    /**
     * 读取 JSON 节点下的文本字段值：非对象节点、字段缺失或非文本节点均返回 null。
     *
     * @param root  JSON 根节点，可为空
     * @param field 字段名
     * @return 字段文本值；不可得返回 null
     */
    private String textValue(JsonNode root, String field) {
        if (ObjectUtils.isEmpty(root) || !root.isObject()) {
            return null;
        }
        JsonNode node = root.get(field);
        return ObjectUtils.isEmpty(node) || !node.isTextual() ? null : node.asText();
    }

    /**
     * 校验一次回写内切片序号不重复。
     *
     * @param drafts 切片草稿集合
     * @throws DeepDataAgentException 存在重复序号（400）
     */
    private void validateSequenceUnique(List<ChunkDraft> drafts) {
        Set<Integer> sequences = new HashSet<>();
        for (ChunkDraft draft : drafts) {
            if (!sequences.add(draft.sequence())) {
                throw new DeepDataAgentException("切片序号重复：" + draft.sequence());
            }
        }
    }

    /**
     * 依据落库后的切片（含主键）与原始草稿，组装 1:1 派生表示集合。
     * <p>通过 sequence 将草稿的向量字面量与切片原文对齐到已落库切片的主键
     * （全文 tsvector 由仓储写入 SQL 现算，草稿不再携带预分词字面量）。</p>
     *
     * @param kbId       知识库ID
     * @param documentId 文档ID
     * @param persisted  已落库切片集合（含主键）
     * @param drafts     原始草稿集合
     * @return 携带有效派生表示的切片表示集合
     */
    private List<ChunkRepresentation> buildRepresentations(Long kbId, Long documentId,
                                                           List<Chunk> persisted, List<ChunkDraft> drafts) {
        Map<Integer, ChunkDraft> draftBySequence = new HashMap<>(drafts.size());
        for (ChunkDraft draft : drafts) {
            draftBySequence.put(draft.sequence(), draft);
        }
        List<ChunkRepresentation> representations = new ArrayList<>(persisted.size());
        for (Chunk chunk : persisted) {
            ChunkDraft draft = draftBySequence.get(chunk.sequence());
            if (ObjectUtils.isEmpty(draft)) {
                continue;
            }
            ChunkRepresentation representation = ChunkRepresentation.create(kbId, documentId, chunk.id(),
                    draft.embeddingVector(), draft.content());
            if (!representation.isEmptyRepresentation()) {
                representations.add(representation);
            }
        }
        return representations;
    }

    /**
     * 将操作人ID转换为审计字段字符串，为空时返回 null（由持久化层回落为系统默认操作人）。
     *
     * @param operatorId 操作人ID
     * @return 审计操作人字符串，可为 null
     */
    private String auditOperator(Long operatorId) {
        return ObjectUtils.isEmpty(operatorId) ? null : String.valueOf(operatorId);
    }

    /**
     * 重新统计并回写文档的分块数量。
     *
     * @param documentId 文档ID
     */
    private void refreshDocumentChunkCount(Long documentId) {
        if (ObjectUtils.isEmpty(documentId)) {
            return;
        }
        documentRepository.findById(documentId).ifPresent(document -> {
            long total = chunkRepository.countByDocumentId(documentId);
            int chunkCount = (int) Math.min(total, Integer.MAX_VALUE);
            documentRepository.update(document.withChunkCount(chunkCount));
        });
    }

    /**
     * 组装单条切片的 1:1 派生表示（人工新增 / 编辑路径）。
     * <p>向量字面量由事务外的向量化契约产出，可为空（未配置嵌入模型时的降级口径，此时仅落全文行）；
     * 切片原文承载全文表示的现算输入（tsvector 由写入 SQL 内 {@code to_tsvector} 现算）。</p>
     *
     * @param chunk         已落库 / 已更新的切片（含主键，非空）
     * @param vectorLiteral 向量字面量，可为空
     * @return 切片派生表示
     */
    private ChunkRepresentation buildRepresentation(Chunk chunk, String vectorLiteral) {
        return ChunkRepresentation.create(chunk.kbId(), chunk.documentId(), chunk.id(),
                vectorLiteral, chunk.chunkContent());
    }

    /**
     * 解析切片内容形态：为空返回 null（由聚合根兜底为 TEXT），取值非法抛出 400。
     *
     * @param chunkContentType 内容形态名称
     * @return 内容形态枚举，可为 null
     * @throws DeepDataAgentException 取值非法（400）
     */
    private ChunkContentType parseContentTypeOrNull(String chunkContentType) {
        if (StringUtils.isBlank(chunkContentType)) {
            return null;
        }
        String normalized = chunkContentType.trim().toUpperCase();
        for (ChunkContentType type : ChunkContentType.values()) {
            if (StringUtils.equals(type.name(), normalized)) {
                return type;
            }
        }
        throw new DeepDataAgentException("不支持的切片内容形态：" + chunkContentType);
    }
}
