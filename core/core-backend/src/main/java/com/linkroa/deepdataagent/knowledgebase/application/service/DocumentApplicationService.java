package com.linkroa.deepdataagent.knowledgebase.application.service;

import com.linkroa.deepdataagent.knowledgebase.api.DocumentCleanupTaskSubmitter;
import com.linkroa.deepdataagent.knowledgebase.api.InFlightTaskRegistry;
import com.linkroa.deepdataagent.knowledgebase.api.InFlightTaskType;
import com.linkroa.deepdataagent.knowledgebase.api.IngestionCancellationApi;
import com.linkroa.deepdataagent.knowledgebase.api.IngestionTaskSubmitter;
import com.linkroa.deepdataagent.knowledgebase.application.command.DeleteDocumentCommand;
import com.linkroa.deepdataagent.knowledgebase.application.command.ReparseDocumentCommand;
import com.linkroa.deepdataagent.knowledgebase.application.command.UploadDocumentCommand;
import com.linkroa.deepdataagent.knowledgebase.application.port.KbAssetStoragePort;
import com.linkroa.deepdataagent.knowledgebase.application.query.ListDocumentQuery;
import com.linkroa.deepdataagent.knowledgebase.application.result.DedupHit;
import com.linkroa.deepdataagent.knowledgebase.application.result.DocumentContentResult;
import com.linkroa.deepdataagent.knowledgebase.application.result.UploadDocumentResult;
import com.linkroa.deepdataagent.knowledgebase.application.validation.ChunkStrategyConfigValidator;
import com.linkroa.deepdataagent.knowledgebase.application.validation.DocumentValidator;
import com.linkroa.deepdataagent.knowledgebase.application.validation.KnowledgeBaseValidator;
import com.linkroa.deepdataagent.knowledgebase.domain.model.DedupPolicyConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Document;
import com.linkroa.deepdataagent.knowledgebase.domain.model.KnowledgeBase;
import com.linkroa.deepdataagent.knowledgebase.domain.model.S3File;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DedupConflictAction;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentStatus;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FileType;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.ChunkRepository;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.DocumentRepository;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.KnowledgeBaseRepository;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;
import org.springframework.util.unit.DataSize;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 文档应用服务（上传登记 / 列表 / 详情 / 删除 / 重新解析 / 原文内容代理）。
 * <p>上传为单次调用：本服务接收文件字节，先于事务外经 {@link KbAssetStoragePort} 落对象存储，
 * 再以短事务登记元信息并置为 PENDING；实际解析、切片与索引构建由 RAG 上下文的异步摄入任务接管；
 * 原文预览 / 下载经同一存储访问端口代理回传，本服务不落任何磁盘文件。</p>
 */
@Service
public class DocumentApplicationService {

    private static final Logger log = LoggerFactory.getLogger(DocumentApplicationService.class);

    /** 知识库 RAG 引擎配置 JSON 中的分块策略字段名 */
    private static final String FIELD_CHUNK_STRATEGY = "chunkStrategy";

    /** 文档存储引用 JSON 中的对象键字段名 */
    private static final String FIELD_S3_OBJECT_KEY = "objectKey";

    /** 内容类型兜底值：文件类型缺失或不可识别时使用二进制流 */
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    /** 文件类型 → 内容类型映射（原文代理不再读存储侧元数据，按业务登记的文档类型推断） */
    private static final Map<FileType, String> CONTENT_TYPE_BY_FILE_TYPE = Map.ofEntries(
            Map.entry(FileType.PDF, "application/pdf"),
            Map.entry(FileType.DOC, "application/msword"),
            Map.entry(FileType.DOCX,
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry(FileType.XLS, "application/vnd.ms-excel"),
            Map.entry(FileType.XLSX,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry(FileType.PPT, "application/vnd.ms-powerpoint"),
            Map.entry(FileType.PPTX,
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry(FileType.TXT, "text/plain"),
            Map.entry(FileType.MD, "text/markdown"),
            Map.entry(FileType.CSV, "text/csv"),
            Map.entry(FileType.HTML, "text/html"),
            Map.entry(FileType.PNG, "image/png"),
            Map.entry(FileType.JPG, "image/jpeg"),
            Map.entry(FileType.JPEG, "image/jpeg"));

    /** 源文件对象键命名空间段：与媒体图片资产共用 {@code rag/{kbId}/} 前缀根，整库清退可一并覆盖 */
    private static final String SOURCE_OBJECT_NAMESPACE = "rag";

    /** 源文件对象键目录段：与 {@code {documentId}/images} 媒体目录区隔，避免命名空间交叉 */
    private static final String SOURCE_OBJECT_DIRECTORY = "source";

    /** 对象键路径分隔符 */
    private static final String PATH_SEPARATOR = "/";

    /** 对象键扩展名分隔符 */
    private static final String FILE_EXTENSION_SEPARATOR = ".";

    /** 所属知识库不可用时回传给内容访问端点的业务错误消息 */
    private static final String ERROR_KB_UNAVAILABLE = "知识库不可用，禁止访问文档内容";

    /** 分块策略 JSON 解析器（无状态，进程内共享） */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 解析任务提交失败的回写原因：落库已提交但入队未成功时置 FAILED，闭合 PENDING 孤儿窗口 */
    private static final String INGESTION_SUBMIT_FAILED_MESSAGE = "解析任务提交失败";

    /** 删除链失败留痕前缀：标识 DELETE_FAILED 由删除链引入，携带失败步骤与本批切片信息便于定位 */
    private static final String DELETE_FAILED_MESSAGE_PREFIX = "[DELETE-FAILED]";

    /** 删除失败留痕 errorMessage 截断上限，防止超长批次信息溢出错误信息列 */
    private static final int DELETE_ERROR_MESSAGE_MAX_LENGTH = 500;

    /** 删除链受理源态集合（CAS 前置）：五源态→DELETING；DELETING 行不入源态集，
     * 由受理侧按「在飞幂等 / 崩溃遗留重触发」单独处置 */
    private static final Set<DocumentStatus> DELETE_ACCEPT_FROM_STATUSES = Set.of(
            DocumentStatus.PENDING, DocumentStatus.PROCESSING, DocumentStatus.PROCESSED,
            DocumentStatus.FAILED, DocumentStatus.DELETE_FAILED);

    /** 清退步骤标识：在飞登记（登记先于投递，失败按失败兜底置 DELETE_FAILED） */
    private static final String DELETE_STEP_REGISTER = "in_flight_register";

    /** 清退步骤标识：掐灭等待（等待在飞摄入 worker 退出，事务外） */
    private static final String DELETE_STEP_CANCEL_WAIT = "cancel_wait";

    /** 清退步骤标识：批取（事务外只读查询） */
    private static final String DELETE_STEP_BATCH_FETCH = "batch_fetch";

    /** 清退步骤标识：切片批物理清退原语（表示 → 切片 → 图谱收敛在原语内固定序完成） */
    private static final String DELETE_STEP_CHUNK_BATCH = "chunk_batch_cleanup";

    /** 清退步骤标识：媒体图片对象清理（对象存储 IO，事务外执行，失败进重删续跑） */
    private static final String DELETE_STEP_MEDIA_CLEANUP = "media_cleanup";

    /** 清退步骤标识：文档源文件对象定点清理（对象存储 IO，事务外执行，失败进重删续跑） */
    private static final String DELETE_STEP_SOURCE_FILE_CLEANUP = "source_file_cleanup";

    /** 清退步骤标识：文档行收口 */
    private static final String DELETE_STEP_FINISH = "finish_delete";

    /** 单文件大小上限，唯一来源为 multipart 上传配置（与存储侧落盘约束天然一致） */
    @Value("${spring.servlet.multipart.max-file-size}")
    private DataSize maxFileSize;

    /** 单批清退切片条数（每批独立事务，符合事务规范 500~1000 条/批） */
    @Value("${app.knowledge-base.document-delete.cleanup-batch-size:500}")
    private int deleteCleanupBatchSize;

    /** 等待在飞摄入 worker 退出确认的上限（秒），超时 WARN 留痕后继续清退 */
    @Value("${app.knowledge-base.document-delete.cancel-wait-timeout-seconds:30}")
    private long cancelWaitTimeoutSeconds;

    /** 文档仓储（文档行锁读取、状态流转与计数回写） */
    private final DocumentRepository documentRepository;

    /** 切片仓储（分块计数与切片清退辅助） */
    private final ChunkRepository chunkRepository;

    /** 知识库仓储（库级闸门校验） */
    private final KnowledgeBaseRepository knowledgeBaseRepository;

    /** 文档判重服务（同名 / 同内容哈希两轴预检与处置） */
    private final DocumentDedupService documentDedupService;

    /** 知识库对象资产存储访问端口（源文件读写与删除） */
    private final KbAssetStoragePort kbAssetStoragePort;

    /** 媒体图片对象清理服务：删除链与换版清退复用的「按前缀清空」原语 */
    private final MediaImageCleanupService mediaImageCleanupService;

    /** 编程式事务模板（登记 / 删除受理 / 重新解析的精确事务边界） */
    private final TransactionTemplate transactionTemplate;

    /** 在飞摄入任务掐灭端口（RAG 上下文实现）：删除链清退前等待在飞摄入 worker 退出 */
    private final IngestionCancellationApi ingestionCancellationApi;

    /** 摄入解析任务提交契约（RAG 上下文实现，委托内存摄入队列）：落库 / 重新解析事务提交后提交解析任务 */
    private final IngestionTaskSubmitter ingestionTaskSubmitter;

    /** 切片物理删除原语：文档链每批复用，REQUIRED 并入调用方批事务 */
    private final ChunkPhysicalDeleteService chunkPhysicalDeleteService;

    /** 文档清退任务提交端口（rag BC 委托删除清退虚拟线程执行器实现，端口方向 KB → api → rag） */
    private final DocumentCleanupTaskSubmitter documentCleanupTaskSubmitter;

    /** 在飞任务注册表（rag BC 实现）：任务投递前登记在飞凭据、状态收敛后移除成员，供本实例启动恢复收敛悬挂任务 */
    private final InFlightTaskRegistry inFlightTaskRegistry;

    /**
     * 构造文档应用服务。
     *
     * @param documentRepository           文档仓储
     * @param chunkRepository              切片仓储
     * @param knowledgeBaseRepository      知识库仓储
     * @param documentDedupService         文档判重服务
     * @param kbAssetStoragePort           知识库对象资产存储访问端口
     * @param mediaImageCleanupService     媒体图片对象清理服务
     * @param transactionTemplate          编程式事务模板
     * @param ingestionCancellationApi     在飞摄入任务掐灭端口（跨 BC 端口，实现位于 rag BC）
     * @param ingestionTaskSubmitter       摄入解析任务提交契约（跨 BC 端口，实现位于 rag BC）
     * @param chunkPhysicalDeleteService   切片物理删除原语
     * @param documentCleanupTaskSubmitter 文档清退任务提交端口（跨 BC 端口，实现位于 rag BC）
     * @param inFlightTaskRegistry         在飞任务注册表（跨 BC 端口，实现位于 rag BC）
     */
    public DocumentApplicationService(DocumentRepository documentRepository,
                                      ChunkRepository chunkRepository,
                                      KnowledgeBaseRepository knowledgeBaseRepository,
                                      DocumentDedupService documentDedupService,
                                      KbAssetStoragePort kbAssetStoragePort,
                                      MediaImageCleanupService mediaImageCleanupService,
                                      TransactionTemplate transactionTemplate,
                                      IngestionCancellationApi ingestionCancellationApi,
                                      IngestionTaskSubmitter ingestionTaskSubmitter,
                                      ChunkPhysicalDeleteService chunkPhysicalDeleteService,
                                      DocumentCleanupTaskSubmitter documentCleanupTaskSubmitter,
                                      InFlightTaskRegistry inFlightTaskRegistry) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.documentDedupService = documentDedupService;
        this.kbAssetStoragePort = kbAssetStoragePort;
        this.mediaImageCleanupService = mediaImageCleanupService;
        this.transactionTemplate = transactionTemplate;
        this.ingestionCancellationApi = ingestionCancellationApi;
        this.ingestionTaskSubmitter = ingestionTaskSubmitter;
        this.chunkPhysicalDeleteService = chunkPhysicalDeleteService;
        this.documentCleanupTaskSubmitter = documentCleanupTaskSubmitter;
        this.inFlightTaskRegistry = inFlightTaskRegistry;
    }

    /**
     * 登记上传文档：接收文件字节，服务端算可信内容哈希并落对象存储，再以短事务判重 + 登记置为 PENDING。
     * <p>时序：①事务外校验知识库可写与文件格式 / 大小（大小取字节实算值，
     * 上限与 Spring multipart 上限同源）与文档级分块策略值域
     * （{@link ChunkStrategyConfigValidator}，非法即拒绝登记，不触碰对象存储与数据库）；
     * ②服务端对上传的原始文件字节算 SHA-256 作为判重内容轴
     * （小写十六进制、恒 64 字符，唯一用途是判重轴），
     * 调用方自报哈希一律不采信；③经存储访问端口把文件字节写入对象存储
     * （远程 IO 固定在事务外）；④短事务判重 + 处置 + 登记（见
     * {@link #registerUnderKnowledgeBaseLock}）；⑤登记未发生（REJECT 抛出 / SKIP 命中 / 事务失败回滚）
     * 时补偿删除③写入的对象，失败仅 WARN 留痕且不改变对外响应。</p>
     * <p>覆盖换版（OVERWRITE）：受理段在同一事务内以 {@code acceptDelete}
     * 将命中旧文档逐个 CAS 推入 DELETING（沿用主动删除链原子语义），并登记新文档为 PENDING；
     * 事务提交后按序双投递——先经 {@link DocumentCleanupTaskSubmitter} 为每个旧文档投递清退任务
     * （进入既有 {@link #runCleanupChain}：等待在飞 worker 自灭 → Storage 回收 → 分批清退切片
     * → 图谱收敛 → 文档收口），再经 {@link IngestionTaskSubmitter} 为新文档投递解析任务。
     * 两侧投递各自 try/catch 独立留痕，MUST NOT 相互阻断、MUST NOT 回滚受理（受理 COMMIT 即事实发生）。
     * 旧文档快照通过 {@link UploadDocumentResult#overwritten()} 回传给控制器作为前端可见中间态。</p>
     * <p>⑥登记成功（非跳过）时事务提交后统一走解析任务提交通道：
     * 提交失败由 {@link #submitIngestionTask} 内部回写文档为 FAILED，上传响应不受影响。</p>
     *
     * @param command 上传命令（携带文件字节）
     * @return 上传结果（新文档已登记，或命中重复被跳过并携带既有文档；覆盖换版路径携带旧文档快照列表）
     * @throws ResourceNotFoundException 知识库不存在（404）
     * @throws DeepDataAgentException    文件内容为空 / 格式不支持 / 大小超限 / 文档级分块策略非法 /
     *                                   知识库处于删除流程（400）
     * @throws ResourceConflictException 命中重复且冲突动作为 REJECT（409，消息携带重复摘要）
     */
    public UploadDocumentResult upload(UploadDocumentCommand command) {
        KnowledgeBase knowledgeBase = knowledgeBaseRepository.findById(command.kbId())
                .orElseThrow(() -> new ResourceNotFoundException("知识库不存在"));
        KnowledgeBaseValidator.validateNotDeleting(knowledgeBase);
        FileType fileType = DocumentValidator.validateFileType(command.fileType());
        byte[] content = command.content();
        if (ObjectUtils.isEmpty(content)) {
            throw new DeepDataAgentException("上传文件内容不能为空");
        }
        DocumentValidator.validateFileSize((long) content.length, maxFileSize.toBytes());
        // 文档级分块策略在落对象存储与开启登记事务前完成拒绝式校验：非法即零写入、零残留对象
        ChunkStrategyConfigValidator.validate(command.chunkStrategy());
        String contentHash = documentDedupService.sha256Hex(content);
        S3File stored = storeSourceFile(command.kbId(), fileType, command.contentType(), content);
        UploadDocumentResult result;
        try {
            result = transactionTemplate.execute(status -> registerUnderKnowledgeBaseLock(
                    command, fileType, stored, contentHash));
        } catch (RuntimeException e) {
            deleteStoredObjectQuietly(stored);
            throw e;
        }
        // 事务已提交（transactionTemplate 返回即 COMMIT）：覆盖换版路径先按序投递旧文档清退任务
        boolean overwritePath = ObjectUtils.isNotEmpty(result) && !CollectionUtils.isEmpty(result.overwritten());
        if (overwritePath) {
            for (Document stale : result.overwritten()) {
                submitDocumentCleanupAfterCommit(stale.id());
            }
        }
        // 新登记文档（非跳过）经解析任务提交通道入队（状态 PENDING=已入队）
        if (ObjectUtils.isNotEmpty(result) && !result.skipped() && ObjectUtils.isNotEmpty(result.document())) {
            submitIngestionTask(result.document().id());
        }
        if (overwritePath) {
            log.info("覆盖换版双投递完成, newDocId={}, 清理任务投递数={}, 解析任务投递=true",
                    result.document().id(), result.overwritten().size());
        }
        if (ObjectUtils.isNotEmpty(result) && result.skipped()) {
            deleteStoredObjectQuietly(stored);
        }
        return result;
    }

    /**
     * 判重 + 处置 + 登记（同一短事务，事务首步锁知识库行）。
     * <p>事务首步取 {@code knowledge_base} 行 {@code FOR UPDATE}，使同一知识库的并发上传互斥串行，
     * 消除「判重与登记分属两段」的 check-then-insert 竞态；跨知识库上传互不阻塞。
     * 固定锁序 {@code knowledge_base → document}，与删除链（只取 document 行锁）不交叉成环。
     * 事务体内仅含行锁、只读判重与落库，无任何远程 / 文件 IO。</p>
     * <p>处置语义沿用既有契约：REJECT → 409（消息携带重复摘要）；SKIP → 返回命中的既有文档并标记跳过；
     * OVERWRITE → 同事务内按文档ID升序对每条命中调用 {@link #acceptDelete}（复用主动删除链受理段，
     *）→ 保存新文档 PENDING；策略未配置 / 解析降级时保持旧行为直接登记。
     * 覆盖换版路径下被受理为 DELETING 的旧文档快照通过 {@link UploadDocumentResult#overwritten()} 回传，
     * 事务提交后由调用方 {@link #upload} 完成双投递。</p>
     *
     * @param command     上传命令
     * @param fileType    已解析校验的文件格式
     * @param stored      已写入对象存储的源文件引用
     * @param contentHash 服务端对上传的原始文件字节实算的判重内容轴（64 位小写十六进制 SHA-256）
     * @return 上传结果（登记成功 / 命中跳过 / 覆盖换版登记）
     * @throws ResourceNotFoundException 知识库不存在（404）
     * @throws DeepDataAgentException    知识库处于删除流程（400）
     * @throws ResourceConflictException 命中重复且冲突动作为 REJECT（409）
     */
    private UploadDocumentResult registerUnderKnowledgeBaseLock(UploadDocumentCommand command, FileType fileType,
                                                                S3File stored, String contentHash) {
        KnowledgeBase locked = knowledgeBaseRepository.findByIdForUpdate(command.kbId())
                .orElseThrow(() -> new ResourceNotFoundException("知识库不存在"));
        KnowledgeBaseValidator.validateNotDeleting(locked);
        DedupPolicyConfig policy = documentDedupService.resolvePolicy(locked);
        List<DedupHit> hits = documentDedupService.detect(command.kbId(), policy, command.fileName(), contentHash);
        if (CollectionUtils.isEmpty(hits)) {
            return UploadDocumentResult.registered(
                    documentRepository.save(newDocument(command, fileType, stored, contentHash)));
        }
        DedupConflictAction conflictAction = ObjectUtils.isEmpty(policy) ? null : policy.conflictAction();
        if (ObjectUtils.isEmpty(conflictAction)) {
            log.warn("命中库内重复但知识库去重策略未给出处置动作，按登记放行，kbId={}", command.kbId());
            return UploadDocumentResult.registered(
                    documentRepository.save(newDocument(command, fileType, stored, contentHash)));
        }
        return switch (conflictAction) {
            case REJECT -> throw new ResourceConflictException(documentDedupService.describeHits(hits));
            case SKIP -> UploadDocumentResult.skipped(hits.get(0).document());
            case OVERWRITE -> overwriteDuplicates(command, fileType, stored, contentHash, hits);
        };
    }

    /**
     * 上传判重预检（只读）：按库级去重策略探测同名 / 同内容文档，返回重复摘要供调用方决策。
     * <p>不产生任何写入；知识库未启用检测或查询轴无命中时返回空列表。</p>
     *
     * @param kbId        知识库ID
     * @param fileName    待检文件名，可为空
     * @param contentHash 待检内容哈希（64 位十六进制 SHA-256，大小写不敏感；其他形态一律 400 拒绝），可为空
     * @return 命中列表（文档ID升序）；无命中返回空列表
     * @throws DeepDataAgentException    两轴全空 / 内容哈希形态非法 / 知识库处于删除流程（400）
     * @throws ResourceNotFoundException 知识库不存在（404）
     */
    public List<DedupHit> precheck(Long kbId, String fileName, String contentHash) {
        String normalizedContentHash = DocumentValidator.validatePrecheckAxes(fileName, contentHash);
        KnowledgeBase knowledgeBase = knowledgeBaseRepository.findById(kbId)
                .orElseThrow(() -> new ResourceNotFoundException("知识库不存在"));
        KnowledgeBaseValidator.validateNotDeleting(knowledgeBase);
        return documentDedupService.detect(kbId, documentDedupService.resolvePolicy(knowledgeBase),
                fileName, normalizedContentHash);
    }

    /**
     * 构造待登记的新文档聚合根（大小与源文件引用取服务端实算 / 实写值）。
     *
     * @param command     上传命令
     * @param fileType    已解析校验的文件格式
     * @param stored      已写入对象存储的源文件引用
     * @param contentHash 服务端对上传的原始文件字节实算的判重内容轴（64 位小写十六进制 SHA-256）
     * @return 待保存的文档聚合根（PENDING）
     */
    private Document newDocument(UploadDocumentCommand command, FileType fileType, S3File stored,
                                 String contentHash) {
        return Document.create(command.kbId(), command.fileName(), fileType, (long) command.content().length,
                command.sourceFileProfile(), toS3FileJson(stored),
                contentHash, command.chunkStrategy());
    }

    /**
     * 序列化源文件存储引用 JSON（结构为 {@code {"objectKey": "..."}}，桶概念已退役）。
     *
     * @param stored 源文件引用值对象
     * @return 引用 JSON 文本
     */
    private String toS3FileJson(S3File stored) {
        ObjectNode reference = OBJECT_MAPPER.createObjectNode();
        reference.put(FIELD_S3_OBJECT_KEY, stored.objectKey());
        return reference.toString();
    }

    /**
     * 事务外把上传的文件字节写入对象存储并返回可登记的源文件引用（先落文件后登记）。
     * <p>对象键约定 {@code rag/{kbId}/source/{uuid}.{扩展名}}：与媒体图片资产共用
     * {@code rag/{kbId}/} 前缀根（整库清退可一并覆盖），又借 {@code source} 目录段与
     * {@code {documentId}/images} 媒体目录区隔；UUID 保证对象键唯一，登记失败时补偿删除不会
     * 误删仍被其他文档引用的字节。</p>
     *
     * @param kbId        知识库ID
     * @param fileType    已解析校验的文件格式（仅用于对象键扩展名）
     * @param contentType 文件内容类型（取自 multipart 文件部分，可为空）
     * @param content     文件字节
     * @return 源文件存储引用
     */
    private S3File storeSourceFile(Long kbId, FileType fileType, String contentType, byte[] content) {
        String objectKey = SOURCE_OBJECT_NAMESPACE + PATH_SEPARATOR + kbId + PATH_SEPARATOR + SOURCE_OBJECT_DIRECTORY
                + PATH_SEPARATOR + UUID.randomUUID() + FILE_EXTENSION_SEPARATOR
                + StringUtils.lowerCase(fileType.name(), Locale.ROOT);
        kbAssetStoragePort.putSource(objectKey, content, contentType);
        return new S3File(objectKey);
    }

    /**
     * 登记未发生（REJECT 抛出 / SKIP 命中 / 事务失败回滚）时对刚写入对象的补偿删除。
     * <p>尽力而为：删除异常仅 WARN 留痕，MUST NOT 改变对外响应结果——残留对象由既有清理语义兜底。</p>
     *
     * @param stored 刚写入的源文件引用
     */
    private void deleteStoredObjectQuietly(S3File stored) {
        if (ObjectUtils.isEmpty(stored)) {
            return;
        }
        try {
            kbAssetStoragePort.delete(stored.objectKey());
            log.info("上传登记未发生，已回收刚写入的源文件对象, objectKey={}", stored.objectKey());
        } catch (RuntimeException e) {
            log.warn("上传登记未发生后的源文件对象补偿删除失败，残留对象由既有清理语义兜底, objectKey={}",
                    stored.objectKey(), e);
        }
    }

    /**
     * 覆盖处置：在已开启的登记事务内按文档ID升序复用主动删除链受理段逐个受理旧文档，再登记新文档。
     * <p>受理段：对每条命中调用 {@link #acceptDelete}
     * （同一事务内 {@code SELECT FOR UPDATE} 行锁 + CAS 推入 DELETING，五源态必命中；
     * DELETE_FAILED 重删同语句清留痕；已在 DELETING 幂等受理），并保存新文档为 PENDING。
     * 命中列表来自仓储的 ID 升序查询，天然构成固定加锁顺序（且外层已持有知识库行锁）。
     * 与旧 {@code cascadeDeleteDocument} 直删链的差异：本路径不再于事务内做物理 DELETE 与图谱收敛，
     * 事务体仅含纯 CRUD 状态推进，符合"事务内严禁远程 IO"约束；崩溃时旧文档停留 DELETING、
     * 新文档停留 PENDING，可分别被主动删除链幂等续跑与解析任务队列接续消费。</p>
     * <p>事务提交前的 INFO 日志留痕本次受理范围，便于运维在受理 COMMIT 后
     * 与双投递窗口内定位问题。旧文档资产清退与图谱收敛在 {@link #runCleanupChain} 内按序推进。</p>
     *
     * @param command     上传命令
     * @param fileType    已解析校验的文件格式
     * @param stored      已写入对象存储的源文件引用
     * @param contentHash 服务端对上传的原始文件字节实算的判重内容轴
     * @param hits        判重命中列表（文档ID升序）
     * @return 登记结果（携带新文档快照与被受理为 DELETING 的旧文档快照列表）
     */
    private UploadDocumentResult overwriteDuplicates(UploadDocumentCommand command, FileType fileType, S3File stored,
                                                     String contentHash, List<DedupHit> hits) {
        List<Document> accepted = new ArrayList<>(hits.size());
        for (DedupHit hit : hits) {
            accepted.add(acceptDelete(hit.document().id()));
        }
        Document registered = documentRepository.save(newDocument(command, fileType, stored, contentHash));
        log.info("覆盖换版受理, kbId={}, newDocId={}, staleDocIds={}",
                command.kbId(), registered.id(), accepted.stream().map(Document::id).toList());
        return UploadDocumentResult.registeredWithOverwritten(registered, accepted);
    }

    /**
     * 分页查询知识库内的文档列表。
     *
     * @param query 列表查询条件
     * @return 文档列表，按创建时间倒序
     * @throws DeepDataAgentException 状态取值非法（400）
     */
    public List<Document> list(ListDocumentQuery query) {
        return documentRepository.findByKbId(query.kbId(), query.fileName(),
                DocumentValidator.parseStatusOrNull(query.status()), query.page(), query.size());
    }

    /**
     * 统计知识库内文档列表命中总数。
     *
     * @param kbId     知识库ID
     * @param fileName 文件名关键字，可为空
     * @param status   文档处理状态（DocumentStatus 枚举名），可为空
     * @return 命中记录数
     * @throws DeepDataAgentException 状态取值非法（400）
     */
    public long count(Long kbId, String fileName, String status) {
        return documentRepository.countByKbId(kbId, fileName, DocumentValidator.parseStatusOrNull(status));
    }

    /**
     * 按主键查询文档详情。
     *
     * @param id 文档ID
     * @return 文档聚合根
     * @throws ResourceNotFoundException 文档不存在（404）
     */
    public Document get(Long id) {
        return documentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("文档不存在"));
    }

    /**
     * 打开文档原文内容流（预览 / 下载共用，只读代理）。
     * <p>全程处于事务外：仅只读查询与跨 BC 存储调用，不含任何数据库写入，
     * 符合「事务内严禁远程调用」约束。前置闸门：文档不存在或已逻辑删除回传 404；
     * 所属知识库缺失（含已删除导致的逻辑删不可见）或非 ACTIVE（DELETING）回传业务错误，
     * 且不触碰对象存储、不回传任何文件内容。内容类型按文档登记的文件类型推断，
     * 不可识别时兜底为 {@value #DEFAULT_CONTENT_TYPE}；字节数以对象存储列举精确命中取得。</p>
     *
     * @param id 文档ID
     * @return 内容结果（文件名、内容类型、字节数、内容流；流由调用方负责关闭）
     * @throws ResourceNotFoundException 文档不存在或已删除（404）/ 源文件对象缺失（404）
     * @throws DeepDataAgentException    知识库不可用 / 存储引用缺失或非法（400）
     */
    public DocumentContentResult openContent(Long id) {
        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("文档不存在"));
        KnowledgeBase knowledgeBase = knowledgeBaseRepository.findById(document.kbId())
                .orElseThrow(() -> new DeepDataAgentException(ERROR_KB_UNAVAILABLE));
        if (!knowledgeBase.isActive()) {
            throw new DeepDataAgentException(ERROR_KB_UNAVAILABLE);
        }
        S3File s3File = parseS3File(document);
        KbAssetStoragePort.OpenedObject opened = kbAssetStoragePort.open(s3File.objectKey())
                .orElseThrow(() -> new ResourceNotFoundException("文档源文件对象不存在"));
        String contentType = CONTENT_TYPE_BY_FILE_TYPE.getOrDefault(document.fileType(), DEFAULT_CONTENT_TYPE);
        return new DocumentContentResult(document.fileName(), contentType, opened.size(), opened.content());
    }

    /**
     * 解析文档登记的文件存储引用 JSON（结构为 {@code {"objectKey": "..."}}，桶概念已退役）。
     *
     * @param document 目标文档聚合根
     * @return 存储引用值对象
     * @throws DeepDataAgentException 引用缺失、非法 JSON 或缺少对象键（400）
     */
    private S3File parseS3File(Document document) {
        if (StringUtils.isBlank(document.s3File())) {
            throw new DeepDataAgentException("文档未登记文件存储引用，无法访问内容");
        }
        JsonNode reference;
        try {
            reference = OBJECT_MAPPER.readTree(document.s3File());
        } catch (JacksonException e) {
            log.error("文档文件存储引用不是合法JSON，documentId={}，原始值={}", document.id(), document.s3File(), e);
            throw new DeepDataAgentException("文档文件存储引用不是合法的JSON");
        }
        String objectKey = reference.path(FIELD_S3_OBJECT_KEY).stringValue(null);
        if (StringUtils.isBlank(objectKey)) {
            throw new DeepDataAgentException("文档文件存储引用缺少对象键");
        }
        return new S3File(objectKey);
    }

    /**
     * 删除文档（两段式删除链：受理 CAS 同步 + 清退线程异步）。
     * <p>受理段（请求线程内短事务）：CAS
     * {@code {PENDING, PROCESSING, PROCESSED, FAILED, DELETE_FAILED} → DELETING}（单条条件 UPDATE；
     * DELETE_FAILED 重删同时清 error_message）。命中 0 行时按锁内行状态分流：行不存在 → 404；
     * 行已 DELETING → 仍受理（清退在飞则幂等去重，崩溃遗留则重触发续跑）；其他状态 → 冲突（409，
     * 理论不可达的防御分支）。</p>
     * <p>受理事务提交后经 {@link DocumentCleanupTaskSubmitter} 端口投递清退任务体
     * （触发时机对齐 {@code KnowledgeBaseApplicationService} 的「transactionTemplate 返回即已提交」
     * 同款模式），请求线程<b>不执行任何清退步骤</b>，立即返回受理结果（202「删除中」语义）；
     * 投递失败（停机拒收）仅 ERROR 留痕——文档停留 DELETING，由重删或本实例启动恢复一致性校验收敛处置。
     * 清退链在删除清退虚拟线程内推进，见 {@link #runCleanupChain(Long)}。</p>
     *
     * @param command 删除命令
     * @return 受理后的文档快照（状态恒为 DELETING、重删时失败留痕已清，供控制器回显「删除中」，202 语义）
     * @throws ResourceNotFoundException 文档不存在（404）
     * @throws ResourceConflictException 行存在但状态不可受理（409，防御分支）
     */
    public Document delete(DeleteDocumentCommand command) {
        Long documentId = command.id();
        Document accepted = transactionTemplate.execute(status -> acceptDelete(documentId));
        // 受理短事务已提交（transactionTemplate 返回）：此处才允许清退线程可见 DELETING 行
        submitDocumentCleanupAfterCommit(documentId);
        return accepted;
    }

    /**
     * 删除链受理段：短事务锁行 + 状态 CAS 推入 DELETING（对 RAG 侧的取消信号）。
     * <p>文档行不存在即抛 404——「已删除」的唯一表达是行物理缺失。解析中（PROCESSING）不再拒绝
     * 409——置 DELETING 后在飞摄入 worker 将在下个状态闸口自灭。CAS 命中 0 行时锁内行状态只可能是
     * DELETING（源态集之外的持久态不存在）：在飞幂等与崩溃遗留重触发两种情形均受理，清退任务提交
     * 与否由端口在飞去重裁决；其余状态为防御性冲突分支（持锁期间理论不可达）。</p>
     * <p>可见性：package-private。除 {@link #delete(DeleteDocumentCommand)} 走主动删除链调用外，
     * 覆盖换版分支 {@link #overwriteDuplicates} 亦在同一受理事务内直调本方法——
     * 两条路径共用「CAS 推入 DELETING + 幂等受理」的原子语义，MUST NOT 由调用方另立受理实现。</p>
     *
     * @param documentId 文档ID
     * @return 受理后的文档快照（状态恒为 DELETING；首删 / 重删时失败留痕视为已清）
     * @throws ResourceNotFoundException 文档不存在（404）
     * @throws ResourceConflictException 行存在但状态不可受理（409，防御分支）
     */
    Document acceptDelete(Long documentId) {
        Document document = documentRepository.findByIdForUpdate(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("文档不存在"));
        boolean transited = documentRepository.transitStatus(documentId, DELETE_ACCEPT_FROM_STATUSES,
                DocumentStatus.DELETING, null);
        if (transited) {
            return document.withStatus(DocumentStatus.DELETING).withErrorMessage(null);
        }
        if (document.status() == DocumentStatus.DELETING) {
            // 已在飞：提交端口去重挡下（幂等）；崩溃遗留（线程已失）：提交端口重触发续跑——两种均受理
            log.info("文档已处于 DELETING，按受理继续（在飞幂等 / 崩溃遗留重触发由提交端口裁决）, documentId={}",
                    documentId);
            return document;
        }
        throw new ResourceConflictException("文档状态已变更，请重新发起删除：" + document.status());
    }

    /**
     * 受理事务提交后投递文档清退任务（fire-and-forget）。
     * <p>时序：登记先于投递——先在飞注册表登记 {@link InFlightTaskType#DOC_CLEANUP} 凭据，
     * 再投递清退任务；登记与投递均为纯内存操作，MUST 处于事务之外，仅在受理短事务提交
     * （transactionTemplate 返回）后调用。登记失败（注册表不可用）按失败兜底置 DELETE_FAILED 留痕，
     * MUST NOT 静默放行；投递返回 {@code false}＝该文档清退任务已在飞（重删双击等），按幂等受理不另处理；
     * 投递抛异常（停机拒收 {@code IllegalStateException} 等）仅 ERROR 留痕、不改写对外响应且
     * <b>保留在飞成员</b>——文档停留 DELETING，由用户重删或本实例启动恢复按状态一致性校验收敛。</p>
     *
     * @param documentId 已受理为 DELETING 的文档主键
     */
    private void submitDocumentCleanupAfterCommit(Long documentId) {
        try {
            inFlightTaskRegistry.register(InFlightTaskType.DOC_CLEANUP, documentId);
        } catch (RuntimeException e) {
            log.error("文档清退任务在飞登记失败，按失败兜底置 DELETE_FAILED, documentId={}", documentId, e);
            markDeleteFailed(documentId, DELETE_STEP_REGISTER, List.of(), e);
            return;
        }
        boolean submitted;
        try {
            submitted = documentCleanupTaskSubmitter.submit(documentId, () -> runCleanupChain(documentId));
        } catch (Exception e) {
            log.error("文档清退任务投递失败（文档停留 DELETING，由重删或启动恢复一致性校验收敛处置）, documentId={}", documentId, e);
            // 成员保留，由本实例启动恢复按状态一致性校验收敛
            return;
        }
        if (!submitted) {
            log.info("文档清退任务已在飞，重复提交按幂等跳过, documentId={}", documentId);
        }
    }

    /**
     * 清退任务体（原请求线程链整体挪入删除清退虚拟线程）。
     * <p>固定时序：①掐灭等待（事务外，等待被中断即抛异常终止本任务）→ ②Storage 资产回收
     * （媒体前缀 + 源文件定点，<b>先于一切 DB 清退</b>，对象不存在=成功）→
     * ③分批 DB 清退（切片批 ≤500 经 {@link ChunkPhysicalDeleteService} 原语，
     * 每批独立小事务内「分块删除 + 图谱账本收敛」同事务生效）→
     * ④收口条件物理 DELETE（{@link DocumentRepository#executeDelete(Long)}，0 行=已收口幂等）。</p>
     * <p><b>零重试</b>：任一步异常即终止本任务，经 {@link #markDeleteFailed} 置 DELETE_FAILED 留痕
     * （已成功批次不回滚），提示文案「文档不可用，请重新执行删除」；任务体自带全量 catch
     * （fire-and-forget 契约，异常 MUST NOT 逃逸到执行器兜底之外）。</p>
     * <p>在飞成员移除以「状态已收敛」为前提：失败分支仅在置态成功（或未命中即已收敛）后才移除成员，
     * 置态失败保留成员并由 ERROR 留痕，交本实例下次启动恢复重试；收口成功分支（行已物理删除，
     * 或条件 DELETE 零行命中＝已收口）同样属状态已收敛，直接移除成员。</p>
     *
     * @param documentId 已受理为 DELETING 的文档主键
     */
    void runCleanupChain(Long documentId) {
        AtomicReference<String> failedStep = new AtomicReference<>(DELETE_STEP_CANCEL_WAIT);
        AtomicReference<List<Long>> failedBatch = new AtomicReference<>(List.of());
        try {
            awaitIngestionTermination(documentId);
            // Storage 先删：资产回收 MUST 先于任何数据库清退批次
            failedStep.set(DELETE_STEP_MEDIA_CLEANUP);
            failedBatch.set(List.of());
            cleanupDocumentAssets(documentId, failedStep);
            cleanupRemainingChunks(documentId, failedStep, failedBatch);
            failedStep.set(DELETE_STEP_FINISH);
            failedBatch.set(List.of());
            finishDelete(documentId);
        } catch (RuntimeException | Error e) {
            // 置态成功（或未命中即已收敛）才移除成员；置态失败保留成员待下次启动重试
            if (markDeleteFailed(documentId, failedStep.get(), failedBatch.get(), e)) {
                unregisterDocumentCleanupQuietly(documentId);
            }
            return;
        }
        // 收口完成（行已物理删除，或条件 DELETE 零行命中＝已收口）→ 状态已收敛，移除成员
        unregisterDocumentCleanupQuietly(documentId);
    }

    /**
     * 静默移除文档清退任务的在飞成员（异常仅 ERROR 留痕，MUST NOT 上抛）。
     *
     * @param documentId 文档主键
     */
    private void unregisterDocumentCleanupQuietly(Long documentId) {
        try {
            inFlightTaskRegistry.unregister(InFlightTaskType.DOC_CLEANUP, documentId);
        } catch (RuntimeException e) {
            log.error("文档清退任务在飞成员移除失败（残留凭据由下次启动收敛兜底）, documentId={}", documentId, e);
        }
    }

    /**
     * 删除链掐灭等待（事务外）：等待该文档在飞摄入 worker 退出，最长阻塞至配置上限。
     * <p>超时仅 WARN 留痕后继续清退：worker 必经 DELETING 状态闸门自灭，
     * 切片回写闸门亦已互斥，晚退 worker 无法写回任何切片。</p>
     *
     * @param documentId 文档ID
     */
    private void awaitIngestionTermination(Long documentId) {
        boolean terminated = ingestionCancellationApi.awaitTermination(documentId,
                Duration.ofSeconds(cancelWaitTimeoutSeconds));
        if (!terminated) {
            log.warn("等待在飞摄入任务终止超时（{}s），继续执行清退链, documentId={}", cancelWaitTimeoutSeconds, documentId);
        }
    }

    /**
     * 删除链清退步骤③：分批清退文档名下切片（每批一个原语事务，重建远程调用落在事务之外）。
     * <p>批取在事务外（只读、不占事务时间）；每批交给 {@link ChunkPhysicalDeleteService#deleteChunks}
     * 原语，由其自持两段式边界（组 6，design D3）：<b>事务外</b>完成图谱重建计算（重放抽取缓存 +
     * 描述摘要 + 向量化，远程调用），<b>单事务</b>内按「图谱账本收敛与缓存回收 → 1:1 表示
     * 「向量 → 全文」→ 切片行」固定序原子生效——本链调用处 MUST NOT 再包裹外层事务，否则远程调用
     * 会被并入批事务（事务规范 1.1 严禁）。文档链场景 {@code syncDocumentChunkCount=false}
     * 跳过计数回写（文档行即将终删）；图谱收敛由原语按批次数据推导——文档名下绝大多数为解析块，
     * 含解析块即收敛，本处是文档名下分块级图谱贡献的<b>唯一</b>清退路径。无 @TableLogic，
     * 原语即物理 DELETE，行消失后每轮从头取 LIMIT 自然轮到下一批，天然支持中断后幂等续跑
     * （顺序不变量：切片行删除晚于重建读取缓存，故任一批失败后重试仍能自「账本 ∩ 被删分块」
     * 重推出同一批受影响条目，MUST NOT 依赖任何日志或待办记录）。</p>
     *
     * @param documentId  文档ID
     * @param failedStep  失败步骤跟踪器（供批失败留痕定位）
     * @param failedBatch 失败批次切片ID跟踪器（供批失败留痕定位）
     */
    private void cleanupRemainingChunks(Long documentId, AtomicReference<String> failedStep,
                                        AtomicReference<List<Long>> failedBatch) {
        while (true) {
            failedStep.set(DELETE_STEP_BATCH_FETCH);
            failedBatch.set(List.of());
            List<Long> chunkIds = chunkRepository.findIdsByDocumentId(documentId, deleteCleanupBatchSize);
            if (CollectionUtils.isEmpty(chunkIds)) {
                return;
            }
            failedBatch.set(chunkIds);
            failedStep.set(DELETE_STEP_CHUNK_BATCH);
            // 异步线程无操作人上下文：operator 传 null，由原语侧审计口径回落系统缺省值；
            // 计数回写关闭（文档行即将终删）；图谱收敛由原语按批次来源数据推导（本链是该文档
            // 分块级图谱贡献的唯一清退路径，批内含解析块即同事务收敛）
            chunkPhysicalDeleteService.deleteChunks(chunkIds, null, false);
            log.info("清退文档切片一批, documentId={}, count={}", documentId, chunkIds.size());
        }
    }

    /**
     * 删除链资产回收步骤：媒体前缀 + 源文件定点（两步，对象存储侧）。
     * <p>两步顺序执行，事务外单次读取文档行定位清理目标：
     * ①按「知识库 + 文档」两级前缀 {@code rag/{kbId}/{documentId}/images/} 清理媒体图片资产
     * （委托 {@link MediaImageCleanupService#cleanupDocumentImages}，单次前缀幂等清退）；
     * ②按文档登记的存储引用定点回收源文件对象
     * （委托 {@link MediaImageCleanupService#cleanupSourceFiles}，对象不存在视为成功）。</p>
     * <p>执行位置与失败语义（「Storage 先删、DB 后删」）：本步骤位于
     * 一切 DB 清退批次之前、文档收口之前，清退线程内事务外执行（对象存储为远程 IO，严禁进事务）。
     * 两步的清退 / 删除异常按删除链既有模式上抛，由 {@link #runCleanupChain} 的 catch 统一经
     * {@link #markDeleteFailed} 留痕 DELETE_FAILED（failedStep 区分 media_cleanup / source_file_cleanup）——
     * 零重试，用户重删即从断点续跑重试本步骤（对象不存在=成功消化半态）。
     * 文档行不可见或无有效源文件引用（从未摄入即无资产）时视为无需清理，WARN 后跳过，
     * MUST NOT 因缺引用而阻断删除。</p>
     * <p>图谱贡献的清退不在本步骤：由后续分批清退（{@link #cleanupRemainingChunks} 经
     * {@link ChunkPhysicalDeleteService} 原语）在同一小事务内完成「分块删除 + 图谱账本收敛」，
     * 满足分块引用完整性不变量。</p>
     *
     * @param documentId 文档ID
     * @param failedStep 失败步骤跟踪器（进入源文件定点回收前推进为 {@value #DELETE_STEP_SOURCE_FILE_CLEANUP}）
     */
    void cleanupDocumentAssets(Long documentId, AtomicReference<String> failedStep) {
        Document document = documentRepository.findById(documentId).orElse(null);
        if (ObjectUtils.isEmpty(document)) {
            log.warn("文档行不可见，跳过文档资产回收（媒体图片 / 源文件）, documentId={}", documentId);
            return;
        }
        mediaImageCleanupService.cleanupDocumentImages(document);
        failedStep.set(DELETE_STEP_SOURCE_FILE_CLEANUP);
        mediaImageCleanupService.cleanupSourceFiles(document);
    }

    /**
     * 删除链失败留痕（零重试落点）：经仓储单语句 CAS 置 DELETE_FAILED 并携带步骤信息。
     * <p>留痕走 DELETING → DELETE_FAILED 单向 CAS（error_message 列写
     * {@code [DELETE-FAILED] step=…}），避免覆盖并发链已收口的终态；
     * 留痕自身失败仅记 error 不另抛，但<b>保留在飞成员</b>待下次启动重试——
     * 否则会产生「状态停在中间态而注册表无迹可寻」的永久不可见悬挂。</p>
     *
     * @param documentId 文档ID
     * @param step       失败步骤标识
     * @param chunkIds   失败批次切片ID集合（非批步骤为空，留痕省略）
     * @param cause      原始异常
     * @return {@code true} 表示状态已收敛（CAS 命中置态成功，或未命中即行已收口 / 状态已推进）；
     *         {@code false} 表示置态写入异常（状态未收敛，调用方 MUST NOT 移除在飞成员）
     */
    private boolean markDeleteFailed(Long documentId, String step, List<Long> chunkIds, Throwable cause) {
        String batchPart = CollectionUtils.isEmpty(chunkIds) ? "" : " chunkIds=" + chunkIds;
        String errorMessage = StringUtils.abbreviate(
                DELETE_FAILED_MESSAGE_PREFIX + " step=" + step + batchPart,
                DELETE_ERROR_MESSAGE_MAX_LENGTH);
        log.error("文档删除链清退失败（已置 DELETE_FAILED，文档不可用，请重新执行删除）, "
                + "documentId={}, step={}, chunkIds={}", documentId, step, chunkIds, cause);
        try {
            boolean hit = documentRepository.markFailed(documentId, errorMessage);
            if (!hit) {
                log.info("文档删除失败留痕未命中（行已收口或状态已推进，视为已收敛）, documentId={}", documentId);
            }
            return true;
        } catch (RuntimeException e) {
            log.error("文档删除失败留痕写入失败，保留在飞成员待下次启动重试, documentId={}", documentId, e);
            return false;
        }
    }

    /**
     * 删除链收口：单条条件物理 DELETE {@code WHERE id=? AND status IN ('DELETING','DELETE_FAILED')}。
     * <p>「已删除」不再落任何持久状态：行物理缺失是其唯一表达。单条 DELETE 自身即原子单元，
     * 不包裹额外事务上下文（对齐知识库收口 {@code executeDelete} 同款形态）；
     * 0 行命中（行已不存在＝并发链已收口）按幂等空转处理，MUST NOT 抛出。</p>
     *
     * @param documentId 文档ID
     */
    private void finishDelete(Long documentId) {
        boolean closed = documentRepository.executeDelete(documentId);
        if (!closed) {
            log.info("文档收口条件 DELETE 未命中（行已不存在即已收口），按幂等空转处理, documentId={}", documentId);
            return;
        }
        log.info("文档删除链收口完成（行物理删除，行缺失即已删除）, documentId={}", documentId);
    }

    /**
     * 重新解析文档：刷新分块策略快照、置 PENDING 并清零切片数，事务提交后经提交契约再入队。
     * <p>状态驱动委托——本方法只在知识库上下文内改动自有状态，不执行解析；事务提交后调用
     * {@link IngestionTaskSubmitter#submit(Long)} 把解析任务提交进 RAG 内存摄入队列
     * （与上传路径复用同一提交契约），由队列消费方按快照全量重建。
     * 以文档状态作为并发闸门，处于「解析中」的文档拒绝重复触发重建。</p>
     * <p><b>显式语义（BREAKING）</b>：摄入管线的整篇替换将清除该文档<b>全部</b>分块
     * （<b>含人工新增的分块</b>）并按解析结果重建，人工维护内容不保留；重建序号由服务端
     * 按解析顺序连续分配。该清除语义 MUST 由接口契约声明（见
     * {@code DocumentController#reparse}）并由前端二次确认承接——缺少确认即为静默数据丢失。</p>
     *
     * <p>策略校验：命令携带的覆盖值在开启事务前完成拒绝式校验（非法即零 DB 交互）；未携带覆盖值时
     * 回退读取库级快照，同样在写库前校验（见 {@link #resolveChunkStrategy}）——校验失败即抛异常，
     * 事务回滚，MUST NOT 留下「策略已刷新但状态已置 PENDING」的半写状态。</p>
     *
     * @param command 重新解析命令（可选携带分块策略覆盖，为空则沿用知识库当前分块配置快照）
     * @return 重置后的文档聚合根
     * @throws ResourceNotFoundException 文档不存在（404）
     * @throws ResourceConflictException 文档正在解析中（409）
     * @throws DeepDataAgentException    分块策略覆盖值或库级快照非法（400）
     */
    public Document reparse(ReparseDocumentCommand command) {
        // 用户显式携带的策略覆盖值先于加锁与事务校验：非法直接拒绝，不产生任何数据库交互
        ChunkStrategyConfigValidator.validate(command.chunkStrategyOverride());
        Document reset = transactionTemplate.execute(status -> {
            Document document = documentRepository.findByIdForUpdate(command.id())
                    .orElseThrow(() -> new ResourceNotFoundException("文档不存在"));
            DocumentValidator.validateCanReparse(document);
            KnowledgeBase knowledgeBase = knowledgeBaseRepository.findById(document.kbId())
                    .orElseThrow(() -> new ResourceNotFoundException("知识库不存在"));
            KnowledgeBaseValidator.validateNotDeleting(knowledgeBase);
            String effectiveStrategy = resolveChunkStrategy(command.chunkStrategyOverride(), knowledgeBase);
            Document pending = document.withChunkStrategy(effectiveStrategy)
                    .withStatus(DocumentStatus.PENDING)
                    .withChunkCount(0)
                    .withErrorMessage(null);
            return documentRepository.update(pending);
        });
        // 事务已提交：重新解析回到 PENDING（已入队语义）后立即提交解析任务
        if (ObjectUtils.isNotEmpty(reset)) {
            submitIngestionTask(reset.id());
        }
        return reset;
    }

    /**
     * 事务提交后提交解析任务并闭合孤儿窗口。
     * <p>提交成功（含幂等重复提交）即返回；返回 false 或抛异常（如停机拒收、契约实现缺陷）时，
     * 以 PENDING → FAILED 的条件流转回写 {@value #INGESTION_SUBMIT_FAILED_MESSAGE}，
     * MUST NOT 留下「状态 PENDING 但队列无对应任务」的孤儿文档；用户可手动重新解析再次入队。
     * 回滚流转自身失败（如文档恰被删除链置 DELETING 导致 CAS 未命中或 DB 异常）仅 ERROR 留痕，
     * MUST NOT 改写上传 / 重新解析的对外响应。
     * 契约返回值语义已收紧为「该文档后续确实会被处理」（真实入队，或去重命中时队列已登记
     * 待补投、由对应任务收尾补投——见 {@link IngestionTaskSubmitter#submit(Long)}）：
     * 「不会被处理」的情形不再以成功值返回，本兜底判定与之天然一致，签名与调用点均不变。</p>
     * <p>在飞注册表时序（均处于事务外）：登记先于投递——投递失败（含停机窗口拒收）也留有残留凭据，
     * 由本实例启动恢复按状态一致性校验收敛；登记写入失败 MUST NOT 静默放行，直接按失败兜底回写 FAILED；
     * 投递失败时仅在回写结果确认「状态已收敛」后才移除在飞成员——命中置 {@code FAILED} 与未命中
     * （行已不存在或状态已被并发推进）均属已收敛，回写异常时保留成员待启动恢复重试。</p>
     *
     * @param documentId 已落库为 PENDING 的文档ID
     */
    private void submitIngestionTask(Long documentId) {
        // 登记先于投递：投递失败（含停机窗口拒收）也留有残留凭据，由本实例启动恢复收敛
        try {
            inFlightTaskRegistry.register(InFlightTaskType.DOC_INGESTION, documentId);
        } catch (RuntimeException e) {
            log.error("解析任务在飞登记失败，按失败兜底回写 FAILED, documentId={}", documentId, e);
            revertPendingToFailed(documentId);
            return;
        }
        boolean submitted;
        try {
            submitted = ingestionTaskSubmitter.submit(documentId);
        } catch (RuntimeException e) {
            log.error("解析任务提交异常，回滚文档为 FAILED, documentId={}", documentId, e);
            submitted = false;
        }
        if (submitted) {
            return;
        }
        // 投递失败：回写 FAILED 成功后即视为状态已收敛，移除在飞成员
        if (revertPendingToFailed(documentId)) {
            unregisterIngestionQuietly(documentId);
        }
    }

    /**
     * 解析任务在飞登记失败或投递失败后的失败兜底回写：以 PENDING → FAILED 的条件流转
     * 写入 {@value #INGESTION_SUBMIT_FAILED_MESSAGE}，闭合「状态 PENDING 但队列无对应任务」的孤儿窗口。
     * <p>「未命中」意味着数据行已不存在、或状态已被并发推进（例如用户并发发起删除置 {@code DELETING}）——
     * 两种情况都说明该文档已不再是本实例的在飞解析任务，既无需置态、也无须继续保留在飞凭据，
     * 故与命中同属「状态已收敛」；仅回写异常（状态是否收敛未知）才保留在飞成员。</p>
     *
     * @param documentId 已落库为 PENDING 的文档ID
     * @return {@code true} 表示状态已收敛（命中并已置 FAILED，或未命中＝行已不存在 / 状态已推进，无需置态），
     *         调用方据此移除在飞成员；{@code false} 表示回写异常，状态未收敛，MUST NOT 移除在飞成员
     */
    private boolean revertPendingToFailed(Long documentId) {
        try {
            boolean transferred = documentRepository.transitStatus(documentId, Set.of(DocumentStatus.PENDING),
                    DocumentStatus.FAILED, INGESTION_SUBMIT_FAILED_MESSAGE);
            if (!transferred) {
                log.info("解析任务提交失败后的 FAILED 回写未命中（数据行已不存在或状态已被并发推进），"
                        + "按状态已收敛处置并移除在飞成员, documentId={}", documentId);
            }
            return true;
        } catch (RuntimeException e) {
            log.error("解析任务提交失败后的 FAILED 回写异常，依赖本实例启动恢复或用户重新解析收敛, documentId={}",
                    documentId, e);
            return false;
        }
    }

    /**
     * 静默移除解析任务的在飞成员（异常仅 ERROR 留痕，MUST NOT 上抛）。
     *
     * @param documentId 文档ID
     */
    private void unregisterIngestionQuietly(Long documentId) {
        try {
            inFlightTaskRegistry.unregister(InFlightTaskType.DOC_INGESTION, documentId);
        } catch (RuntimeException e) {
            log.error("解析任务在飞成员移除失败（残留凭据由下次启动收敛兜底）, documentId={}", documentId, e);
        }
    }

    /**
     * 确定本次重建实际生效的分块策略：请求覆盖优先，未覆盖时回退到知识库当前分块配置快照；
     * 知识库未配置分块策略时返回 {@code null}，保持「文档级为空则继承库级」的原语义。
     * <p>回退到库级快照时同样执行拒绝式校验（请求覆盖值已由调用方 {@link #reparse} 在事务外校验），
     * 非法即在写库前抛出，MUST NOT 把非法策略写入文档级配置。</p>
     *
     * @param override      请求携带的分块策略覆盖 JSON，可为空
     * @param knowledgeBase 所属知识库聚合根
     * @return 生效的分块策略 JSON；无可用配置时返回 {@code null}
     * @throws DeepDataAgentException 知识库 RAG 引擎配置非法 JSON 或库级分块策略非法（400）
     */
    private String resolveChunkStrategy(String override, KnowledgeBase knowledgeBase) {
        if (StringUtils.isNotBlank(override)) {
            return override;
        }
        String engineConfig = knowledgeBase.ragEngineConfig();
        if (StringUtils.isBlank(engineConfig)) {
            return null;
        }
        try {
            JsonNode strategy = OBJECT_MAPPER.readTree(engineConfig).path(FIELD_CHUNK_STRATEGY);
            if (strategy.isMissingNode() || strategy.isNull()) {
                return null;
            }
            String inherited = strategy.toString();
            ChunkStrategyConfigValidator.validate(inherited);
            return inherited;
        } catch (JacksonException e) {
            log.error("知识库 RAG 引擎配置不是合法JSON，无法提取分块策略，kbId={}，原始值={}",
                    knowledgeBase.id(), engineConfig, e);
            throw new DeepDataAgentException("知识库 RAG 引擎配置不是合法的JSON");
        }
    }
}
