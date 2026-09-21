package com.linkroa.deepdataagent.knowledgebase.application.service;

import com.linkroa.deepdataagent.knowledgebase.api.InFlightTaskRegistry;
import com.linkroa.deepdataagent.knowledgebase.api.InFlightTaskType;
import com.linkroa.deepdataagent.knowledgebase.api.KbCleanupTaskSubmitter;
import com.linkroa.deepdataagent.knowledgebase.application.command.CreateKnowledgeBaseCommand;
import com.linkroa.deepdataagent.knowledgebase.application.command.DeleteKnowledgeBaseCommand;
import com.linkroa.deepdataagent.knowledgebase.application.command.UpdateEntityTypeConfigCommand;
import com.linkroa.deepdataagent.knowledgebase.application.command.UpdateKnowledgeBaseCommand;
import com.linkroa.deepdataagent.knowledgebase.application.command.UpdateRetrievalConfigCommand;
import com.linkroa.deepdataagent.knowledgebase.application.contract.RetrievalConfigDTO;
import com.linkroa.deepdataagent.knowledgebase.application.query.ListKnowledgeBaseQuery;
import com.linkroa.deepdataagent.knowledgebase.application.validation.EntityTypeConfigValidator;
import com.linkroa.deepdataagent.knowledgebase.application.validation.KnowledgeBaseValidator;
import com.linkroa.deepdataagent.knowledgebase.application.validation.RagEngineConfigValidator;
import com.linkroa.deepdataagent.knowledgebase.application.validation.RetrievalConfigValidator;
import com.linkroa.deepdataagent.knowledgebase.domain.model.KnowledgeBase;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentStatus;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.KnowledgeBaseSortField;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.LifecycleStatus;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.RetrievalStrategyType;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 知识库应用服务（创建 / 编辑 / 删除 / 查询 / 总览统计 / 分区配置更新）。
 * <p>事务边界：写操作使用编程式事务仅包裹数据库 CRUD，行锁（{@code findByIdForUpdate}）与
 * 后续更新处于同一事务；查询走仓储条件方法，不显式开启事务。</p>
 */
@Service
public class KnowledgeBaseApplicationService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseApplicationService.class);

    /** 统计结果中知识库总数的键名 */
    public static final String STATS_KEY_TOTAL_KNOWLEDGE_BASES = "totalKnowledgeBases";

    /** 统计结果中文档总数的键名 */
    public static final String STATS_KEY_TOTAL_DOCUMENTS = "totalDocuments";

    /** 统计结果中切片总数的键名 */
    public static final String STATS_KEY_TOTAL_CHUNKS = "totalChunks";

    /** 清退失败留痕（error_message）截断上限，防止超长异常文案溢出留痕列（与文档删除链同形口径） */
    private static final int KB_ERROR_MESSAGE_MAX_LENGTH = 500;

    /** 整库清退在飞登记失败的失败留痕文案（登记失败 MUST NOT 静默放行，按失败兜底置 DELETE_FAILED） */
    private static final String KB_CLEANUP_REGISTER_FAILED_TRACE = "[KB-CLEANUP] step=in_flight_register: 在飞登记失败";

    /** 检索策略 JSON 的字段名：策略类型 */
    private static final String FIELD_STRATEGY_TYPE = "strategyType";

    /** 检索策略 JSON 的字段名：是否启用问题改写 */
    private static final String FIELD_REWRITE_QUESTION = "rewriteQuestion";

    /** 检索策略 JSON 的字段名：召回结果条数 */
    private static final String FIELD_RESULT_CHUNK_COUNT = "resultChunkCount";

    /** 检索策略 JSON 的字段名：相似度阈值 */
    private static final String FIELD_SIMILAR_THRESHOLD = "similarThreshold";

    /** 检索策略 JSON 的字段名：是否启用图谱召回通道 */
    private static final String FIELD_GRAPH_ENABLED = "graphEnabled";

    /** 检索策略 JSON 的字段名：重排配置 */
    private static final String FIELD_RERANK_CONFIG = "rerankConfig";

    /** 检索策略 JSON 的字段名：融合配置 */
    private static final String FIELD_FUSION_CONFIG = "fusionConfig";

    /** 检索策略 JSONB 序列化器（无状态，进程内共享） */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 知识库仓储（本域自有表读写与行锁读取） */
    private final KnowledgeBaseRepository knowledgeBaseRepository;

    /** 文档仓储（解析中文档拦截与文档统计） */
    private final DocumentRepository documentRepository;

    /** 切片仓储（切片统计） */
    private final ChunkRepository chunkRepository;

    /** 编程式事务模板（写操作的精确事务边界） */
    private final TransactionTemplate transactionTemplate;

    /** 整库清退任务提交端口（RAG 侧删除清退虚拟线程执行器实现，返回值即该库清退在飞判定） */
    private final KbCleanupTaskSubmitter kbCleanupTaskSubmitter;

    /** 在飞任务注册表（rag BC 实现）：任务投递前登记在飞凭据、状态收敛后移除成员，供本实例启动恢复收敛悬挂任务 */
    private final InFlightTaskRegistry inFlightTaskRegistry;

    /**
     * 构造知识库应用服务。
     *
     * @param knowledgeBaseRepository 知识库仓储（本域自有表读写与行锁读取）
     * @param documentRepository      文档仓储（解析中文档拦截与文档统计）
     * @param chunkRepository         切片仓储（切片统计）
     * @param transactionTemplate     编程式事务模板（写操作的精确事务边界）
     * @param kbCleanupTaskSubmitter  整库清退任务提交端口（跨 BC 契约，实现位于 rag BC）
     * @param inFlightTaskRegistry    在飞任务注册表（跨 BC 契约，实现位于 rag BC）
     */
    public KnowledgeBaseApplicationService(KnowledgeBaseRepository knowledgeBaseRepository,
                                           DocumentRepository documentRepository,
                                           ChunkRepository chunkRepository,
                                           TransactionTemplate transactionTemplate,
                                           KbCleanupTaskSubmitter kbCleanupTaskSubmitter,
                                           InFlightTaskRegistry inFlightTaskRegistry) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.transactionTemplate = transactionTemplate;
        this.kbCleanupTaskSubmitter = kbCleanupTaskSubmitter;
        this.inFlightTaskRegistry = inFlightTaskRegistry;
    }

    /**
     * 创建知识库：名称唯一性先行拦截（数据库唯一索引兜底），初始状态 ACTIVE。
     * <p>语言为独立入参（真相源 = {@code knowledge_base.language} 列）：经
     * {@link KnowledgeBaseValidator#validateLanguage} 强校验（值域外拒绝、不静默回落），
     * 校验在事务开启前完成；未指定时聚合层显式落 {@code Chinese}，不依赖列库默认值。
     * {@code rag_engine_config} JSONB 的 language 键已弃用，不再校验。</p>
     *
     * @param command 创建命令
     * @return 落库后的知识库聚合根
     * @throws DeepDataAgentException    名称为空或超长、语言入参值域外（400）
     * @throws ResourceConflictException 名称重复（409）
     */
    public KnowledgeBase create(CreateKnowledgeBaseCommand command) {
        KnowledgeBaseValidator.validateNameUnique(command.name(), knowledgeBaseRepository.findByName(command.name()));
        RagEngineConfigValidator.validate(command.ragEngineConfig());
        KnowledgeBaseValidator.validateLanguage(command.language());
        KnowledgeBase created = KnowledgeBase.create(
                command.name(), command.description(), command.language(), command.ragEngineConfig(),
                command.dedupPolicy(),
                command.retrievalStrategy(), command.embeddingConfig(), command.multiModelConfig(),
                command.entityTypeConfig());
        return transactionTemplate.execute(status -> knowledgeBaseRepository.save(created));
    }

    /**
     * 更新知识库基础信息与库级配置。
     * <p>命令中为空白的字段保持原值；名称发生变更时才做唯一性校验；嵌入模型配置创建后锁定，
     * 请求携带新值时忽略并记录告警；语言为独立入参（真相源 = {@code knowledge_base.language} 列，
     *），经 {@link KnowledgeBaseValidator#validateLanguage} 强校验（值域外拒绝、
     * 不静默回落），校验在事务开启前完成；命令未携带语言（null / 空白）时保持原值不覆盖。
     * {@code rag_engine_config} JSONB 的 language 键已弃用，不再校验。</p>
     *
     * @param command 更新命令
     * @return 更新后的知识库聚合根
     * @throws ResourceNotFoundException 知识库不存在（404）
     * @throws DeepDataAgentException    知识库处于删除流程或语言入参值域外（400）
     * @throws ResourceConflictException 新名称被其他知识库占用（409）
     */
    public KnowledgeBase update(UpdateKnowledgeBaseCommand command) {
        RagEngineConfigValidator.validate(command.ragEngineConfig());
        KnowledgeBaseValidator.validateLanguage(command.language());
        return transactionTemplate.execute(status -> {
            KnowledgeBase current = requireActiveForUpdate(command.id());
            String name = StringUtils.defaultIfBlank(command.name(), current.name());
            if (!StringUtils.equals(name, current.name())) {
                KnowledgeBaseValidator.validateNameUnique(name, knowledgeBaseRepository.findByName(name)
                        .filter(occupied -> !Objects.equals(occupied.id(), current.id())));
            }
            String description = StringUtils.defaultIfBlank(command.description(), current.description());
            if (StringUtils.isNotBlank(command.embeddingConfig())
                    && !StringUtils.equals(command.embeddingConfig(), current.embeddingConfig())) {
                log.warn("知识库 {} 的嵌入模型配置创建后不可变更，已忽略请求携带的新值", current.id());
            }
            KnowledgeBase updated = current.withUpdatedConfig(
                    StringUtils.defaultIfBlank(command.ragEngineConfig(), current.ragEngineConfig()),
                    StringUtils.defaultIfBlank(command.dedupPolicy(), current.dedupPolicy()),
                    StringUtils.defaultIfBlank(command.retrievalStrategy(), current.retrievalStrategy()),
                    current.embeddingConfig(),
                    StringUtils.defaultIfBlank(command.multiModelConfig(), current.multiModelConfig()),
                    StringUtils.defaultIfBlank(command.entityTypeConfig(), current.entityTypeConfig()))
                    // 语言独立于 JSONB 配置段：命令未携带（null / 空白）时保持列原值
                    .withLanguage(StringUtils.defaultIfBlank(command.language(), current.language()));
            return knowledgeBaseRepository.update(withIdentity(updated, name, description));
        });
    }

    /**
     * 发起知识库删除（受理矩阵）。
     * <p>受理事务内以行锁读取当前生命周期后按<b>三源态矩阵</b>受理（DELETED 不持久、行缺失即已删除）：</p>
     * <ul>
     *   <li>行不存在（含已收口）→ 404；</li>
     *   <li>{@code ACTIVE}（首删）：存在解析中文档 → 409 拒绝（避免与异步摄入竞态；PENDING 不拦）；
     *       否则单语句 CAS {@code ACTIVE → DELETING}，未命中（并发窗口状态被改走）→ 409；</li>
     *   <li>{@code DELETE_FAILED}（重删）：单语句 CAS {@code DELETE_FAILED → DELETING} 并同语句清除
     *       {@code error_message} 留痕，未命中 → 409；不做 PROCESSING 拦截（库已失活，无摄入竞态）；</li>
     *   <li>{@code DELETING}（在飞重复删除 / 崩溃遗留）：状态已是目标态、零 CAS，出块后由提交端口裁决。</li>
     * </ul>
     * <p>「在飞幂等」与「崩溃遗留重触发」的区分<b>内聚在提交端口的返回值</b>（见
     * {@link KbCleanupTaskSubmitter#submit(Long)}）：受理成功后一律在<b>事务提交后</b>投递清退任务——
     * 该库任务已在飞时执行器登记闸门挡下重复任务并回 {@code false}（幂等），不在飞时本次投递即新建任务
     * （首删推进 / 重删续跑 / 崩溃遗留重触发），两种结果对用户一律回「删除中」。</p>
     * <p>投递失败（含停机拒收 {@code IllegalStateException}）仅 ERROR 留痕，不回滚受理、不落失败态——
     * 知识库停留 DELETING，由用户重删或本实例启动恢复（{@code CleanupStartupMaintenance}）
     * 按状态一致性校验收敛为 {@code DELETE_FAILED} 处置（不再自动重触发续跑）。
     * 清退本体（含条件物理 DELETE 收口与失败 markFailed）全部由 RAG 清退线程驱动，不在本服务内推进。</p>
     *
     * @param command 删除命令
     * @return 受理后的知识库快照（生命周期恒为 DELETING、失败留痕已清，供控制器回显「删除中」）
     * @throws ResourceNotFoundException 知识库不存在或已收口（行缺失，404）
     * @throws ResourceConflictException 存在解析中的文档，或并发下 CAS 状态迁移未命中（409）
     */
    public KnowledgeBase delete(DeleteKnowledgeBaseCommand command) {
        Long kbId = command.id();
        KnowledgeBase accepted = transactionTemplate.execute(status -> acceptDelete(kbId));
        submitCleanupTaskAfterCommit(kbId);
        return accepted;
    }

    /**
     * 按主键查询知识库详情。
     *
     * @param id 知识库ID
     * @return 知识库聚合根
     * @throws ResourceNotFoundException 知识库不存在（404）
     */
    public KnowledgeBase get(Long id) {
        return knowledgeBaseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("知识库不存在"));
    }

    /**
     * 分页查询知识库列表（删除中 / 删除失败库对用户可见）。
     * <p>状态维度不下传具体值——列表可见性由仓储侧 {@code WHERE lifecycle_status IN
     * (ACTIVE, DELETING, DELETE_FAILED)} 三态全集条件保证（已收口库行物理不存在、天然不可达），
     * 回显 lifecycle 与 error_message 供前端轮询状态徽标与发起重删。</p>
     * <p>排序参数经白名单校验后传给仓储：未提供时按创建时间倒序；白名单外直接拒绝。</p>
     *
     * @param query 列表查询条件（含排序字段与排序方向原始值）
     * @return 知识库列表
     * @throws DeepDataAgentException 排序字段或排序方向不在白名单内（400）
     */
    public List<KnowledgeBase> list(ListKnowledgeBaseQuery query) {
        KnowledgeBaseSortField sortField = KnowledgeBaseValidator.parseSortField(query.sortBy());
        boolean ascending = KnowledgeBaseValidator.parseAscendingSortOrder(query.sortOrder());
        return knowledgeBaseRepository.findByCondition(query.keyword(), null,
                sortField, ascending, query.page(), query.size());
    }

    /**
     * 统计知识库列表命中总数（口径与 {@link #list} 一致：三态全集，含删除中与删除失败库）。
     *
     * @param keyword 名称/描述关键字，可为空
     * @return 命中记录数
     */
    public long count(String keyword) {
        return knowledgeBaseRepository.countByCondition(keyword, null);
    }

    /**
     * 知识库总览统计：可用库数、文档总数、切片总数。
     * <p>统计口径允许最终一致，实现为三条 count 查询；文档与切片按全库统计，
     * 知识库ID传 {@code null} 表示不加库维度过滤。</p>
     *
     * @return 统计结果，键为 {@link #STATS_KEY_TOTAL_KNOWLEDGE_BASES}、
     *         {@link #STATS_KEY_TOTAL_DOCUMENTS}、{@link #STATS_KEY_TOTAL_CHUNKS}
     */
    public Map<String, Long> stats() {
        Map<String, Long> stats = new LinkedHashMap<>(3);
        stats.put(STATS_KEY_TOTAL_KNOWLEDGE_BASES, knowledgeBaseRepository.countByStatus(LifecycleStatus.ACTIVE));
        stats.put(STATS_KEY_TOTAL_DOCUMENTS, documentRepository.countByKbId(null, null, null));
        stats.put(STATS_KEY_TOTAL_CHUNKS, chunkRepository.countByKbId(null, null, null, null));
        return stats;
    }

    /**
     * 更新检索策略配置（查询侧配置，保存后对后续检索即时生效）。
     * <p>与 {@link #saveRetrievalConfig}（保留能力，当前无生产调用点）共用
     * {@link RetrievalConfigValidator} 统一校验，
     * 校验与规范化（策略类型归一、RRF 缺省 rrfK 回写 60）在事务开启前完成，落库值为规范化后的 JSON。</p>
     *
     * @param command 检索配置更新命令
     * @return 更新后的知识库聚合根
     * @throws ResourceNotFoundException 知识库不存在（404）
     * @throws DeepDataAgentException    配置为空、校验不通过或知识库处于删除流程（400）
     */
    public KnowledgeBase updateRetrievalConfig(UpdateRetrievalConfigCommand command) {
        String normalizedStrategy = RetrievalConfigValidator.validateAndNormalize(command.retrievalStrategy());
        return transactionTemplate.execute(status -> {
            KnowledgeBase current = requireActiveForUpdate(command.kbId());
            KnowledgeBase updated = current.withUpdatedConfig(
                    current.ragEngineConfig(), current.dedupPolicy(), normalizedStrategy,
                    current.embeddingConfig(), current.multiModelConfig(), current.entityTypeConfig());
            return knowledgeBaseRepository.update(updated);
        });
    }

    /**
     * 保存检索配置。
     * <p><b>保留能力，当前无生产调用点</b>：原为跨 BC 契约 {@code KnowledgeBaseApi#saveRetrievalConfig}
     * 的提供者实现；该契约方法因全项目零消费方，已随知识库 Bean 循环修复同步退役，本方法保留能力
     * 以待后续需要，REST 入口走 {@link #updateRetrievalConfig}。</p>
     * <p>以整块覆盖语义写入 {@code retrieval_strategy} JSONB：入参为空的可选字段不落库，
     * 图谱开关始终落库（为空按 false）。与 REST 入口共用 {@link RetrievalConfigValidator}
     * 统一校验与规范化（含 RRF 缺省 rrfK 回写 60），校验在事务开启前完成，任一规则不通过不落库。
     * 知识库不存在返回 404，处于删除流程返回 400，
     * 事务内只做知识库自有表的行锁读取与更新，不含任何远程调用。</p>
     *
     * @param kbId       知识库主键，必填
     * @param config     检索配置契约，必填
     * @param operatorId 操作人ID，可为空（空则审计字段落系统默认值）
     * @return 落库后的检索配置快照（融合配置取自规范化回写后的节点）
     * @throws DeepDataAgentException    入参为空、校验不通过或知识库处于删除流程（400）
     * @throws ResourceNotFoundException 知识库不存在（404）
     */
    public RetrievalConfigDTO saveRetrievalConfig(Long kbId, RetrievalConfigDTO config, Long operatorId) {
        if (ObjectUtils.isEmpty(kbId)) {
            throw new DeepDataAgentException("知识库ID不能为空");
        }
        if (ObjectUtils.isEmpty(config)) {
            throw new DeepDataAgentException("检索配置不能为空");
        }
        RetrievalStrategyType strategyType = parseStrategyType(config.strategyType());
        ObjectNode strategyNode = buildRetrievalStrategyNode(strategyType, config);
        RetrievalConfigValidator.validateAndNormalize(strategyNode);
        String strategyJson = strategyNode.toString();
        RetrievalConfigDTO snapshot = new RetrievalConfigDTO(strategyType.name(), config.rewriteQuestion(),
                config.resultChunkCount(), config.similarThreshold(), config.graphEnabled(),
                config.rerankConfig(), extractFusionConfigJson(strategyNode));
        transactionTemplate.executeWithoutResult(status -> {
            KnowledgeBase current = requireActiveForUpdate(kbId);
            KnowledgeBase updated = current.withUpdatedConfig(
                    current.ragEngineConfig(), current.dedupPolicy(), strategyJson,
                    current.embeddingConfig(), current.multiModelConfig(), current.entityTypeConfig());
            knowledgeBaseRepository.update(updated, auditOperator(operatorId));
        });
        return snapshot;
    }

    /**
     * 清退失败留痕（跨 BC 契约 {@code KnowledgeBaseApi#markFailed} 的提供者实现）。
     * <p>零重试语义的失败落点：以
     * {@link KnowledgeBaseRepository#markFailed} 单语句 CAS 完成 DELETING → DELETE_FAILED 并同语句
     * 写入 {@code error_message} 留痕（形如 {@code [KB-CLEANUP] step=…}，超过
     * {@value #KB_ERROR_MESSAGE_MAX_LENGTH} 字符截断）。非源态 CAS 未命中（已收口行缺失、已是
     * DELETE_FAILED 或 ACTIVE）按幂等空转处理：仅 INFO 留痕，MUST NOT 覆盖并发链已写入的状态。</p>
     * <p>单条条件 UPDATE 自身即原子单元，不包裹额外事务上下文。</p>
     *
     * @param kbId   知识库主键，必填
     * @param reason 失败步骤与原因摘要，可为空（空则仅置态不落具体文案）
     * @throws DeepDataAgentException 知识库ID为空（400）
     */
    public void markFailed(Long kbId, String reason) {
        if (ObjectUtils.isEmpty(kbId)) {
            throw new DeepDataAgentException("知识库ID不能为空");
        }
        String traceMessage = StringUtils.abbreviate(reason, KB_ERROR_MESSAGE_MAX_LENGTH);
        boolean hit = knowledgeBaseRepository.markFailed(kbId, traceMessage);
        if (!hit) {
            log.info("知识库 {} 未处于 DELETING 源态（或行已不存在），失败留痕按幂等空转处理, reason={}",
                    kbId, traceMessage);
            return;
        }
        log.warn("知识库清退失败已置 DELETE_FAILED（请重新执行删除）, kbId={}, error_message={}", kbId, traceMessage);
    }

    /**
     * 更新实体类型配置（摄入类配置，需重解析后生效）。
     * <p>落库前经 {@link EntityTypeConfigValidator} 强校验（名称格式、内置同名、强制保留、防重、数量上限），
     * 校验在事务开启前完成；校验仅拒绝不回写，合法配置以原文落库。</p>
     *
     * @param command 实体类型配置更新命令
     * @return 更新后的知识库聚合根
     * @throws ResourceNotFoundException 知识库不存在（404）
     * @throws DeepDataAgentException    配置为空、校验不通过或知识库处于删除流程（400）
     */
    public KnowledgeBase updateEntityTypeConfig(UpdateEntityTypeConfigCommand command) {
        EntityTypeConfigValidator.validate(command.entityTypeConfig());
        return transactionTemplate.execute(status -> {
            KnowledgeBase current = requireActiveForUpdate(command.kbId());
            KnowledgeBase updated = current.withUpdatedConfig(
                    current.ragEngineConfig(), current.dedupPolicy(), current.retrievalStrategy(),
                    current.embeddingConfig(), current.multiModelConfig(), command.entityTypeConfig());
            return knowledgeBaseRepository.update(updated);
        });
    }

    /**
     * 解析契约中的策略类型字符串为领域枚举。
     * <p>大小写不敏感；非法取值按参数错误处理，不做异常捕获式控制流。</p>
     */
    private RetrievalStrategyType parseStrategyType(String strategyType) {
        String normalized = StringUtils.trimToEmpty(strategyType).toUpperCase(Locale.ROOT);
        for (RetrievalStrategyType candidate : RetrievalStrategyType.values()) {
            if (StringUtils.equals(candidate.name(), normalized)) {
                return candidate;
            }
        }
        throw new DeepDataAgentException("检索策略类型非法：" + strategyType);
    }

    /**
     * 按 {@code retrieval_strategy} JSONB 的既有口径生成配置节点（供统一校验后序列化落库）。
     * <p>重排与融合配置以子 JSON 原样嵌入，避免二次编码转义。</p>
     */
    private ObjectNode buildRetrievalStrategyNode(RetrievalStrategyType strategyType, RetrievalConfigDTO config) {
        ObjectNode strategy = OBJECT_MAPPER.createObjectNode();
        strategy.put(FIELD_STRATEGY_TYPE, strategyType.name());
        if (ObjectUtils.isNotEmpty(config.rewriteQuestion())) {
            strategy.put(FIELD_REWRITE_QUESTION, config.rewriteQuestion());
        }
        if (ObjectUtils.isNotEmpty(config.resultChunkCount())) {
            strategy.put(FIELD_RESULT_CHUNK_COUNT, config.resultChunkCount());
        }
        if (ObjectUtils.isNotEmpty(config.similarThreshold())) {
            strategy.put(FIELD_SIMILAR_THRESHOLD, config.similarThreshold());
        }
        strategy.put(FIELD_GRAPH_ENABLED, Boolean.TRUE.equals(config.graphEnabled()));
        putRawJsonField(strategy, FIELD_RERANK_CONFIG, config.rerankConfig());
        putRawJsonField(strategy, FIELD_FUSION_CONFIG, config.fusionConfig());
        return strategy;
    }

    /**
     * 从规范化回写后的策略节点提取融合配置 JSON 文本；该字段未落库时返回 {@code null}。
     */
    private String extractFusionConfigJson(ObjectNode strategy) {
        JsonNode fusionNode = strategy.path(FIELD_FUSION_CONFIG);
        return fusionNode.isObject() ? fusionNode.toString() : null;
    }

    /**
     * 将子配置 JSON 字符串解析后嵌入父节点；空白表示不落该字段。
     */
    private void putRawJsonField(ObjectNode parent, String fieldName, String rawJson) {
        if (StringUtils.isBlank(rawJson)) {
            return;
        }
        try {
            parent.set(fieldName, OBJECT_MAPPER.readTree(rawJson));
        } catch (JacksonException e) {
            log.error("检索配置字段 {} 不是合法JSON，原始值={}", fieldName, rawJson, e);
            throw new DeepDataAgentException("检索配置字段 " + fieldName + " 不是合法的JSON");
        }
    }

    /**
     * 将契约透传的操作人ID转换为审计字段值；为空时返回 {@code null} 交由自动填充器落系统默认值。
     */
    private String auditOperator(Long operatorId) {
        return ObjectUtils.isEmpty(operatorId) ? null : String.valueOf(operatorId);
    }

    /**
     * 删除受理事务内核：按当前生命周期分派三源态矩阵，返回受理后的快照（生命周期恒 DELETING）。
     * <p>事务内仅含本域表的行锁读取与条件 UPDATE，无任何远程调用（数据库事务规范）。</p>
     */
    private KnowledgeBase acceptDelete(Long kbId) {
        KnowledgeBase current = knowledgeBaseRepository.findByIdForUpdate(kbId)
                .orElseThrow(() -> new ResourceNotFoundException("知识库不存在"));
        // 枚举已穷举三态，无需 default 分支
        return switch (current.lifecycleStatus()) {
            case ACTIVE -> acceptFirstDelete(current);
            case DELETE_FAILED -> acceptRedelete(current);
            // DELETING：清退已在途或崩溃遗留，状态已是目标态无需再动，由提交端口裁决幂等/重触发
            case DELETING -> current;
        };
    }

    /**
     * 首删受理（ACTIVE → DELETING）：先拦解析中文档，再单语句 CAS。
     */
    private KnowledgeBase acceptFirstDelete(KnowledgeBase current) {
        long processingCount = documentRepository.countByKbId(current.id(), null, DocumentStatus.PROCESSING);
        if (processingCount > 0) {
            throw new ResourceConflictException(
                    "知识库存在 " + processingCount + " 个解析中的文档，请先等待解析完成后再删除");
        }
        boolean hit = knowledgeBaseRepository.transitLifecycle(current.id(), LifecycleStatus.ACTIVE,
                LifecycleStatus.DELETING);
        if (!hit) {
            throw new ResourceConflictException("知识库状态已变更，请重新发起删除");
        }
        return current.markDeleting();
    }

    /**
     * 重删受理（DELETE_FAILED → DELETING 并清除失败留痕）：单语句 CAS，未命中即并发窗口状态被改走。
     */
    private KnowledgeBase acceptRedelete(KnowledgeBase current) {
        boolean hit = knowledgeBaseRepository.transitLifecycleClearingFailure(current.id(),
                LifecycleStatus.DELETE_FAILED, LifecycleStatus.DELETING);
        if (!hit) {
            throw new ResourceConflictException("知识库状态已变更，请重新发起删除");
        }
        return current.clearFailureOnRedelete();
    }

    /**
     * 删除受理事务提交后投递整库清退任务（尽力投递）。
     * <p>时序：登记先于投递——先在飞注册表登记 {@link InFlightTaskType#KB_CLEANUP} 凭据，再投递清退任务；
     * 登记与投递均为纯内存操作，MUST 处于事务之外——本方法仅在 {@code transactionTemplate}
     * 返回（事务已提交）后调用。登记失败（注册表不可用）按失败兜底置 DELETE_FAILED 留痕，
     * MUST NOT 静默放行；登记成功后投递的返回值即受理矩阵所需的在飞判定：{@code false} 表示该库清退已在飞
     * （重复删除幂等跳过，不产生第二个任务），{@code true} 表示本次新建任务（首删 / 重删 / 崩溃遗留重触发）。
     * 投递失败（含停机拒收 {@code IllegalStateException}）仅 ERROR 留痕并<b>保留在飞成员</b>，
     * 绝不回滚受理、不影响用户删除结果：知识库停留 DELETING，由用户重删或本实例启动恢复
     * 按状态一致性校验收敛为 {@code DELETE_FAILED} 处置（不再自动重触发续跑）。</p>
     *
     * @param kbId 已受理删除的知识库主键
     */
    private void submitCleanupTaskAfterCommit(Long kbId) {
        try {
            inFlightTaskRegistry.register(InFlightTaskType.KB_CLEANUP, kbId);
        } catch (RuntimeException e) {
            log.error("知识库清退任务在飞登记失败，按失败兜底置 DELETE_FAILED, kbId={}", kbId, e);
            markFailed(kbId, KB_CLEANUP_REGISTER_FAILED_TRACE);
            return;
        }
        try {
            boolean submitted = kbCleanupTaskSubmitter.submit(kbId);
            if (!submitted) {
                log.info("知识库清退任务已在飞，本次删除按幂等处理（不产生第二个清退任务）, kbId={}", kbId);
            }
        } catch (Exception e) {
            log.error("整库清退任务投递失败（知识库停留 DELETING，由重删或启动恢复一致性校验收敛处置）, kbId={}", kbId, e);
        }
    }

    /**
     * 加行锁读取知识库并校验处于可操作状态。
     */
    private KnowledgeBase requireActiveForUpdate(Long id) {
        KnowledgeBase current = knowledgeBaseRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("知识库不存在"));
        KnowledgeBaseValidator.validateNotDeleting(current);
        return current;
    }

    /**
     * 复制知识库并覆盖名称与描述（record 不可变语义，其余字段含更新时间原样保留）。
     */
    private KnowledgeBase withIdentity(KnowledgeBase base, String name, String description) {
        return KnowledgeBase.restore(base.id(), name, description, base.language(), base.lifecycleStatus(),
                base.errorMessage(), base.ragEngineConfig(), base.dedupPolicy(), base.retrievalStrategy(),
                base.embeddingConfig(), base.multiModelConfig(), base.entityTypeConfig(),
                base.createdAt(), base.updatedAt());
    }
}
