package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.rag.domain.model.EntityInfoVector;
import com.linkroa.deepdataagent.rag.domain.model.EntityNode;
import com.linkroa.deepdataagent.rag.domain.model.EntityProperties;
import com.linkroa.deepdataagent.rag.domain.model.RelationEdge;
import com.linkroa.deepdataagent.rag.domain.model.RelationInfoVector;
import com.linkroa.deepdataagent.rag.domain.model.RelationProperties;
import com.linkroa.deepdataagent.rag.domain.port.EmbeddingClient;
import com.linkroa.deepdataagent.rag.domain.repository.EntityInfoVectorRepository;
import com.linkroa.deepdataagent.rag.domain.repository.EntityNodeGraphRepository;
import com.linkroa.deepdataagent.rag.domain.repository.RelationEdgeGraphRepository;
import com.linkroa.deepdataagent.rag.domain.repository.RelationInfoVectorRepository;
import org.apache.commons.lang3.ObjectUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link GraphMergeService} 单元测试。
 *
 * <p>纯领域离线测试：四个仓储端口、{@link EmbeddingClient}、{@link DescriptionSummarizer}、
 * {@link TokenCounter} 与 {@link TransactionTemplate} 全部 Mockito 模拟；
 * 事务模板打桩为真实执行回调（模拟 Spring 短事务 A/B），摄入执行器打桩为当前线程同步执行
 * （确定性失败顺序），不启动 Spring 容器、不触碰数据库。</p>
 *
 * <p>覆盖场景：自环拒绝、平行边融合、belongs_to 批内双抽全额累加（LightRAG 同构 quirk）、
 * 无来源（null sourceId）记录全额累加、Map-Reduce 摘要调用、向量 chunkIds 累积、
 * 以向量账本为权威的实体/关系双幂等短路（含 fail-open）、source_ids 上限与 KEEP/FIFO 双策略截断、
 * 写前锚点机制退役后仅写真实条目（保留锚点名零写入）、端点名空/超长截断归一（含截断后同名自环）、取消检查、重放不膨胀、
 * 阶段失败异常聚合（addSuppressed）。</p>
 *
 * <p>账本权威口径（redefine-graph-ledger-invariants）：权重累加与幂等短路的过滤基准统一取
 * <b>关系向量行 chunk_ids</b>（向量账本），图行 source_ids 降级为纯展示列——覆盖「图行展示列被置空/
 * 截断但账本仍短路」「同一文档重复解析权重不增长」「新文档来源正常累加」三类断言；
 * 关系合并只持边锁、只读边行与边向量（不再触碰实体行），缺失端点<b>不补建占位节点</b>
 * （不建图行、不建向量行、不发 embedding，关系仍正常落库）；
 * 方向在抽取聚合出口归一为字典序，历史反向行仅按无向端点对复用同一条目计权，
 * MUST NOT 再发生残留折叠与反向行物理删除。</p>
 *
 * <p>双读模型与写回原子性覆盖：事务外普通读（仅构造上下文与复用旧向量）与写回事务内加锁读
 * （唯一写回基准）的时序分层断言、并发批次写回交错下账本/权重的并集累积、保留上限耗尽且过滤后
 * 无有效新片段的零写短路、首插唯一约束冲突的重试与重试上界（含非冲突异常不重试、重试后重新判定
 * 截断分支）、写回事务纯净性（远程调用先于事务且事务内不再发生）。</p>
 *
 * <p>规格场景直接证据补充：加锁读与写回落在同一事务执行期（实体与关系两条写回路径）、
 * 跨实例（进程内条纹锁域独立且事务外快照过期）下的账本并集、同名实体跨新旧账本并集与重复描述去重
 * 及空描述兜底、混合权重边全额累加翻正（10.0 + 3×1.0 = 13.0）与重喂幂等、
 * 均匀权重域下全额累加与原均摊公式的等价性。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
public class GraphMergeServiceTest {

    /** 测试知识库ID */
    private static final Long KB_ID = 1L;

    /** 测试文档ID（图合并上下文归属标识） */
    private static final Long DOC_ID = 7L;

    /** 摘要模型 profileId */
    private static final String SUMMARY_PROFILE = "model-summary";

    /** 向量化模型 profileId */
    private static final String EMBED_PROFILE = "model-embed";

    /** 测试操作人 */
    private static final String OPERATOR = "tester";

    /** Prompt 语言 */
    private static final String LANGUAGE_ZH = "zh";

    /** 实体类型：人物 */
    private static final String ENTITY_TYPE_PERSON = "PERSON";

    /** 来源文件路径 */
    private static final String FILE_PATH = "docs/sample.txt";

    /** 实体名 Alice（字典序小于 Bob/Ghost） */
    private static final String ENTITY_ALICE = "Alice";

    /** 实体名 Bob */
    private static final String ENTITY_BOB = "Bob";

    /** 实体名 Doc1（字典序小于 Folder1） */
    private static final String ENTITY_DOC = "Doc1";

    /** 实体名 Folder1 */
    private static final String ENTITY_FOLDER = "Folder1";

    /** 实体名 Ghost（抽取边引用但实体表不存在的端点，字典序小于 Ghost2） */
    private static final String ENTITY_GHOST = "Ghost";

    /** 实体名 Ghost2（第二个缺失端点，用于「两端点均缺失」场景） */
    private static final String ENTITY_GHOST2 = "Ghost2";

    /** 来源分块ID 101 */
    private static final Long SOURCE_101 = 101L;

    /** 来源分块ID 102 */
    private static final Long SOURCE_102 = 102L;

    /** 来源分块ID 103 */
    private static final Long SOURCE_103 = 103L;

    /** 来源分块ID 104（混合权重边的第 3 个普通文本 chunk） */
    private static final Long SOURCE_104 = 104L;

    /** 来源分块ID 201 */
    private static final Long SOURCE_201 = 201L;

    /** 来源分块ID 301 */
    private static final Long SOURCE_301 = 301L;

    /** 来源分块ID 302 */
    private static final Long SOURCE_302 = 302L;

    /** 来源分块ID 303 */
    private static final Long SOURCE_303 = 303L;

    /** 来源分块ID 555（仅存在于旧向量行 chunkIds 中） */
    private static final Long SOURCE_555 = 555L;

    /** 来源分块ID 1（并发合并用例中首写前的既有账本来源） */
    private static final Long SOURCE_1 = 1L;

    /** belongs_to 关系权重（口径） */
    private static final double BELONGS_TO_WEIGHT = 10.0;

    /** 普通关系权重 */
    private static final double DEFAULT_EDGE_WEIGHT = 1.0;

    /** 混合权重边合并用例中的普通文本 chunk 条数（各贡献 1.0） */
    private static final int PLAIN_CHUNK_COUNT = 3;

    /** 均摊公式在混合权重域下的失真值（spec Scenario 明示 MUST NOT 出现） */
    private static final double APPORTIONED_MIXED_WEIGHT_DRIFT = 19.75;

    /** belongs_to 关系关键词（文档级来源口径） */
    private static final String KEYWORD_BELONGS_TO = "belongs_to";

    /** 普通文本关系关键词 */
    private static final String KEYWORD_PLAIN = "rel";

    /** 文档级关系描述 */
    private static final String EDGE_DESC_BELONGS_TO = "doc-rel";

    /** 普通文本关系描述 */
    private static final String EDGE_DESC_PLAIN = "plain-rel";

    /** 跨新旧共享的重复实体描述 */
    private static final String SHARED_ENTITY_DESC = "same-desc";

    /** 空描述兜底前缀（与被测服务口径一致：{@code Entity {name}}） */
    private static final String ENTITY_DESC_PLACEHOLDER_PREFIX = "Entity ";

    /** embedding 桩返回的固定向量 */
    private static final float[] EMBEDDING_VECTOR = new float[]{0.1f, 0.2f};

    /** 内容未变化时复用的旧向量 */
    private static final float[] OLD_VECTOR = new float[]{0.5f, 0.5f};

    /** 摘要器返回的收敛描述 */
    private static final String SUMMARIZED_DESCRIPTION = "SUMMARIZED";

    /** 关系摘要 itemName 连接符（与被测服务展示口径一致） */
    private static final String RELATION_NAME_CONNECTOR = "~";

    /** 普通关系描述 */
    private static final String EDGE_DESC_NORMAL = "rel-desc";

    /** 实体/端点名入库最大长度（与被测服务内部常量、V11 表定义 VARCHAR(512) 口径一致） */
    private static final int NAME_LENGTH_LIMIT = 512;

    /** 默认上限用例中连续来源ID的起始值 */
    private static final long BELOW_DEFAULT_LIMIT_FIRST_SOURCE = 1001L;

    /** 默认上限用例中旧行已持有的来源条数（超过旧默认值 10、远低于新默认值 200） */
    private static final int BELOW_DEFAULT_LIMIT_SOURCE_COUNT = 12;

    /** 已退役写前锚点的实体侧保留名前缀（断言合并写入中不得出现此类条目） */
    private static final String RESERVED_ENTITY_ANCHOR_PREFIX = "__full_entities__";

    /** 已退役写前锚点的关系侧保留名前缀（断言合并写入中不得出现此类条目） */
    private static final String RESERVED_RELATION_ANCHOR_PREFIX = "__full_relations__";

    /** 图谱实体节点仓储桩 */
    @Mock
    private EntityNodeGraphRepository entityNodeRepository;

    /** 图谱关系边仓储桩 */
    @Mock
    private RelationEdgeGraphRepository relationEdgeRepository;

    /** 实体向量仓储桩 */
    @Mock
    private EntityInfoVectorRepository entityInfoVectorRepository;

    /** 关系向量仓储桩 */
    @Mock
    private RelationInfoVectorRepository relationInfoVectorRepository;

    /** 向量化端口桩 */
    @Mock
    private EmbeddingClient embeddingClient;

    /** 描述摘要器桩 */
    @Mock
    private DescriptionSummarizer descriptionSummarizer;

    /** Token 计数器桩 */
    @Mock
    private TokenCounter tokenCounter;

    /** 编程式事务模板桩 */
    @Mock
    private TransactionTemplate transactionTemplate;

    /** 摄入执行器桩（打桩为当前线程同步执行） */
    @Mock
    private Executor ingestionExecutor;

    /** 向量内容收口原语桩（合并流程仅在其上触发收口并计数，不参与写回计算） */
    @Mock
    private VectorContentReconciler vectorContentReconciler;

    /** 被测服务（Mockito 按类型走全参构造器注入） */
    @InjectMocks
    private GraphMergeService graphMergeService;

    /**
     * 公共桩：事务模板真实执行回调、执行器同步执行、truncate 恒等、embed 恒返固定向量。
     *
     * <p>全部使用 lenient()：部分用例（空批次、短路跳过、异常分支）不触达这些端口，
     * 避免 Mockito 严格桩（STRICT_STUBS）误报 UnnecessaryStubbing；
     * truncate 为接口 default 方法，被 mock 后不会走默认实现，必须显式打恒等桩。</p>
     */
    @BeforeEach
    public void setUp() {
        wireTransactionTemplate();
        lenient().doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(ingestionExecutor).execute(any());
        lenient().when(tokenCounter.truncate(anyString(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(embeddingClient.embed(anyString(), anyString())).thenReturn(EMBEDDING_VECTOR);
    }

    /**
     * 事务模板双桩：execute 与 executeWithoutResult 均真实执行传入回调
     * （executeWithoutResult 为接口 default 方法，mock 后不会委派到 execute，需单独打桩）。
     */
    private void wireTransactionTemplate() {
        lenient().doAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactionTemplate).execute(any());
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> consumer = invocation.getArgument(0);
            consumer.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    /**
     * 构造默认参数上下文（source_ids 上限取默认值 200、截断策略取默认 KEEP）。
     *
     * @return 图合并上下文
     */
    private GraphMergeContext defaultCtx() {
        return ctxWithTruncation(GraphMergeParams.DEFAULT_APPLY_SOURCE_IDS_LIMIT,
                GraphMergeParams.DEFAULT_SOURCE_IDS_TRUNCATION);
    }

    /**
     * 构造指定 source_ids 保留上限的上下文（截断策略取默认 KEEP），其余参数取默认。
     *
     * @param applySourceIdsLimit source_ids 保留上限
     * @return 图合并上下文
     */
    private GraphMergeContext ctxWithSourceLimit(int applySourceIdsLimit) {
        return ctxWithTruncation(applySourceIdsLimit, GraphMergeParams.DEFAULT_SOURCE_IDS_TRUNCATION);
    }

    /**
     * 构造指定 source_ids 上限与截断策略的上下文，其余参数取默认。
     *
     * @param applySourceIdsLimit source_ids 保留上限
     * @param sourceIdsTruncation 截断策略（KEEP 保留前 N / FIFO 保留后 N）
     * @return 图合并上下文
     */
    private GraphMergeContext ctxWithTruncation(int applySourceIdsLimit, String sourceIdsTruncation) {
        GraphMergeParams params = new GraphMergeParams(
                GraphMergeParams.DEFAULT_LLM_MODEL_MAX_ASYNC,
                applySourceIdsLimit,
                GraphMergeParams.DEFAULT_EMBEDDING_TOKEN_LIMIT,
                GraphMergeParams.DEFAULT_SUMMARY_CONTEXT_SIZE,
                GraphMergeParams.DEFAULT_SUMMARY_MAX_TOKENS,
                GraphMergeParams.DEFAULT_FORCE_LLM_SUMMARY_ON_MERGE,
                sourceIdsTruncation,
                GraphMergeParams.DEFAULT_SOURCE_FILE_PATHS_LIMIT,
                GraphMergeParams.DEFAULT_SOURCE_FILE_PATHS_PLACEHOLDER);
        return new GraphMergeContext(KB_ID, DOC_ID, SUMMARY_PROFILE, EMBED_PROFILE,
                LANGUAGE_ZH, params, OPERATOR, null);
    }

    /**
     * 构造指定来源文件路径保留上限的上下文，其余参数取默认。
     *
     * @param sourceFilePathsLimit 来源文件路径列表保留上限
     * @return 图合并上下文
     */
    private GraphMergeContext ctxWithFilePathsLimit(int sourceFilePathsLimit) {
        GraphMergeParams params = new GraphMergeParams(
                GraphMergeParams.DEFAULT_LLM_MODEL_MAX_ASYNC,
                GraphMergeParams.DEFAULT_APPLY_SOURCE_IDS_LIMIT,
                GraphMergeParams.DEFAULT_EMBEDDING_TOKEN_LIMIT,
                GraphMergeParams.DEFAULT_SUMMARY_CONTEXT_SIZE,
                GraphMergeParams.DEFAULT_SUMMARY_MAX_TOKENS,
                GraphMergeParams.DEFAULT_FORCE_LLM_SUMMARY_ON_MERGE,
                GraphMergeParams.DEFAULT_SOURCE_IDS_TRUNCATION,
                sourceFilePathsLimit,
                GraphMergeParams.DEFAULT_SOURCE_FILE_PATHS_PLACEHOLDER);
        return new GraphMergeContext(KB_ID, DOC_ID, SUMMARY_PROFILE, EMBED_PROFILE,
                LANGUAGE_ZH, params, OPERATOR, null);
    }

    /**
     * 打桩实体侧权威账本（旧向量行 chunk_ids），供幂等短路覆盖判定使用。
     *
     * @param entityName      实体名
     * @param ledgerChunkIds  账本来源列表
     */
    private void stubEntityLedger(String entityName, List<Long> ledgerChunkIds) {
        when(entityInfoVectorRepository.lockByKbIdAndName(KB_ID, entityName))
                .thenReturn(Optional.of(EntityInfoVector.create(KB_ID, entityName,
                        EntityInfoVector.buildContent(entityName, "ledger-desc"), ledgerChunkIds, OLD_VECTOR)));
    }

    /**
     * 打桩关系侧权威账本（旧向量行 chunk_ids），供幂等短路覆盖判定使用。
     *
     * @param sourceName     源实体名
     * @param targetName     目标实体名
     * @param ledgerChunkIds 账本来源列表
     */
    private void stubRelationLedger(String sourceName, String targetName, List<Long> ledgerChunkIds) {
        when(relationInfoVectorRepository.lockByKbIdAndUnorderedPair(KB_ID, sourceName, targetName))
                .thenReturn(Optional.of(RelationInfoVector.create(KB_ID, sourceName, targetName,
                        "ledger-content", ledgerChunkIds, OLD_VECTOR)));
    }

    /**
     * 实体向量行（Alice，内容与向量固定，账本取给定来源列表），
     * 供「普通读 / 加锁读连续返回不同账本」的模拟写回演化场景打桩使用。
     *
     * @param ledgerChunkIds 账本来源列表
     * @return 实体向量行
     */
    private EntityInfoVector aliceVectorWithLedger(List<Long> ledgerChunkIds) {
        return EntityInfoVector.create(KB_ID, ENTITY_ALICE,
                EntityInfoVector.buildContent(ENTITY_ALICE, "alice-desc"), ledgerChunkIds, OLD_VECTOR);
    }

    /**
     * 关系向量行（Alice—Bob，内容与向量固定，账本取给定来源列表），
     * 供「普通读 / 加锁读连续返回不同账本」的模拟写回演化场景打桩使用。
     *
     * @param ledgerChunkIds 账本来源列表
     * @return 关系向量行
     */
    private RelationInfoVector aliceBobVectorWithLedger(List<Long> ledgerChunkIds) {
        return RelationInfoVector.create(KB_ID, ENTITY_ALICE, ENTITY_BOB, "rel-content", ledgerChunkIds,
                OLD_VECTOR);
    }

    /**
     * 抽取产物实体（类型 PERSON，描述 {@code desc-名称}，单来源）。
     *
     * @param name     实体名
     * @param sourceId 来源分块ID
     * @return 新建实体节点
     */
    private EntityNode extractedEntity(String name, Long sourceId) {
        return EntityNode.create(KB_ID, name, ENTITY_TYPE_PERSON, "desc-" + name, sourceId, FILE_PATH);
    }

    /**
     * 抽取产物实体（显式描述，单来源）。
     *
     * @param name        实体名
     * @param description 实体描述
     * @param sourceId    来源分块ID
     * @return 新建实体节点
     */
    private EntityNode extractedEntityWithDesc(String name, String description, Long sourceId) {
        return EntityNode.create(KB_ID, name, ENTITY_TYPE_PERSON, description, sourceId, FILE_PATH);
    }

    /**
     * 库内既有实体节点（多来源，属性字段完整）。
     *
     * @param name        实体名
     * @param description 实体描述
     * @param sourceIds   既有来源分块ID列表
     * @return 实体节点
     */
    private EntityNode persistedEntity(String name, String description, List<Long> sourceIds) {
        return new EntityNode(KB_ID, name, new EntityProperties(ENTITY_TYPE_PERSON, description, sourceIds,
                FILE_PATH, Map.of(ENTITY_TYPE_PERSON, 1), List.of(description)));
    }

    /**
     * 库内既有实体节点（显式来源文件路径列表，供 filePaths 合并/截断场景使用）。
     *
     * @param name        实体名
     * @param description 实体描述
     * @param sourceIds   既有来源分块ID列表
     * @param filePaths   既有来源文件路径列表
     * @return 实体节点
     */
    private EntityNode persistedEntityWithFilePaths(String name, String description, List<Long> sourceIds,
                                                    List<String> filePaths) {
        return new EntityNode(KB_ID, name, new EntityProperties(ENTITY_TYPE_PERSON, description, sourceIds,
                filePaths, Map.of(ENTITY_TYPE_PERSON, 1), List.of(description)));
    }

    /**
     * 抽取产物实体（显式来源文件路径，单来源）。
     *
     * @param name     实体名
     * @param sourceId 来源分块ID
     * @param filePath 来源文件路径
     * @return 新建实体节点
     */
    private EntityNode extractedEntityWithFilePath(String name, Long sourceId, String filePath) {
        return EntityNode.create(KB_ID, name, ENTITY_TYPE_PERSON, "desc-" + name, sourceId, filePath);
    }

    /**
     * 抽取产物关系边（单来源）。
     *
     * @param source      源实体名
     * @param target      目标实体名
     * @param weight      片段权重
     * @param description 关系描述
     * @param keywords    关系关键词
     * @param sourceId    来源分块ID
     * @return 新建关系边
     */
    private RelationEdge extractedEdge(String source, String target, double weight, String description,
                                       List<String> keywords, Long sourceId) {
        return RelationEdge.create(KB_ID, source, target, weight, description, keywords, sourceId, FILE_PATH);
    }

    /**
     * 库内既有关系边（多来源，属性字段完整）。
     *
     * @param source      源实体名
     * @param target      目标实体名
     * @param weight      累计权重
     * @param description 关系描述
     * @param keywords    关系关键词
     * @param sourceIds   已贡献权重的来源列表
     * @return 关系边
     */
    private RelationEdge persistedEdge(String source, String target, double weight, String description,
                                       List<String> keywords, List<Long> sourceIds) {
        return new RelationEdge(KB_ID, source, target,
                new RelationProperties(weight, description, keywords, sourceIds, FILE_PATH));
    }

    /**
     * 库内既有关系边（显式来源文件路径列表，供 filePaths 合并/截断场景使用）。
     *
     * @param source      源实体名
     * @param target      目标实体名
     * @param weight      累计权重
     * @param description 关系描述
     * @param keywords    关系关键词
     * @param sourceIds   已贡献权重的来源列表
     * @param filePaths   既有来源文件路径列表
     * @return 关系边
     */
    private RelationEdge persistedEdgeWithFilePaths(String source, String target, double weight,
                                                    String description, List<String> keywords,
                                                    List<Long> sourceIds, List<String> filePaths) {
        return new RelationEdge(KB_ID, source, target,
                new RelationProperties(weight, description, keywords, sourceIds, filePaths));
    }

    /**
     * 抽取产物关系边（显式来源文件路径，单来源）。
     *
     * @param source      源实体名
     * @param target      目标实体名
     * @param weight      片段权重
     * @param description 关系描述
     * @param keywords    关系关键词
     * @param sourceId    来源分块ID
     * @param filePath    来源文件路径
     * @return 新建关系边
     */
    private RelationEdge extractedEdgeWithFilePath(String source, String target, double weight,
                                                   String description, List<String> keywords, Long sourceId,
                                                   String filePath) {
        return RelationEdge.create(KB_ID, source, target, weight, description, keywords, sourceId, filePath);
    }

    /**
     * 带方向的边存储键（用于状态化仓储桩）。
     *
     * @param source 源实体名
     * @param target 目标实体名
     * @return 存储键
     */
    private String edgeKey(String source, String target) {
        return source + "->" + target;
    }

    /**
     * 构造连续递增的来源ID列表，用于验证默认上限放宽后不再截断。
     *
     * @param firstId 起始来源ID
     * @param count   来源条数
     * @return 长度为 count 的连续来源ID列表
     */
    private List<Long> sequentialSources(long firstId, int count) {
        final List<Long> sourceIds = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            sourceIds.add(firstId + index);
        }
        return sourceIds;
    }

    /**
     * 上下文为空：入口直接拒绝，不触碰任何仓储。
     */
    @Test
    public void should_throwIllegalArgumentException_when_mergeNodesAndEdges_given_nullContext() {
        // given：上下文为 null

        // when：执行合并
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> graphMergeService.mergeNodesAndEdges(null, List.of(), List.of()));

        // then：消息匹配且全部仓储零交互
        assertEquals("图合并上下文不能为空", thrown.getMessage(), "空上下文应给出明确拒绝原因");
        verifyNoInteractions(entityNodeRepository, relationEdgeRepository, entityInfoVectorRepository,
                relationInfoVectorRepository);
    }

    /**
     * 取消检查命中：合并中止且零图写入（取消语义）。
     */
    @Test
    public void should_throwIllegalStateException_when_mergeNodesAndEdges_given_cancelledContext() {
        // given：取消检查器返回 true
        GraphMergeContext cancelledCtx = new GraphMergeContext(KB_ID, DOC_ID, SUMMARY_PROFILE, EMBED_PROFILE,
                LANGUAGE_ZH, null, OPERATOR, () -> true);

        // when：执行合并
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> graphMergeService.mergeNodesAndEdges(cancelledCtx, List.of(), List.of()));

        // then：中止消息匹配且零图写入
        assertEquals("摄入已取消，图合并中止", thrown.getMessage(), "取消中止应给出明确消息");
        verifyNoInteractions(entityNodeRepository, relationEdgeRepository, entityInfoVectorRepository,
                relationInfoVectorRepository);
    }

    /**
     * 空批次：写前锚点机制已退役，空批次不落任何写入，报告全零。
     */
    @Test
    public void should_returnEmptyReport_when_mergeNodesAndEdges_given_emptyBatch() {
        // given：默认上下文与空抽取产物

        // when：执行合并
        GraphMergeReport report = graphMergeService.mergeNodesAndEdges(defaultCtx(), List.of(), List.of());

        // then：全零报告，且全部图仓储零交互（不再写任何锚点标记）
        assertEquals(GraphMergeReport.empty(), report, "空批次应返回全零报告");
        verifyNoInteractions(entityNodeRepository, relationEdgeRepository, entityInfoVectorRepository,
                relationInfoVectorRepository);
    }

    /**
     * 自环边兜底防御：聚合阶段丢弃并计数（扩展流程 5b）。
     *
     * <p>{@link RelationEdge} 构造器已拒自环，正常途径无法构造自环边，
     * 故以 mock 模拟旁路入口（Mockito 5 inline mockmaker 支持 final record）。</p>
     */
    @Test
    public void should_countSelfLoopDropped_when_mergeNodesAndEdges_given_selfLoopEdge() {
        // given：源目标同名的自环边（构造器旁路，仅打桩两端点访问器）
        RelationEdge selfLoop = mock(RelationEdge.class);
        when(selfLoop.sourceName()).thenReturn(ENTITY_ALICE);
        when(selfLoop.targetName()).thenReturn(ENTITY_ALICE);

        // when：执行合并
        GraphMergeReport report = graphMergeService.mergeNodesAndEdges(defaultCtx(),
                List.of(), List.of(selfLoop));

        // then：自环丢弃计数为 1，其余计数为零
        assertEquals(new GraphMergeReport(0, 0, 0, 0, 1), report, "自环边应在聚合阶段被丢弃并计数");
    }

    /**
     * 关系模型不变量：同端点建边被构造器直接拒绝。
     */
    @Test
    public void should_throwIllegalArgumentException_when_RelationEdgeCreate_given_sameSourceAndTarget() {
        // given：源目标同为 Alice 的建边入参

        // when：调用工厂方法
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> RelationEdge.create(KB_ID, ENTITY_ALICE, ENTITY_ALICE, DEFAULT_EDGE_WEIGHT,
                        EDGE_DESC_NORMAL, List.of("knows"), SOURCE_101, FILE_PATH));

        // then：消息明示拒绝自环
        assertEquals("关系拒绝自环：" + ENTITY_ALICE, thrown.getMessage(), "构造器应拒绝自环并指名实体");
    }

    /**
     * 批内平行边融合：同无向端点对两条边归一为单条目，权重按贡献记录全额累计，落库为单行。
     *
     * <p>方向归一在抽取聚合出口完成（上游恒为字典序），此处批内骨架即归一方向 Alice→Bob；
     * 关系合并只读写边行与边向量，不再触碰实体行（故无端点仓储打桩）。</p>
     */
    @Test
    public void should_mergeParallelEdges_when_mergeNodesAndEdges_given_oppositeDirectionsInBatch() {
        // given：批内 Alice→Bob(101) 与 Bob→Alice(102) 两条边（后者模拟旁路传入的反向条目）

        // when：执行合并
        GraphMergeReport report = graphMergeService.mergeNodesAndEdges(defaultCtx(), List.of(),
                List.of(extractedEdge(ENTITY_ALICE, ENTITY_BOB, DEFAULT_EDGE_WEIGHT, EDGE_DESC_NORMAL,
                                List.of("knows"), SOURCE_101),
                        extractedEdge(ENTITY_BOB, ENTITY_ALICE, DEFAULT_EDGE_WEIGHT, EDGE_DESC_NORMAL,
                                List.of("knows"), SOURCE_102)));

        // then：仅写一条边，权重 2.0、落库方向为字典序归一方向、来源累积 [101,102]
        ArgumentCaptor<List<RelationEdge>> captor = ArgumentCaptor.captor();
        verify(relationEdgeRepository).upsertAll(captor.capture(), eq(OPERATOR));
        assertEquals(1, captor.getValue().size(), "平行边应融合为单条写回");
        RelationEdge merged = captor.getValue().get(0);
        assertEquals(2.0, merged.properties().weight(), "批内平行边权重应累计");
        assertEquals(List.of(ENTITY_ALICE, ENTITY_BOB), merged.normalizedPair(), "落库方向应为字典序归一方向");
        assertEquals(List.of(SOURCE_101, SOURCE_102), merged.properties().sourceIds(), "来源应去重累积");
        assertEquals(new GraphMergeReport(0, 0, 1, 0, 0), report, "关系写入计数应为 1");
    }

    /**
     * belongs_to 权重口径：同一来源批内双抽按全额累加（LightRAG 批内不去重 quirk 同构）。
     *
     * <p>合并端过滤基准为「写回事务内加锁读到的向量账本快照」而非循环内追加的账本，故新库首写时
     * 同一来源的两条记录各自全额计入：10.0 + 10.0 = 20.0；该 quirk 与 LightRAG 合并端实况一致，
     * 照抄不修。</p>
     */
    @Test
    public void should_accumulateBelongsToWeight_when_mergeNodesAndEdges_given_repeatedBelongsToSameSource() {
        // given：两条同来源（301）的 belongs_to 边（反向一条模拟旁路入口），权重 10

        // when：执行合并
        graphMergeService.mergeNodesAndEdges(defaultCtx(), List.of(),
                List.of(extractedEdge(ENTITY_DOC, ENTITY_FOLDER, BELONGS_TO_WEIGHT, "doc-folder-rel",
                                List.of("belongs_to"), SOURCE_301),
                        extractedEdge(ENTITY_FOLDER, ENTITY_DOC, BELONGS_TO_WEIGHT, "doc-folder-rel",
                                List.of("belongs_to"), SOURCE_301)));

        // then：写回权重全额累加为 20.0（批内不去重 quirk，与 LightRAG 同构）
        ArgumentCaptor<List<RelationEdge>> captor = ArgumentCaptor.captor();
        verify(relationEdgeRepository).upsertAll(captor.capture(), eq(OPERATOR));
        assertEquals(2 * BELONGS_TO_WEIGHT, captor.getValue().get(0).properties().weight(),
                "批内同一来源双抽应全额累加两次（LightRAG 同构 quirk）");
        assertEquals(List.of(SOURCE_301), captor.getValue().get(0).properties().sourceIds(),
                "来源账本仍应去重为单条");
    }

    /**
     * 残留折叠机制已退役（新增用例 8）：历史双向行并存时，仅按无向端点对复用保留行计权——
     * 反向行的累计权重 MUST NOT 被折叠进来，亦 MUST NOT 发生任何物理删除。
     */
    @Test
    public void should_excludeResidueFold_when_mergeNodesAndEdges_given_legacyReverseRowPresent() {
        // given：库内并存 Alice→Bob(weight=1.0，来源 101) 与 Bob→Alice(weight=5.0，来源 201) 两行历史数据，
        //        向量账本 [101]，本批为未记账来源 102（贡献 1.0）
        when(relationEdgeRepository.lockByKbIdAndUnorderedPair(KB_ID, ENTITY_ALICE, ENTITY_BOB))
                .thenReturn(List.of(
                        persistedEdge(ENTITY_ALICE, ENTITY_BOB, DEFAULT_EDGE_WEIGHT, "old-rel",
                                List.of("knows"), List.of(SOURCE_101)),
                        persistedEdge(ENTITY_BOB, ENTITY_ALICE, 5.0, "old-rel-rev",
                                List.of("rev"), List.of(SOURCE_201))));
        stubRelationLedger(ENTITY_ALICE, ENTITY_BOB, List.of(SOURCE_101));

        // when：执行合并
        graphMergeService.mergeNodesAndEdges(defaultCtx(), List.of(),
                List.of(extractedEdge(ENTITY_ALICE, ENTITY_BOB, DEFAULT_EDGE_WEIGHT, EDGE_DESC_NORMAL,
                        List.of("knows"), SOURCE_102)));

        // then：weight = 保留行 1.0 + 未记账来源 102 全额 1.0，反向行累计值 5.0 不参与计权
        ArgumentCaptor<List<RelationEdge>> captor = ArgumentCaptor.captor();
        verify(relationEdgeRepository).upsertAll(captor.capture(), eq(OPERATOR));
        RelationEdge finalEdge = captor.getValue().get(0);
        assertEquals(2 * DEFAULT_EDGE_WEIGHT, finalEdge.properties().weight(),
                "反向残留行的累计权重 MUST NOT 被折叠进保留行");
        assertEquals(List.of(ENTITY_ALICE, ENTITY_BOB), finalEdge.normalizedPair(), "仍命中同一无向端点对");
        assertEquals(List.of(SOURCE_101, SOURCE_102), finalEdge.properties().sourceIds(),
                "展示来源列应为既有来源与本批来源的并集");
        // then：关系仓储交互仅「读 + upsert」，不存在任何删除类写交互
        verify(relationEdgeRepository).findByKbIdAndUnorderedPair(KB_ID, ENTITY_ALICE, ENTITY_BOB);
        verify(relationEdgeRepository).lockByKbIdAndUnorderedPair(KB_ID, ENTITY_ALICE, ENTITY_BOB);
        verifyNoMoreInteractions(relationEdgeRepository);
    }

    /**
     * 仅存历史反向行时复用同一条目：按无向端点对命中既有行计权，MUST NOT 另写第二方向行。
     */
    @Test
    public void should_reuseSingleRow_when_mergeOneEdge_given_onlyLegacyReverseRowExists() {
        // given：库内仅有 Bob→Alice 历史行（weight=1.0、来源 201），本批报 Alice→Bob 新来源 303
        when(relationEdgeRepository.lockByKbIdAndUnorderedPair(KB_ID, ENTITY_ALICE, ENTITY_BOB))
                .thenReturn(List.of(persistedEdge(ENTITY_BOB, ENTITY_ALICE, DEFAULT_EDGE_WEIGHT,
                        "legacy-rel", List.of("knows"), List.of(SOURCE_201))));

        // when：执行合并
        graphMergeService.mergeNodesAndEdges(defaultCtx(), List.of(),
                List.of(extractedEdge(ENTITY_ALICE, ENTITY_BOB, DEFAULT_EDGE_WEIGHT, EDGE_DESC_NORMAL,
                        List.of("knows"), SOURCE_303)));

        // then：同一无向端点对仍只落一行，权重 1.0 + 1.0，描述沿用既有行非空者
        ArgumentCaptor<List<RelationEdge>> captor = ArgumentCaptor.captor();
        verify(relationEdgeRepository).upsertAll(captor.capture(), eq(OPERATOR));
        assertEquals(1, captor.getValue().size(), "同一无向端点对 MUST 复用单条目");
        RelationEdge finalEdge = captor.getValue().get(0);
        assertEquals(List.of(ENTITY_ALICE, ENTITY_BOB), finalEdge.normalizedPair(), "端点身份仍为该无向对");
        assertEquals(2 * DEFAULT_EDGE_WEIGHT, finalEdge.properties().weight(), "新来源应完整贡献权重");
        assertEquals("legacy-rel", finalEdge.properties().description(), "描述应沿用既有行非空者");
        verify(relationEdgeRepository).findByKbIdAndUnorderedPair(KB_ID, ENTITY_ALICE, ENTITY_BOB);
        verify(relationEdgeRepository).lockByKbIdAndUnorderedPair(KB_ID, ENTITY_ALICE, ENTITY_BOB);
        verifyNoMoreInteractions(relationEdgeRepository);
    }

    /**
     * 并发合并同一关系权重与账本不丢失（spec Scenario）：两个批次的写回加锁读交错——第一批写回前读到
     * weight=1.0 / 账本 [1]，第二批读到的是第一批写回后的 weight=2.0 / 账本 [1,101]；
     * 断言最终 weight 等于按两批全部来源全额累加的结果、source_ids 为两批来源的并集。
     */
    @Test
    public void should_accumulateWeightOfBothBatches_when_mergeOneEdge_given_concurrentBatchesInterleaved() {
        // given：加锁读连续返回值模拟「第一批写回前 → 第一批写回后」的图边与边向量账本演化
        //（关系合并只读写边行与边向量，端点实体行不参与，故无端点打桩）
        when(relationEdgeRepository.lockByKbIdAndUnorderedPair(KB_ID, ENTITY_ALICE, ENTITY_BOB))
                .thenReturn(List.of(persistedEdge(ENTITY_ALICE, ENTITY_BOB, DEFAULT_EDGE_WEIGHT,
                                EDGE_DESC_NORMAL, List.of("knows"), List.of(SOURCE_1))),
                        List.of(persistedEdge(ENTITY_ALICE, ENTITY_BOB, 2 * DEFAULT_EDGE_WEIGHT,
                                EDGE_DESC_NORMAL, List.of("knows"), List.of(SOURCE_1, SOURCE_101))));
        when(relationInfoVectorRepository.lockByKbIdAndUnorderedPair(KB_ID, ENTITY_ALICE, ENTITY_BOB))
                .thenReturn(Optional.of(aliceBobVectorWithLedger(List.of(SOURCE_1))),
                        Optional.of(aliceBobVectorWithLedger(List.of(SOURCE_1, SOURCE_101))));

        // when：两批（来源 101 / 102，各权重 1.0）先后合并同一无向端点对
        graphMergeService.mergeNodesAndEdges(defaultCtx(), List.of(),
                List.of(extractedEdge(ENTITY_ALICE, ENTITY_BOB, DEFAULT_EDGE_WEIGHT, EDGE_DESC_NORMAL,
                        List.of("knows"), SOURCE_101)));
        graphMergeService.mergeNodesAndEdges(defaultCtx(), List.of(),
                List.of(extractedEdge(ENTITY_ALICE, ENTITY_BOB, DEFAULT_EDGE_WEIGHT, EDGE_DESC_NORMAL,
                        List.of("knows"), SOURCE_102)));

        // then：最终 weight = 1.0（既有）+ 1.0（批一）+ 1.0（批二），source_ids 为两批并集
        ArgumentCaptor<List<RelationEdge>> captor = ArgumentCaptor.captor();
        verify(relationEdgeRepository, times(2)).upsertAll(captor.capture(), eq(OPERATOR));
        RelationEdge finalEdge = captor.getAllValues().get(1).get(0);
        assertEquals(3 * DEFAULT_EDGE_WEIGHT, finalEdge.properties().weight(),
                "最终 weight 应为按两批全部来源全额累加的结果（含既有账本）");
        assertEquals(List.of(SOURCE_1, SOURCE_101, SOURCE_102), finalEdge.properties().sourceIds(),
                "最终 source_ids 应为两批来源的并集，MUST NOT 出现某一批被整段覆盖");
    }

    /**
     * 重喂幂等（spec Scenario）：与<b>向量账本</b>完全相同的来源被重复提交合并时，过滤基准命中该来源，
     * 增量恒为 0，断言 weight 与账本均不变。
     *
     * <p>本用例刻意让旧行关键词不含本批关键词，绕开幂等短路，使合并公式本身被真正执行。</p>
     */
    @Test
    public void should_keepWeightAndLedgerUnchanged_when_mergeOneEdge_given_sameSourcesReplayed() {
        // given：既有边 weight=2.0（展示来源 [1]）、向量账本 [1]、关键词 [knows]；
        //        本批重喂同来源 1（片段权重 5.0、关键词 [likes]）
        when(relationEdgeRepository.lockByKbIdAndUnorderedPair(KB_ID, ENTITY_ALICE, ENTITY_BOB))
                .thenReturn(List.of(persistedEdge(ENTITY_ALICE, ENTITY_BOB, 2 * DEFAULT_EDGE_WEIGHT,
                        EDGE_DESC_NORMAL, List.of("knows"), List.of(SOURCE_1))));
        stubRelationLedger(ENTITY_ALICE, ENTITY_BOB, List.of(SOURCE_1));

        // when：重喂同一来源
        graphMergeService.mergeNodesAndEdges(defaultCtx(), List.of(),
                List.of(extractedEdge(ENTITY_ALICE, ENTITY_BOB, 5.0, EDGE_DESC_NORMAL,
                        List.of("likes"), SOURCE_1)));

        // then：weight 保持不变（同来源增量为 0），账本不变
        ArgumentCaptor<List<RelationEdge>> captor = ArgumentCaptor.captor();
        verify(relationEdgeRepository).upsertAll(captor.capture(), eq(OPERATOR));
        assertEquals(2 * DEFAULT_EDGE_WEIGHT, captor.getValue().get(0).properties().weight(),
                "同来源重喂的增量应为 0，weight 保持不变");
        assertEquals(List.of(SOURCE_1), captor.getValue().get(0).properties().sourceIds(), "账本应保持不变");
    }

    /**
     * 混合权重边合并翻正（spec Scenario）：图内已存在仅含文档级来源 m1（belongs_to，weight=10.0）的
     * 关系边，新文档以 3 个普通文本 chunk（各贡献 1.0）合入同一条关系 → 合并后 weight SHALL 为
     * 13.0（10.0 + 3×1.0），SHALL NOT 出现均摊公式的 19.75。
     */
    @Test
    public void should_mergeToFullAccumulatedWeight_when_mergeOneEdge_given_mixedWeightLedgerAndPlainChunks() {
        // given：既有边 weight=10.0、向量账本 [101]（文档级来源 m1）；关键词与本批不重叠以绕开幂等短路
        when(relationEdgeRepository.lockByKbIdAndUnorderedPair(KB_ID, ENTITY_ALICE, ENTITY_BOB))
                .thenReturn(List.of(persistedEdge(ENTITY_ALICE, ENTITY_BOB, BELONGS_TO_WEIGHT,
                        EDGE_DESC_BELONGS_TO, List.of(KEYWORD_BELONGS_TO), List.of(SOURCE_101))));
        stubRelationLedger(ENTITY_ALICE, ENTITY_BOB, List.of(SOURCE_101));

        // when：3 个普通文本 chunk（102/103/104）各以权重 1.0 合入同一条关系
        graphMergeService.mergeNodesAndEdges(defaultCtx(), List.of(), plainChunkEdges());

        // then：weight = 10.0 + 3×1.0 = 13.0，且不等于均摊公式的 19.75
        ArgumentCaptor<List<RelationEdge>> captor = ArgumentCaptor.captor();
        verify(relationEdgeRepository).upsertAll(captor.capture(), eq(OPERATOR));
        double mergedWeight = captor.getValue().get(0).properties().weight();
        assertEquals(BELONGS_TO_WEIGHT + PLAIN_CHUNK_COUNT * DEFAULT_EDGE_WEIGHT, mergedWeight,
                "混合权重边应按逐来源全额累加为 13.0");
        assertNotEquals(APPORTIONED_MIXED_WEIGHT_DRIFT, mergedWeight,
                "MUST NOT 出现均摊公式的 19.75");
        assertEquals(List.of(SOURCE_101, SOURCE_102, SOURCE_103, SOURCE_104),
                captor.getValue().get(0).properties().sourceIds(), "账本应旧值优先并集");
    }

    /**
     * 重喂幂等（spec Scenario）：对已翻正的 13.0 边重喂同批 3 个 chunk，全部来源已在账本中被过滤，
     * 结果 SHALL 仍为 13.0（weight 与账本均不变）。
     */
    @Test
    public void should_keepFlippedWeight_when_mergeOneEdge_given_replayOfThreePlainChunks() {
        // given：已翻正边 weight=13.0、向量账本 [101,102,103,104]；
        //        重喂同批 3 chunk（关键词不重叠以绕开幂等短路，使权重公式被真正执行）
        when(relationEdgeRepository.lockByKbIdAndUnorderedPair(KB_ID, ENTITY_ALICE, ENTITY_BOB))
                .thenReturn(List.of(persistedEdge(ENTITY_ALICE, ENTITY_BOB,
                        BELONGS_TO_WEIGHT + PLAIN_CHUNK_COUNT * DEFAULT_EDGE_WEIGHT, EDGE_DESC_BELONGS_TO,
                        List.of(KEYWORD_BELONGS_TO),
                        List.of(SOURCE_101, SOURCE_102, SOURCE_103, SOURCE_104))));
        stubRelationLedger(ENTITY_ALICE, ENTITY_BOB,
                List.of(SOURCE_101, SOURCE_102, SOURCE_103, SOURCE_104));

        // when：重喂同批 3 个 chunk
        graphMergeService.mergeNodesAndEdges(defaultCtx(), List.of(), plainChunkEdges());

        // then：weight 仍为 13.0、账本不变（全部来源被过滤，新增为 0）
        ArgumentCaptor<List<RelationEdge>> captor = ArgumentCaptor.captor();
        verify(relationEdgeRepository).upsertAll(captor.capture(), eq(OPERATOR));
        assertEquals(BELONGS_TO_WEIGHT + PLAIN_CHUNK_COUNT * DEFAULT_EDGE_WEIGHT,
                captor.getValue().get(0).properties().weight(), "重喂全部来源被过滤，weight 应保持不变");
        assertEquals(List.of(SOURCE_101, SOURCE_102, SOURCE_103, SOURCE_104),
                captor.getValue().get(0).properties().sourceIds(), "账本应保持不变");
    }

    /**
     * 构造同一条关系上 3 个普通文本 chunk 的抽取产物（权重各 1.0、来源 102/103/104）。
     *
     * @return 批内抽取边列表
     */
    private List<RelationEdge> plainChunkEdges() {
        return List.of(
                extractedEdge(ENTITY_ALICE, ENTITY_BOB, DEFAULT_EDGE_WEIGHT, EDGE_DESC_PLAIN,
                        List.of(KEYWORD_PLAIN), SOURCE_102),
                extractedEdge(ENTITY_ALICE, ENTITY_BOB, DEFAULT_EDGE_WEIGHT, EDGE_DESC_PLAIN,
                        List.of(KEYWORD_PLAIN), SOURCE_103),
                extractedEdge(ENTITY_ALICE, ENTITY_BOB, DEFAULT_EDGE_WEIGHT, EDGE_DESC_PLAIN,
                        List.of(KEYWORD_PLAIN), SOURCE_104));
    }

    /**
     * 实体幂等短路：旧向量账本 chunk_ids 覆盖本批来源时跳过写入（账本为权威依据）。
     */
    @Test
    public void should_skipEntity_when_mergeOneEntity_given_vectorLedgerCoversSourceIds() {
        // given：事务外普通读即可见旧行，旧向量账本 [101,102] 完全覆盖本批来源 [101]
        when(entityNodeRepository.findByKbIdAndName(KB_ID, ENTITY_ALICE))
                .thenReturn(Optional.of(persistedEntity(ENTITY_ALICE, "old-desc",
                        List.of(SOURCE_101, SOURCE_102))));
        when(entityInfoVectorRepository.findByKbIdAndName(KB_ID, ENTITY_ALICE))
                .thenReturn(Optional.of(EntityInfoVector.create(KB_ID, ENTITY_ALICE,
                        EntityInfoVector.buildContent(ENTITY_ALICE, "ledger-desc"),
                        List.of(SOURCE_101, SOURCE_102), OLD_VECTOR)));

        // when：执行合并
        GraphMergeReport report = graphMergeService.mergeNodesAndEdges(ctxWithSourceLimit(2),
                List.of(extractedEntity(ENTITY_ALICE, SOURCE_101)), List.of());

        // then：短路跳过，零写入零远程调用
        assertEquals(new GraphMergeReport(0, 1, 0, 0, 0), report, "已覆盖来源应短路跳过实体写入");
        verify(entityNodeRepository, never()).upsert(any(EntityNode.class), any());
        verify(entityInfoVectorRepository, never()).upsert(any(EntityInfoVector.class), any());
        verify(embeddingClient, never()).embed(anyString(), anyString());
        verify(descriptionSummarizer, never()).summarize(any(), any(), any(), any());
    }

    /**
     * 实体正常写入：旧向量账本缺失时 fail-open（判为未覆盖），必须写回。
     */
    @Test
    public void should_writeEntity_when_mergeOneEntity_given_vectorLedgerMissing() {
        // given：上限 2，旧行存在但未打桩向量账本（仓储桩返回 Optional.empty）→ 账本缺失不短路
        when(entityNodeRepository.lockByKbIdAndNames(KB_ID, List.of(ENTITY_ALICE)))
                .thenReturn(List.of(persistedEntity(ENTITY_ALICE, "old-desc", List.of(SOURCE_101))));

        // when：执行合并
        GraphMergeReport report = graphMergeService.mergeNodesAndEdges(ctxWithSourceLimit(2),
                List.of(extractedEntity(ENTITY_ALICE, SOURCE_102)), List.of());

        // then：实体写入计数 1，节点与向量各写回一次
        assertEquals(new GraphMergeReport(1, 0, 0, 0, 0), report, "账本缺失应 fail-open 写回实体");
        verify(entityNodeRepository).upsert(any(EntityNode.class), eq(OPERATOR));
        verify(entityInfoVectorRepository).upsert(any(EntityInfoVector.class), eq(OPERATOR));
    }

    /**
     * 向量 chunkIds 累积：写回向量的 chunkIds 为旧行与新来源的保序并集（旧优先）。
     */
    @Test
    public void should_accumulateChunkIds_when_mergeOneEntity_given_existingVectorChunkIds() {
        // given：旧向量行 chunkIds=[555]，旧节点 source_ids=[101]，本批来源 102
        when(entityNodeRepository.lockByKbIdAndNames(KB_ID, List.of(ENTITY_ALICE)))
                .thenReturn(List.of(persistedEntity(ENTITY_ALICE, "old-desc", List.of(SOURCE_101))));
        when(entityInfoVectorRepository.lockByKbIdAndName(KB_ID, ENTITY_ALICE))
                .thenReturn(Optional.of(EntityInfoVector.create(KB_ID, ENTITY_ALICE,
                        "Alice\nstale-description", List.of(SOURCE_555), OLD_VECTOR)));

        // when：执行合并
        graphMergeService.mergeNodesAndEdges(ctxWithSourceLimit(2),
                List.of(extractedEntity(ENTITY_ALICE, SOURCE_102)), List.of());

        // then：chunkIds 累积为 [555,101,102]（旧优先，来源并入）
        ArgumentCaptor<EntityInfoVector> captor = ArgumentCaptor.captor();
        verify(entityInfoVectorRepository).upsert(captor.capture(), eq(OPERATOR));
        assertEquals(List.of(SOURCE_555, SOURCE_101, SOURCE_102), captor.getValue().chunkIds(),
                "向量 chunkIds 应为旧行与来源的保序并集");
    }

    /**
     * 同名实体累积不重复（spec Scenario）：新旧片段共享同一实体名时，旧节点的 source_ids 与向量
     * chunk_ids SHALL 与新来源累积并入，重复描述 SHALL NOT 累加（跨新旧精确去重）。
     */
    @Test
    public void should_unionLedgersAndDedupeDescriptions_when_mergeOneEntity_given_sharedDescription() {
        // given：旧节点描述 "same-desc"/来源 [101]、旧向量账本 chunkIds=[555]；本批同名实体描述同为 "same-desc"/来源 102
        when(entityNodeRepository.findByKbIdAndName(KB_ID, ENTITY_ALICE))
                .thenReturn(Optional.of(persistedEntity(ENTITY_ALICE, SHARED_ENTITY_DESC, List.of(SOURCE_101))));
        when(entityNodeRepository.lockByKbIdAndNames(KB_ID, List.of(ENTITY_ALICE)))
                .thenReturn(List.of(persistedEntity(ENTITY_ALICE, SHARED_ENTITY_DESC, List.of(SOURCE_101))));
        when(entityInfoVectorRepository.lockByKbIdAndName(KB_ID, ENTITY_ALICE))
                .thenReturn(Optional.of(aliceVectorWithLedger(List.of(SOURCE_555))));

        // when：执行合并
        graphMergeService.mergeNodesAndEdges(defaultCtx(),
                List.of(extractedEntityWithDesc(ENTITY_ALICE, SHARED_ENTITY_DESC, SOURCE_102)), List.of());

        // then：描述候选跨新旧精确去重为单条（重复描述不累加）
        ArgumentCaptor<List<String>> descCaptor = ArgumentCaptor.captor();
        verify(descriptionSummarizer).summarize(eq(defaultCtx()), eq(ENTITY_ALICE), eq(ENTITY_TYPE_PERSON),
                descCaptor.capture());
        assertEquals(List.of(SHARED_ENTITY_DESC), descCaptor.getValue(), "重复描述 SHALL NOT 累加");
        ArgumentCaptor<EntityNode> nodeCaptor = ArgumentCaptor.captor();
        verify(entityNodeRepository).upsert(nodeCaptor.capture(), eq(OPERATOR));
        assertEquals(List.of(SHARED_ENTITY_DESC), nodeCaptor.getValue().properties().descriptions(),
                "写回描述列表不应因重复描述膨胀");
        // then：图行 source_ids 与向量 chunk_ids 均为新旧累积并集
        assertEquals(List.of(SOURCE_101, SOURCE_102), nodeCaptor.getValue().properties().sourceIds(),
                "旧节点 source_ids 应与新来源累积并入");
        ArgumentCaptor<EntityInfoVector> vectorCaptor = ArgumentCaptor.captor();
        verify(entityInfoVectorRepository).upsert(vectorCaptor.capture(), eq(OPERATOR));
        assertEquals(List.of(SOURCE_555, SOURCE_101, SOURCE_102), vectorCaptor.getValue().chunkIds(),
                "旧节点 chunk_ids 应与新来源累积并入");
    }

    /**
     * 同名实体累积不重复（spec Scenario 的 AND 子句）：空描述 SHALL 兜底为 {@code Entity {name}}，
     * 且图行描述与向量内容使用同一兜底落值。
     */
    @Test
    public void should_fallbackToEntityNamePlaceholder_when_mergeOneEntity_given_blankDescription() {
        // given：本批 Alice 描述为空白、无旧节点（无旧账本 → 不短路）

        // when：执行合并
        graphMergeService.mergeNodesAndEdges(defaultCtx(),
                List.of(extractedEntityWithDesc(ENTITY_ALICE, "   ", SOURCE_101)), List.of());

        // then：图行描述与向量内容均兜底为 Entity {name}
        ArgumentCaptor<EntityNode> nodeCaptor = ArgumentCaptor.captor();
        verify(entityNodeRepository).upsert(nodeCaptor.capture(), eq(OPERATOR));
        assertEquals(ENTITY_DESC_PLACEHOLDER_PREFIX + ENTITY_ALICE,
                nodeCaptor.getValue().properties().description(), "空描述 SHALL 兜底为 Entity {name}");
        ArgumentCaptor<EntityInfoVector> vectorCaptor = ArgumentCaptor.captor();
        verify(entityInfoVectorRepository).upsert(vectorCaptor.capture(), eq(OPERATOR));
        assertEquals(EntityInfoVector.buildContent(ENTITY_ALICE, ENTITY_DESC_PLACEHOLDER_PREFIX + ENTITY_ALICE),
                vectorCaptor.getValue().content(), "向量内容应使用同一兜底描述");
    }

    /**
     * 并发合并同一实体账本不丢失（spec Scenario）：两个批次的写回加锁读交错——第一批写回前读到账本 [1]，
     * 第二批写回前读到的是第一批写回后的 [1,101]；断言最终写入的账本为两批贡献的并集，无批次贡献缺失。
     *
     * <p>事务外普通读在本用例中不打桩（fail-open 不短路），写回值恒以写回事务内加锁读到的当前账本重算。</p>
     */
    @Test
    public void should_unionLedgerWithoutLosingContribution_when_mergeOneEntity_given_concurrentBatchesInterleaved() {
        // given：加锁读连续返回值模拟「第一批写回前 → 第一批写回后」的账本演化
        when(entityNodeRepository.lockByKbIdAndNames(KB_ID, List.of(ENTITY_ALICE)))
                .thenReturn(List.of(persistedEntity(ENTITY_ALICE, "alice-desc", List.of(SOURCE_1))),
                        List.of(persistedEntity(ENTITY_ALICE, "alice-desc",
                                List.of(SOURCE_1, SOURCE_101))));
        when(entityInfoVectorRepository.lockByKbIdAndName(KB_ID, ENTITY_ALICE))
                .thenReturn(Optional.of(aliceVectorWithLedger(List.of(SOURCE_1))),
                        Optional.of(aliceVectorWithLedger(List.of(SOURCE_1, SOURCE_101))));

        // when：两批（来源 101 / 102）先后合并同一实体名
        graphMergeService.mergeNodesAndEdges(defaultCtx(),
                List.of(extractedEntity(ENTITY_ALICE, SOURCE_101)), List.of());
        graphMergeService.mergeNodesAndEdges(defaultCtx(),
                List.of(extractedEntity(ENTITY_ALICE, SOURCE_102)), List.of());

        // then：两次写回的账本依次为 [1,101] 与两批并集 [1,101,102]
        ArgumentCaptor<EntityInfoVector> captor = ArgumentCaptor.captor();
        verify(entityInfoVectorRepository, times(2)).upsert(captor.capture(), eq(OPERATOR));
        List<EntityInfoVector> written = captor.getAllValues();
        assertEquals(List.of(SOURCE_1, SOURCE_101), written.get(0).chunkIds(),
                "第一批写回应并入自身贡献，不与既有账本互相覆盖");
        assertEquals(List.of(SOURCE_1, SOURCE_101, SOURCE_102), written.get(1).chunkIds(),
                "第二批写回的账本应为两批贡献的并集，任一成功批次的来源均不缺失");
    }

    /**
     * 不依赖进程内锁保证正确性（spec Scenario）：两个服务实例各自持有独立的条纹锁数组（模拟两进程各持
     * 自身进程内锁），且两者的事务外读取都发生在任一写回之前（普通读快照恒为过期账本 [1]）；
     * 断言最终账本仍为两批贡献的并集——正确性来源于写回事务内重新加锁读到的当前账本，而非进程内锁。
     */
    @Test
    public void should_unionLedgerWithoutStripeLockShared_when_mergeOneEntity_given_twoInstancesAndStaleSnapshot() {
        // given：事务外普通读恒返回写回前的过期账本 [1]，写回事务内加锁读返回逐次演化的当前账本
        when(entityNodeRepository.findByKbIdAndName(KB_ID, ENTITY_ALICE))
                .thenReturn(Optional.of(persistedEntity(ENTITY_ALICE, "alice-desc", List.of(SOURCE_1))));
        when(entityNodeRepository.lockByKbIdAndNames(KB_ID, List.of(ENTITY_ALICE)))
                .thenReturn(List.of(persistedEntity(ENTITY_ALICE, "alice-desc", List.of(SOURCE_1))),
                        List.of(persistedEntity(ENTITY_ALICE, "alice-desc", List.of(SOURCE_1, SOURCE_101))));
        when(entityInfoVectorRepository.lockByKbIdAndName(KB_ID, ENTITY_ALICE))
                .thenReturn(Optional.of(aliceVectorWithLedger(List.of(SOURCE_1))),
                        Optional.of(aliceVectorWithLedger(List.of(SOURCE_1, SOURCE_101))));
        // 第二个实例与其不共享任何进程内条纹锁（模拟另一进程的锁域），仅共享同一批仓储 Mock（同一数据库）
        GraphMergeService otherInstance = new GraphMergeService(entityNodeRepository, relationEdgeRepository,
                entityInfoVectorRepository, relationInfoVectorRepository,
                embeddingClient, descriptionSummarizer, tokenCounter, transactionTemplate,
                vectorContentReconciler, ingestionExecutor);

        // when：两个实例先后合并同一实体的不同来源
        graphMergeService.mergeNodesAndEdges(defaultCtx(),
                List.of(extractedEntity(ENTITY_ALICE, SOURCE_101)), List.of());
        otherInstance.mergeNodesAndEdges(defaultCtx(),
                List.of(extractedEntity(ENTITY_ALICE, SOURCE_102)), List.of());

        // then：两批贡献均被并入（进程内锁不共享亦未丢失任何批次）
        ArgumentCaptor<EntityNode> nodeCaptor = ArgumentCaptor.captor();
        verify(entityNodeRepository, times(2)).upsert(nodeCaptor.capture(), eq(OPERATOR));
        assertEquals(List.of(SOURCE_1, SOURCE_101), nodeCaptor.getAllValues().get(0).properties().sourceIds(),
                "首个实例贡献应与既有账本合并且不互相覆盖");
        assertEquals(List.of(SOURCE_1, SOURCE_101, SOURCE_102),
                nodeCaptor.getAllValues().get(1).properties().sourceIds(),
                "第二个实例应以写回事务内重读的当前账本并入自身贡献（进程内锁不共享亦不丢失）");
        ArgumentCaptor<EntityInfoVector> vectorCaptor = ArgumentCaptor.captor();
        verify(entityInfoVectorRepository, times(2)).upsert(vectorCaptor.capture(), eq(OPERATOR));
        assertEquals(List.of(SOURCE_1, SOURCE_101, SOURCE_102), vectorCaptor.getAllValues().get(1).chunkIds(),
                "权威账本应为两实例贡献的并集");
    }

    /**
     * source_ids 截断（KEEP 默认策略）：合并结果超上限时保留前 N 条，旧来源优先在前。
     */
    @Test
    public void should_truncateSourceIdsOldFirst_when_mergeOneEntity_given_mergedSourcesExceedLimit() {
        // given：上限 2，旧行 [101,102] 加本批 [301] 超限额
        when(entityNodeRepository.lockByKbIdAndNames(KB_ID, List.of(ENTITY_ALICE)))
                .thenReturn(List.of(persistedEntity(ENTITY_ALICE, "old-desc",
                        List.of(SOURCE_101, SOURCE_102))));

        // when：执行合并
        graphMergeService.mergeNodesAndEdges(ctxWithSourceLimit(2),
                List.of(extractedEntity(ENTITY_ALICE, SOURCE_301)), List.of());

        // then：写回节点 source_ids 截断为 [101,102]
        ArgumentCaptor<EntityNode> captor = ArgumentCaptor.captor();
        verify(entityNodeRepository).upsert(captor.capture(), eq(OPERATOR));
        assertEquals(List.of(SOURCE_101, SOURCE_102), captor.getValue().properties().sourceIds(),
                "source_ids 应按上限截断且旧优先");
    }

    /**
     * source_ids 截断（FIFO 策略）：超上限时保留后 N 条，淘汰最旧来源。
     */
    @Test
    public void should_keepLastNSourceIds_when_mergeOneEntity_given_fifoTruncationExceedsLimit() {
        // given：上限 2 且策略 FIFO，旧行 [101,102] 加本批 [301] 超限额
        when(entityNodeRepository.lockByKbIdAndNames(KB_ID, List.of(ENTITY_ALICE)))
                .thenReturn(List.of(persistedEntity(ENTITY_ALICE, "old-desc",
                        List.of(SOURCE_101, SOURCE_102))));

        // when：以 FIFO 策略执行合并
        graphMergeService.mergeNodesAndEdges(ctxWithTruncation(2, GraphMergeParams.SOURCE_IDS_TRUNCATION_FIFO),
                List.of(extractedEntity(ENTITY_ALICE, SOURCE_301)), List.of());

        // then：保留后 2 条 [102,301]（新来源优先）
        ArgumentCaptor<EntityNode> captor = ArgumentCaptor.captor();
        verify(entityNodeRepository).upsert(captor.capture(), eq(OPERATOR));
        assertEquals(List.of(SOURCE_102, SOURCE_301), captor.getValue().properties().sourceIds(),
                "FIFO 策略应淘汰最旧来源并保留后 N 条");
    }

    /**
     * 跨文档共享实体（graph-source-file-paths R1）：既有图行含文档 A 路径、本批贡献文档 B 路径。
     * 预期：写回 filePaths 同时包含 A 与 B 的路径（去重保序，自身在前）。
     */
    @Test
    public void should_unionFilePathsFromBothDocuments_when_mergeOneEntity_given_sharedEntityAcrossDocuments() {
        // given：旧行 filePaths=[docs/A.docx]/来源 [101]，本批同名实体来源 102、路径 docs/B.pdf
        when(entityNodeRepository.lockByKbIdAndNames(KB_ID, List.of(ENTITY_ALICE)))
                .thenReturn(List.of(persistedEntityWithFilePaths(ENTITY_ALICE, "old-desc",
                        List.of(SOURCE_101), List.of("docs/A.docx"))));

        // when：执行合并
        graphMergeService.mergeNodesAndEdges(defaultCtx(),
                List.of(extractedEntityWithFilePath(ENTITY_ALICE, SOURCE_102, "docs/B.pdf")), List.of());

        // then：写回 filePaths 含两篇来源路径且旧在前、新追加在后
        ArgumentCaptor<EntityNode> captor = ArgumentCaptor.captor();
        verify(entityNodeRepository).upsert(captor.capture(), eq(OPERATOR));
        assertEquals(List.of("docs/A.docx", "docs/B.pdf"), captor.getValue().properties().filePaths(),
                "跨文档共享实体的来源路径列表应含全部来源");
    }

    /**
     * 同一文件重复贡献（graph-source-file-paths R1）：既有图行与本批来源路径相同。
     * 预期：写回 filePaths 该路径只记一次。
     */
    @Test
    public void should_recordFilePathOnce_when_mergeOneEntity_given_sameDocumentRepeatedContribution() {
        // given：旧行与批次均为 docs/A.docx（同文档多个分块），来源分块不同
        when(entityNodeRepository.lockByKbIdAndNames(KB_ID, List.of(ENTITY_ALICE)))
                .thenReturn(List.of(persistedEntityWithFilePaths(ENTITY_ALICE, "old-desc",
                        List.of(SOURCE_101), List.of("docs/A.docx"))));

        // when：执行合并
        graphMergeService.mergeNodesAndEdges(defaultCtx(),
                List.of(extractedEntityWithFilePath(ENTITY_ALICE, SOURCE_102, "docs/A.docx")), List.of());

        // then：该文件路径只出现一次
        ArgumentCaptor<EntityNode> captor = ArgumentCaptor.captor();
        verify(entityNodeRepository).upsert(captor.capture(), eq(OPERATOR));
        assertEquals(List.of("docs/A.docx"), captor.getValue().properties().filePaths(),
                "同一文件重复贡献 MUST 只记一次");
    }

    /**
     * 来源路径超限（graph-source-file-paths R2，实体写回）：去重后 3 条超过上限 2。
     * 预期：裁剪至前 2 条并追加溢出占位元素（含 KEEP 策略与「保留数/总数」）；未超限场景由
     * {@code GraphSourceFilePathsTest} 与上两条用例（不追加占位）共同覆盖。
     */
    @Test
    public void should_truncateAndAppendPlaceholder_when_mergeOneEntity_given_mergedFilePathsExceedLimit() {
        // given：上限 2，旧行 filePaths=[p1,p2]，本批贡献 p3
        when(entityNodeRepository.lockByKbIdAndNames(KB_ID, List.of(ENTITY_ALICE)))
                .thenReturn(List.of(persistedEntityWithFilePaths(ENTITY_ALICE, "old-desc",
                        List.of(SOURCE_101), List.of("docs/p1.docx", "docs/p2.docx"))));

        // when：以路径上限 2 执行合并
        graphMergeService.mergeNodesAndEdges(ctxWithFilePathsLimit(2),
                List.of(extractedEntityWithFilePath(ENTITY_ALICE, SOURCE_102, "docs/p3.docx")), List.of());

        // then：保留前 2 条 + 末位溢出占位元素
        ArgumentCaptor<EntityNode> captor = ArgumentCaptor.captor();
        verify(entityNodeRepository).upsert(captor.capture(), eq(OPERATOR));
        assertEquals(List.of("docs/p1.docx", "docs/p2.docx", "…等(KEEP;保留2/共3)"),
                captor.getValue().properties().filePaths(),
                "超限应裁剪至上限并追加含策略与数量的占位元素");
    }

    /**
     * 来源路径超限（graph-source-file-paths R2，关系写回）：与实体侧同一归一口径（共用
     * {@code GraphSourceFilePaths.normalize}），关系合并同样「保留前 N + 占位元素」。
     */
    @Test
    public void should_truncateAndAppendPlaceholder_when_mergeOneEdge_given_mergedFilePathsExceedLimit() {
        // given：上限 2，旧边 filePaths=[r1,r2]，本批边贡献 r3
        when(relationEdgeRepository.lockByKbIdAndUnorderedPair(KB_ID, ENTITY_ALICE, ENTITY_BOB))
                .thenReturn(List.of(persistedEdgeWithFilePaths(ENTITY_ALICE, ENTITY_BOB, DEFAULT_EDGE_WEIGHT,
                        "old-rel", List.of("knows"), List.of(SOURCE_101),
                        List.of("docs/r1.docx", "docs/r2.docx"))));

        // when：以路径上限 2 执行合并
        graphMergeService.mergeNodesAndEdges(ctxWithFilePathsLimit(2), List.of(),
                List.of(extractedEdgeWithFilePath(ENTITY_ALICE, ENTITY_BOB, DEFAULT_EDGE_WEIGHT,
                        EDGE_DESC_NORMAL, List.of("knows"), SOURCE_102, "docs/r3.docx")));

        // then：关系写回 filePaths 与实体侧同一截断口径
        ArgumentCaptor<List<RelationEdge>> captor = ArgumentCaptor.captor();
        verify(relationEdgeRepository).upsertAll(captor.capture(), eq(OPERATOR));
        assertEquals(List.of("docs/r1.docx", "docs/r2.docx", "…等(KEEP;保留2/共3)"),
                captor.getValue().get(0).properties().filePaths(),
                "关系侧超限截断与占位口径应与实体侧一致");
    }

    /**
     * 截断分支回归（保留上限耗尽 + 过滤后无有效新片段）：上限已满额（图行账本截断为 [101,102]，
     * 第 3 条来源 103 仅存活于向量权威账本），本批两条来源均已在权威账本中 → 过滤后无有效新片段，
     * 断言原样返回旧节点且零写操作（不 upsert、不产生远程调用）。
     *
     * <p>口径说明：现行实现以「向量表 chunk_ids 权威账本覆盖判定」表达该语义——上限满额本身
     * 不抑制写入，仅当本批片段已全部入账（无可归属的有效新片段）时才短路不写。</p>
     */
    @Test
    public void should_returnOldNodeWithoutWrite_when_mergeOneEntity_given_sourceIdsLimitExhaustedAndFilteredEmpty() {
        // given：上限 2 且图行账本已满额，权威账本含被截断丢弃的来源 103，本批来源全被覆盖
        when(entityNodeRepository.findByKbIdAndName(KB_ID, ENTITY_ALICE))
                .thenReturn(Optional.of(persistedEntity(ENTITY_ALICE, "old-desc",
                        List.of(SOURCE_101, SOURCE_102))));
        when(entityInfoVectorRepository.findByKbIdAndName(KB_ID, ENTITY_ALICE))
                .thenReturn(Optional.of(EntityInfoVector.create(KB_ID, ENTITY_ALICE,
                        EntityInfoVector.buildContent(ENTITY_ALICE, "old-desc"),
                        List.of(SOURCE_101, SOURCE_102, SOURCE_103), OLD_VECTOR)));
        // 本批实体为多来源（101/102）：多来源 of(...) 重载已随占位机制退役，改以全参构造表达
        EntityNode incoming = new EntityNode(KB_ID, ENTITY_ALICE, new EntityProperties(ENTITY_TYPE_PERSON,
                "desc-Alice", List.of(SOURCE_101, SOURCE_102), FILE_PATH,
                Map.of(ENTITY_TYPE_PERSON, 2), List.of("desc-Alice")));

        // when：执行合并
        GraphMergeReport report = graphMergeService.mergeNodesAndEdges(ctxWithSourceLimit(2),
                List.of(incoming), List.of());

        // then：原样返回旧节点（零 upsert）、零远程调用
        assertEquals(new GraphMergeReport(0, 1, 0, 0, 0), report, "上限满额且无有效新片段应短路跳过实体写入");
        verify(entityNodeRepository, never()).upsert(any(EntityNode.class), any());
        verify(entityInfoVectorRepository, never()).upsert(any(EntityInfoVector.class), any());
        verify(embeddingClient, never()).embed(anyString(), anyString());
        verify(descriptionSummarizer, never()).summarize(any(), any(), any(), any());
    }

    /**
     * 截断不造成假覆盖：图行 source_ids 已满额但向量账本未含新来源时仍需写回。
     */
    @Test
    public void should_writeEntity_when_mergeOneEntity_given_sourceIdsTruncatedButLedgerNotCovers() {
        // given：上限 2，旧行 source_ids 已满额 [101,102]，账本同为 [101,102] 但不含本批来源 301
        when(entityNodeRepository.lockByKbIdAndNames(KB_ID, List.of(ENTITY_ALICE)))
                .thenReturn(List.of(persistedEntity(ENTITY_ALICE, "old-desc",
                        List.of(SOURCE_101, SOURCE_102))));
        stubEntityLedger(ENTITY_ALICE, List.of(SOURCE_101, SOURCE_102));

        // when：执行合并
        GraphMergeReport report = graphMergeService.mergeNodesAndEdges(ctxWithSourceLimit(2),
                List.of(extractedEntity(ENTITY_ALICE, SOURCE_301)), List.of());

        // then：账本未覆盖新来源 → 不短路，写回时仍按上限截断
        assertEquals(new GraphMergeReport(1, 0, 0, 0, 0), report, "向量账本未覆盖新来源时不应短路跳过");
        ArgumentCaptor<EntityNode> captor = ArgumentCaptor.captor();
        verify(entityNodeRepository).upsert(captor.capture(), eq(OPERATOR));
        assertEquals(List.of(SOURCE_101, SOURCE_102), captor.getValue().properties().sourceIds(),
                "写回仍按上限截断，截断与覆盖判定互不干扰");
    }

    /**
     * 默认上限放宽至 200：13 条来源不再被旧默认值 10 截断。
     */
    @Test
    public void should_keepAllSourceIds_when_mergeOneEntity_given_sourcesBelowDefaultLimit() {
        // given：默认上下文（上限 200），旧行 12 条来源（超旧默认 10）+ 本批 1 条
        when(entityNodeRepository.lockByKbIdAndNames(KB_ID, List.of(ENTITY_ALICE)))
                .thenReturn(List.of(persistedEntity(ENTITY_ALICE, "old-desc",
                        sequentialSources(BELOW_DEFAULT_LIMIT_FIRST_SOURCE, BELOW_DEFAULT_LIMIT_SOURCE_COUNT))));

        // when：执行合并
        graphMergeService.mergeNodesAndEdges(defaultCtx(),
                List.of(extractedEntity(ENTITY_ALICE, SOURCE_101)), List.of());

        // then：13 条来源全量保留
        ArgumentCaptor<EntityNode> captor = ArgumentCaptor.captor();
        verify(entityNodeRepository).upsert(captor.capture(), eq(OPERATOR));
        assertEquals(BELOW_DEFAULT_LIMIT_SOURCE_COUNT + 1,
                captor.getValue().properties().sourceIds().size(), "默认上限 200 下 13 条来源不应截断");
    }

    /**
     * Map-Reduce 摘要接入：每个实体恰好调用一次摘要器，参数为上下文/名称/类型/去重描述列表。
     */
    @Test
    public void should_summarizePerEntityExactlyOnce_when_mergeNodesAndEdges_given_twoNewEntities() {
        // given：批内两个全新实体，无旧行

        // when：执行合并
        GraphMergeReport report = graphMergeService.mergeNodesAndEdges(defaultCtx(),
                List.of(extractedEntity(ENTITY_ALICE, SOURCE_101), extractedEntity(ENTITY_BOB, SOURCE_102)),
                List.of());

        // then：摘要器按实体各调用一次，itemType 为实体主类型
        verify(descriptionSummarizer).summarize(defaultCtx(), ENTITY_ALICE, ENTITY_TYPE_PERSON,
                List.of("desc-Alice"));
        verify(descriptionSummarizer).summarize(defaultCtx(), ENTITY_BOB, ENTITY_TYPE_PERSON,
                List.of("desc-Bob"));
        assertEquals(new GraphMergeReport(2, 0, 0, 0, 0), report, "两个新实体均应写回");
    }

    /**
     * 摘要结果生效：关系描述以摘要器返回值为最终落库描述。
     */
    @Test
    public void should_useSummaryAsFinalDescription_when_mergeOneEdge_given_summarizerReturnsText() {
        // given：摘要器对关系返回收敛描述（关系合并只读写边行/边向量，不再读取端点实体行）
        when(descriptionSummarizer.summarize(any(GraphMergeContext.class), anyString(), anyString(), anyList()))
                .thenReturn(SUMMARIZED_DESCRIPTION);

        // when：执行合并
        graphMergeService.mergeNodesAndEdges(defaultCtx(), List.of(),
                List.of(extractedEdge(ENTITY_ALICE, ENTITY_BOB, DEFAULT_EDGE_WEIGHT, EDGE_DESC_NORMAL,
                        List.of("colleague", "knows"), SOURCE_101)));

        // then：摘要入参为字典序端点对、入序关键词 join 与本批描述
        ArgumentCaptor<String> nameCaptor = ArgumentCaptor.captor();
        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.captor();
        ArgumentCaptor<List<String>> descCaptor = ArgumentCaptor.captor();
        verify(descriptionSummarizer).summarize(eq(defaultCtx()), nameCaptor.capture(),
                typeCaptor.capture(), descCaptor.capture());
        assertEquals(ENTITY_ALICE + RELATION_NAME_CONNECTOR + ENTITY_BOB, nameCaptor.getValue(),
                "关系摘要名应为字典序端点对加连接符");
        assertEquals("colleague,knows", typeCaptor.getValue(), "itemType 应为入序关键词 join");
        assertEquals(List.of(EDGE_DESC_NORMAL), descCaptor.getValue(), "描述候选应为本批描述");
    }

    /**
     * 实体向量复用：新内容与旧行 content 一致时复用旧向量，零 embedding 远程调用。
     */
    @Test
    public void should_reuseOldVectorWithoutEmbed_when_mergeOneEntity_given_unchangedContent() {
        // given：事务外普通读到的旧向量 content 与合并后的目标内容一致（账本 [101] 不含本批来源 102）
        when(entityNodeRepository.findByKbIdAndName(KB_ID, ENTITY_ALICE))
                .thenReturn(Optional.of(persistedEntity(ENTITY_ALICE, "same-desc", List.of(SOURCE_101))));
        when(entityInfoVectorRepository.findByKbIdAndName(KB_ID, ENTITY_ALICE))
                .thenReturn(Optional.of(EntityInfoVector.create(KB_ID, ENTITY_ALICE,
                        EntityInfoVector.buildContent(ENTITY_ALICE, "same-desc"), List.of(SOURCE_101),
                        OLD_VECTOR)));
        when(entityNodeRepository.lockByKbIdAndNames(KB_ID, List.of(ENTITY_ALICE)))
                .thenReturn(List.of(persistedEntity(ENTITY_ALICE, "same-desc", List.of(SOURCE_101))));
        when(entityInfoVectorRepository.lockByKbIdAndName(KB_ID, ENTITY_ALICE))
                .thenReturn(Optional.of(EntityInfoVector.create(KB_ID, ENTITY_ALICE,
                        EntityInfoVector.buildContent(ENTITY_ALICE, "same-desc"), List.of(SOURCE_101),
                        OLD_VECTOR)));

        // when：执行合并
        GraphMergeReport report = graphMergeService.mergeNodesAndEdges(ctxWithSourceLimit(2),
                List.of(extractedEntity(ENTITY_ALICE, SOURCE_102)), List.of());

        // then：实体写回计数 1，向量复用旧数组且未发起 embedding
        assertEquals(1, report.entitiesWritten(), "内容一致时实体仍应写回（来源更新）");
        ArgumentCaptor<EntityInfoVector> captor = ArgumentCaptor.captor();
        verify(entityInfoVectorRepository).upsert(captor.capture(), eq(OPERATOR));
        assertSame(OLD_VECTOR, captor.getValue().vector(), "内容未变应复用旧向量数组");
        verify(embeddingClient, never()).embed(anyString(), anyString());
    }

    /**
     * 关系幂等短路：保留行存在 + 全部记录可归因 + 向量账本覆盖本批来源 + 关键词覆盖骨架 + 描述非空。
     *
     * <p>短路条件已移除「无反向残留行」与「两端点齐备」两个合取项：方向在抽取出口归一后残留行
     * 无从产生；端点缺失属正常态（不补建占位节点），不影响关系条目的幂等判定。</p>
     */
    @Test
    public void should_skipRelation_when_mergeOneEdge_given_allIdempotencyConditionsMet() {
        // given：上限 2，事务外普通读即可见旧行与旧向量账本，旧行覆盖本批关键词、账本覆盖本批来源
        when(relationEdgeRepository.findByKbIdAndUnorderedPair(KB_ID, ENTITY_ALICE, ENTITY_BOB))
                .thenReturn(List.of(persistedEdge(ENTITY_ALICE, ENTITY_BOB, DEFAULT_EDGE_WEIGHT, "old-rel",
                        List.of("know", "like"), List.of(SOURCE_101, SOURCE_102))));
        when(relationInfoVectorRepository.findByKbIdAndUnorderedPair(KB_ID, ENTITY_ALICE, ENTITY_BOB))
                .thenReturn(Optional.of(RelationInfoVector.create(KB_ID, ENTITY_ALICE, ENTITY_BOB,
                        "ledger-content", List.of(SOURCE_101, SOURCE_102), OLD_VECTOR)));

        // when：执行合并
        GraphMergeReport report = graphMergeService.mergeNodesAndEdges(ctxWithSourceLimit(2), List.of(),
                List.of(extractedEdge(ENTITY_ALICE, ENTITY_BOB, DEFAULT_EDGE_WEIGHT, "new-rel",
                        List.of("know"), SOURCE_101)));

        // then：短路跳过，零边写入与零远程调用
        assertEquals(new GraphMergeReport(0, 0, 0, 1, 0), report, "满足全部覆盖条件应跳过关系写入");
        verify(relationEdgeRepository, never()).upsertAll(anyList(), any());
        verify(relationInfoVectorRepository, never()).upsertAll(anyList(), any());
        verify(descriptionSummarizer, never()).summarize(any(), any(), any(), any());
        verify(embeddingClient, never()).embed(anyString(), anyString());
    }

    /**
     * 端点缺失时关系正常落库（新增用例 7）：抽取边两端点均无实体行，
     * 关系图行与关系向量行仍各写回一次，实体侧零写入（不补建占位节点）。
     */
    @Test
    public void should_persistRelation_when_mergeNodesAndEdges_given_missingEndpoints() {
        // given：边两端点 Ghost、Ghost2 在实体表均不存在（实体仓储未打桩，Mockito 默认返回空）

        // when：执行合并
        GraphMergeReport report = graphMergeService.mergeNodesAndEdges(defaultCtx(), List.of(),
                List.of(extractedEdge(ENTITY_GHOST, ENTITY_GHOST2, DEFAULT_EDGE_WEIGHT, EDGE_DESC_NORMAL,
                        List.of("knows"), SOURCE_301)));

        // then：关系写入计数 1、实体写入计数 0，实体图行与实体向量仓储全程零交互
        assertEquals(new GraphMergeReport(0, 0, 1, 0, 0), report, "端点缺失时关系仍应正常写入");
        verifyNoInteractions(entityNodeRepository, entityInfoVectorRepository);
        // then：关系图行落库一次，端点身份为字典序归一对、权重为本批记录全额
        ArgumentCaptor<List<RelationEdge>> edgeCaptor = ArgumentCaptor.captor();
        verify(relationEdgeRepository).upsertAll(edgeCaptor.capture(), eq(OPERATOR));
        RelationEdge writtenEdge = edgeCaptor.getValue().get(0);
        assertEquals(List.of(ENTITY_GHOST, ENTITY_GHOST2), writtenEdge.normalizedPair(),
                "悬挂端点的关系仍按归一端点对落库");
        assertEquals(DEFAULT_EDGE_WEIGHT, writtenEdge.properties().weight(), "首写关系权重应为本批记录全额");
        assertEquals(List.of(SOURCE_301), writtenEdge.properties().sourceIds(), "展示来源列应记入本批来源");
        // then：关系向量行落库一次，其 chunk_ids 即权威来源账本
        ArgumentCaptor<List<RelationInfoVector>> vectorCaptor = ArgumentCaptor.captor();
        verify(relationInfoVectorRepository).upsertAll(vectorCaptor.capture(), eq(OPERATOR));
        assertEquals(List.of(SOURCE_301), vectorCaptor.getValue().get(0).chunkIds(),
                "关系向量行的来源账本应记入本批来源");
    }

    /**
     * 同一缺失端点被批内多条边引用：不补建占位节点，两条边的来源在同一关系条目上全额累加。
     */
    @Test
    public void should_accumulateBothBatchSources_when_mergeOneEdge_given_missingEndpointReferencedTwice() {
        // given：Ghost 端点不存在，批内两条 Alice→Ghost 边分别来自 301、303

        // when：执行合并
        GraphMergeReport report = graphMergeService.mergeNodesAndEdges(defaultCtx(), List.of(),
                List.of(extractedEdge(ENTITY_ALICE, ENTITY_GHOST, DEFAULT_EDGE_WEIGHT, EDGE_DESC_NORMAL,
                                List.of("knows"), SOURCE_301),
                        extractedEdge(ENTITY_ALICE, ENTITY_GHOST, DEFAULT_EDGE_WEIGHT, EDGE_DESC_NORMAL,
                                List.of("knows"), SOURCE_303)));

        // then：单条写回、权重 2.0、展示来源列为全量 [301,303]
        ArgumentCaptor<List<RelationEdge>> edgeCaptor = ArgumentCaptor.captor();
        verify(relationEdgeRepository).upsertAll(edgeCaptor.capture(), eq(OPERATOR));
        assertEquals(1, edgeCaptor.getValue().size(), "同一无向端点对应聚合为单条目");
        assertEquals(2 * DEFAULT_EDGE_WEIGHT, edgeCaptor.getValue().get(0).properties().weight(),
                "批内两条边权重应累加");
        assertEquals(List.of(SOURCE_301, SOURCE_303), edgeCaptor.getValue().get(0).properties().sourceIds(),
                "展示来源列应为本批全量非空来源");
        // then：实体侧零交互——缺失端点不再补建占位节点
        assertEquals(new GraphMergeReport(0, 0, 1, 0, 0), report, "缺失端点不再补建占位实体");
        verifyNoInteractions(entityNodeRepository, entityInfoVectorRepository);
    }

    /**
     * 缺失端点不建节点、不建向量行、不发向量化（新增用例 6）：
     * 关系合并只持边锁、只读写边行与边向量，端点存在与否均不触达实体侧仓储与端点内容的 embedding。
     */
    @Test
    public void should_notCreateNodeVectorOrEmbed_when_mergeNodesAndEdges_given_missingEndpoint() {
        // given：批内两条边分别引用实体表不存在的 Ghost、Ghost2 两个缺失端点
        List<RelationEdge> batch = List.of(
                extractedEdge(ENTITY_ALICE, ENTITY_GHOST, DEFAULT_EDGE_WEIGHT, EDGE_DESC_NORMAL,
                        List.of("knows"), SOURCE_301),
                extractedEdge(ENTITY_ALICE, ENTITY_GHOST2, DEFAULT_EDGE_WEIGHT, EDGE_DESC_NORMAL,
                        List.of("knows"), SOURCE_302));

        // when：执行合并
        GraphMergeReport report = graphMergeService.mergeNodesAndEdges(defaultCtx(), List.of(), batch);

        // then：两条关系各落库一次，实体写入计数为零
        assertEquals(new GraphMergeReport(0, 0, 2, 0, 0), report, "两条边应各写回一次且不建端点节点");
        // then：实体图行与实体向量仓储全程零交互（既不 upsert 任何名字，也不读端点行）
        verifyNoInteractions(entityNodeRepository, entityInfoVectorRepository);
        // then：embedding 只为两条边的 content 各发一次，不存在端点内容的向量化调用
        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.captor();
        verify(embeddingClient, times(2)).embed(eq(EMBED_PROFILE), contentCaptor.capture());
        assertEquals(List.of(
                        RelationInfoVector.buildContent(List.of("knows"), ENTITY_ALICE, ENTITY_GHOST,
                                EDGE_DESC_NORMAL),
                        RelationInfoVector.buildContent(List.of("knows"), ENTITY_ALICE, ENTITY_GHOST2,
                                EDGE_DESC_NORMAL)), contentCaptor.getAllValues(),
                "MUST NOT 为缺失端点内容发起向量化调用");
    }

    /**
     * 关系侧账本 fail-open：图行来源已满额覆盖但向量账本缺失时仍判为未覆盖并写回。
     */
    @Test
    public void should_writeRelation_when_mergeOneEdge_given_vectorLedgerMissingAlthoughGraphRowCovers() {
        // given：图行展示来源与关键词均覆盖本批，但未打桩关系向量账本（仓储桩返回 Optional.empty）
        when(relationEdgeRepository.lockByKbIdAndUnorderedPair(KB_ID, ENTITY_ALICE, ENTITY_BOB))
                .thenReturn(List.of(persistedEdge(ENTITY_ALICE, ENTITY_BOB, DEFAULT_EDGE_WEIGHT, "old-rel",
                        List.of("know", "like"), List.of(SOURCE_101, SOURCE_102))));

        // when：执行合并
        GraphMergeReport report = graphMergeService.mergeNodesAndEdges(ctxWithSourceLimit(2), List.of(),
                List.of(extractedEdge(ENTITY_ALICE, ENTITY_BOB, DEFAULT_EDGE_WEIGHT, "new-rel",
                        List.of("know"), SOURCE_101)));

        // then：账本缺失 fail-open，宁多算不漏算仍写回
        assertEquals(new GraphMergeReport(0, 0, 1, 0, 0), report, "向量账本缺失时不应短路跳过关系写入");
        verify(relationEdgeRepository).upsertAll(anyList(), eq(OPERATOR));
    }

    /**
     * null 来源记录全额累加（组2 公式）：无来源归属的贡献记录不参与账本过滤，且使幂等短路失效。
     */
    @Test
    public void should_addFullWeightOfNullSource_when_mergeOneEdge_given_nullSourceRecordWithCoveredLedger() {
        // given：向量账本 [101,102] 已覆盖图行展示来源，本批为一条无来源归属的边（权重 5.0）
        when(relationEdgeRepository.lockByKbIdAndUnorderedPair(KB_ID, ENTITY_ALICE, ENTITY_BOB))
                .thenReturn(List.of(persistedEdge(ENTITY_ALICE, ENTITY_BOB, DEFAULT_EDGE_WEIGHT, "old-rel",
                        List.of("know", "like"), List.of(SOURCE_101, SOURCE_102))));
        stubRelationLedger(ENTITY_ALICE, ENTITY_BOB, List.of(SOURCE_101, SOURCE_102));

        // when：执行合并
        GraphMergeReport report = graphMergeService.mergeNodesAndEdges(defaultCtx(), List.of(),
                List.of(extractedEdge(ENTITY_ALICE, ENTITY_BOB, 5.0, "new-rel", List.of("know"), null)));

        // then：null 来源全额累加为 6.0，且账本无法覆盖无归属记录 → 写回而非短路
        ArgumentCaptor<List<RelationEdge>> captor = ArgumentCaptor.captor();
        verify(relationEdgeRepository).upsertAll(captor.capture(), eq(OPERATOR));
        assertEquals(6.0, captor.getValue().get(0).properties().weight(), "null 来源记录应全额累加权重");
        assertEquals(List.of(SOURCE_101, SOURCE_102), captor.getValue().get(0).properties().sourceIds(),
                "null 来源不入账本，图行账本应原样保留");
        assertEquals(new GraphMergeReport(0, 0, 1, 0, 0), report, "含 null 来源记录时不得短路跳过");
    }

    /**
     * 实体名超长截断：超出入库上限（512，对齐 V11 表定义）的实体名截断后入库。
     */
    @Test
    public void should_truncateEntityName_when_mergeNodesAndEdges_given_entityNameOverLimit() {
        // given：实体名比入库上限多 1 字符

        // when：执行合并
        graphMergeService.mergeNodesAndEdges(defaultCtx(),
                List.of(extractedEntity("x".repeat(NAME_LENGTH_LIMIT + 1), SOURCE_101)), List.of());

        // then：写回名为前 512 字符
        ArgumentCaptor<EntityNode> captor = ArgumentCaptor.captor();
        verify(entityNodeRepository).upsert(captor.capture(), eq(OPERATOR));
        assertEquals("x".repeat(NAME_LENGTH_LIMIT), captor.getValue().entityName(),
                "超长实体名应截断至入库上限后写回");
    }

    /**
     * 防御线顺序「归一 → 判空 → 截断」（align-extraction-identity-and-hygiene 1.6/1.7）：
     * 外层引号包裹的超限名（514 字符）先归一剥离引号回到恰好 512 字符、不再触发截断——
     * 若先截断，写回名将残留首位引号且长度形态不同。
     */
    @Test
    public void should_normalizeBeforeTruncate_when_mergeNodesAndEdges_given_quotedNameOverLimit() {
        // given：双引号包裹 512 个 x，总长 514，归一后恰为上限内纯名
        String quotedName = "\"" + "x".repeat(NAME_LENGTH_LIMIT) + "\"";

        // when：执行合并
        graphMergeService.mergeNodesAndEdges(defaultCtx(),
                List.of(extractedEntity(quotedName, SOURCE_101)), List.of());

        // then：写回名为归一后的 512 纯 x（引号已剥离、截断未触发）
        ArgumentCaptor<EntityNode> captor = ArgumentCaptor.captor();
        verify(entityNodeRepository).upsert(captor.capture(), eq(OPERATOR));
        assertEquals("x".repeat(NAME_LENGTH_LIMIT), captor.getValue().entityName(),
                "防御线必须先归一再截断：残留引号说明顺序被颠倒");
    }

    /**
     * 空实体名跳过：空名条目丢弃并 WARN，同批正常实体不受影响。
     *
     * <p>{@link EntityNode} 构造器拒空名，故以 mock 模拟上游旁路传入的空名条目。</p>
     */
    @Test
    public void should_skipEntity_when_mergeNodesAndEdges_given_blankEntityName() {
        // given：一个空名实体条目 + 一个正常实体
        EntityNode blankNode = mock(EntityNode.class);
        when(blankNode.entityName()).thenReturn("   ");

        // when：执行合并
        GraphMergeReport report = graphMergeService.mergeNodesAndEdges(defaultCtx(),
                List.of(blankNode, extractedEntity(ENTITY_ALICE, SOURCE_101)), List.of());

        // then：空名条目不落库，仅正常实体写回一次
        assertEquals(new GraphMergeReport(1, 0, 0, 0, 0), report, "空名实体应跳过，正常实体计一次写入");
        verify(entityNodeRepository, times(1)).upsert(any(EntityNode.class), eq(OPERATOR));
    }

    /**
     * 空端点名跳过：任一端点为空的边条目丢弃，不触碰任何图仓储。
     *
     * <p>{@link RelationEdge} 构造器拒空端点，故以 mock 模拟旁路传入的空端点边。</p>
     */
    @Test
    public void should_skipEdge_when_mergeNodesAndEdges_given_blankEndpointName() {
        // given：源端点为空白的一条边（判空短路，不触碰目标端点访问器）
        RelationEdge blankEdge = mock(RelationEdge.class);
        when(blankEdge.sourceName()).thenReturn("  ");

        // when：执行合并
        GraphMergeReport report = graphMergeService.mergeNodesAndEdges(defaultCtx(), List.of(),
                List.of(blankEdge));

        // then：报告全零且边/实体仓储零触碰
        assertEquals(GraphMergeReport.empty(), report, "空端点边应静默跳过不计数");
        verifyNoInteractions(relationEdgeRepository, relationInfoVectorRepository);
        verify(entityNodeRepository, never()).upsert(any(EntityNode.class), any());
    }

    /**
     * 截断后同名自环：两端点截断后收敛为同一名称时按自环丢弃并计数。
     */
    @Test
    public void should_countSelfLoopDropped_when_mergeNodesAndEdges_given_endpointsEqualAfterTruncation() {
        // given：两端点共享 512 字符前缀、仅尾部不同（截断后必同名）
        String commonPrefix = "a".repeat(NAME_LENGTH_LIMIT);
        RelationEdge edge = extractedEdge(commonPrefix + "1", commonPrefix + "2", DEFAULT_EDGE_WEIGHT,
                EDGE_DESC_NORMAL, List.of("knows"), SOURCE_101);

        // when：执行合并
        GraphMergeReport report = graphMergeService.mergeNodesAndEdges(defaultCtx(), List.of(), List.of(edge));

        // then：计入自环丢弃，无关系写回
        assertEquals(new GraphMergeReport(0, 0, 0, 0, 1), report, "截断后同名的边应按自环丢弃");
        verify(relationEdgeRepository, never()).upsertAll(anyList(), any());
    }

    /**
     * 实体阶段 fail-closed：多条目失败时首异常上抛、其余 addSuppressed，且不进入关系阶段。
     */
    @Test
    public void should_throwFirstFailureWithSuppressed_when_mergeNodesAndEdges_given_multipleEntityFailures() {
        // given：实体锁读全部失败（thenAnswer 每次新建异常实例，避免 addSuppressed 自身异常）
        when(entityNodeRepository.lockByKbIdAndNames(eq(KB_ID), any())).thenAnswer(invocation -> {
            List<String> names = invocation.getArgument(1);
            throw new RuntimeException("lock-fail-" + names.get(0));
        });

        // when：执行合并
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> graphMergeService.mergeNodesAndEdges(defaultCtx(),
                        List.of(extractedEntity(ENTITY_ALICE, SOURCE_101),
                                extractedEntity(ENTITY_BOB, SOURCE_102)),
                        List.of(extractedEdge(ENTITY_ALICE, ENTITY_BOB, DEFAULT_EDGE_WEIGHT,
                                EDGE_DESC_NORMAL, List.of("knows"), SOURCE_101))));

        // then：首异常为 Alice 失败，Bob 失败挂为 suppressed，关系仓储零触碰
        assertEquals("实体合并失败：lock-fail-Alice", thrown.getMessage(), "异常消息应携带阶段名与首异常");
        RuntimeException first = (RuntimeException) thrown.getCause();
        assertEquals("lock-fail-Alice", first.getMessage(), "cause 应为首个失败异常");
        assertEquals(1, first.getSuppressed().length, "第二个失败应挂为 suppressed");
        assertEquals("lock-fail-Bob", first.getSuppressed()[0].getMessage(), "suppressed 应为 Bob 的失败");
        verifyNoInteractions(relationEdgeRepository);
    }

    /**
     * 关系阶段异常上抛：embedding 远程失败时关系阶段整体失败且不落库。
     */
    @Test
    public void should_throwIllegalStateException_when_mergeOneEdge_given_embeddingFailure() {
        // given：边内容向量化恒失败（关系合并已不读取端点实体行，无需任何端点打桩）
        when(embeddingClient.embed(anyString(), anyString())).thenThrow(new RuntimeException("embed down"));

        // when：执行合并
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> graphMergeService.mergeNodesAndEdges(defaultCtx(), List.of(),
                        List.of(extractedEdge(ENTITY_ALICE, ENTITY_BOB, DEFAULT_EDGE_WEIGHT,
                                EDGE_DESC_NORMAL, List.of("knows"), SOURCE_101))));

        // then：阶段消息含「关系合并失败」，边与关系向量均未写入
        assertEquals("关系合并失败：embed down", thrown.getMessage(), "embedding 失败应上抛并标注阶段");
        verify(relationEdgeRepository, never()).upsertAll(anyList(), any());
        verify(relationInfoVectorRepository, never()).upsertAll(anyList(), any());
    }

    /**
     * 跨进程重放幂等：同一逻辑批重复执行两次，权重与来源不膨胀，第二次复用向量零 embedding。
     */
    @Test
    public void should_keepWeightAndEmbeddingStable_when_mergeNodesAndEdges_calledTwiceWithSameBatch() {
        // given：状态化边/向量仓储桩模拟真实库内 read-modify-write 演化（普通读与加锁读共用同一状态）
        Map<String, RelationEdge> edgeStore = new HashMap<>();
        Map<String, RelationInfoVector> vectorStore = new HashMap<>();
        wireStatefulRelationStores(edgeStore, vectorStore);
        GraphMergeContext ctx = defaultCtx();
        List<RelationEdge> batch = List.of(extractedEdge(ENTITY_ALICE, ENTITY_BOB, DEFAULT_EDGE_WEIGHT,
                EDGE_DESC_NORMAL, List.of("knows"), SOURCE_101));

        // when：同一逻辑批连续合并两次
        GraphMergeReport firstReport = graphMergeService.mergeNodesAndEdges(ctx, List.of(), batch);
        GraphMergeReport replayReport = graphMergeService.mergeNodesAndEdges(ctx, List.of(),
                List.of(extractedEdge(ENTITY_ALICE, ENTITY_BOB, DEFAULT_EDGE_WEIGHT,
                        EDGE_DESC_NORMAL, List.of("knows"), SOURCE_101)));

        // then：首遍写回、重放被向量账本短路，权重/来源不膨胀且全程仅一次 embedding
        assertEquals(new GraphMergeReport(0, 0, 1, 0, 0), firstReport, "首次执行应写入一条关系");
        assertEquals(new GraphMergeReport(0, 0, 0, 1, 0), replayReport,
                "重放执行应被账本覆盖短路（关系跳过计数 1）而非重复写回");
        RelationEdge stored = edgeStore.get(edgeKey(ENTITY_ALICE, ENTITY_BOB));
        assertEquals(DEFAULT_EDGE_WEIGHT, stored.properties().weight(), "重放后权重应保持 1.0 不膨胀");
        assertEquals(List.of(SOURCE_101), stored.properties().sourceIds(), "重放后来源不应重复累积");
        verify(embeddingClient, times(1)).embed(eq(EMBED_PROFILE), anyString());
    }

    /**
     * 重复解析同一文档权重不增长（新增用例 2）：同一文档（两个分块）连续解析两次，
     * 第二次被向量账本短路，落库权重与仅解析一次时相同。
     */
    @Test
    public void should_sameWeightAsSingleRun_when_mergeNodesAndEdges_given_documentParsedRepeatedly() {
        // given：同一文档的两个分块（101/102）贡献同一条关系；状态化仓储模拟真实库内演化
        Map<String, RelationEdge> edgeStore = new HashMap<>();
        Map<String, RelationInfoVector> vectorStore = new HashMap<>();
        wireStatefulRelationStores(edgeStore, vectorStore);
        GraphMergeContext ctx = defaultCtx();

        // when：该文档首次解析与重复解析各执行一次合并（两批产物完全相同）
        GraphMergeReport firstReport = graphMergeService.mergeNodesAndEdges(ctx, List.of(),
                twoChunkDocumentEdges());
        GraphMergeReport replayReport = graphMergeService.mergeNodesAndEdges(ctx, List.of(),
                twoChunkDocumentEdges());

        // then：首遍按两个来源全额累加为 2.0 并落库
        assertEquals(1, firstReport.relationsWritten(), "首次解析应写回一条关系");
        ArgumentCaptor<List<RelationEdge>> captor = ArgumentCaptor.captor();
        verify(relationEdgeRepository).upsertAll(captor.capture(), eq(OPERATOR));
        double firstWrittenWeight = captor.getValue().get(0).properties().weight();
        assertEquals(2 * DEFAULT_EDGE_WEIGHT, firstWrittenWeight, "首次解析应按两个来源全额累加");
        // then：重复解析被向量账本短路（未再次落库），库内权重与单次解析相同
        assertEquals(1, replayReport.relationsSkipped(), "重复解析应被向量账本短路");
        assertEquals(0, replayReport.relationsWritten(), "重复解析 MUST NOT 再次落库");
        verify(relationEdgeRepository, times(1)).upsertAll(anyList(), eq(OPERATOR));
        assertEquals(firstWrittenWeight,
                edgeStore.get(edgeKey(ENTITY_ALICE, ENTITY_BOB)).properties().weight(),
                "重复解析 N 次后的权重 MUST 与仅解析一次时相同");
    }

    /**
     * 同一文档两个分块对同一条关系的抽取产物（来源 101/102，权重各 1.0）。
     *
     * @return 批内抽取边列表
     */
    private List<RelationEdge> twoChunkDocumentEdges() {
        return List.of(
                extractedEdge(ENTITY_ALICE, ENTITY_BOB, DEFAULT_EDGE_WEIGHT, EDGE_DESC_NORMAL,
                        List.of("knows"), SOURCE_101),
                extractedEdge(ENTITY_ALICE, ENTITY_BOB, DEFAULT_EDGE_WEIGHT, EDGE_DESC_NORMAL,
                        List.of("knows"), SOURCE_102));
    }

    /**
     * 记录首次插入的并发竞争（spec Scenario）：写回事务首次落库命中唯一约束冲突时，重试整个写回事务
     * （重读当前值 → 重算 → 写回）并成功并入本批贡献，MUST NOT 因冲突丢弃该批贡献；
     * 重试仅重跑数据库事务体，事务外的摘要与向量化不重复发生。
     */
    @Test
    public void should_retryAndMergeOwnContribution_when_mergeOneEntity_given_duplicateKeyOnFirstInsert() {
        // given：加锁读恒为重试前的当前账本 [101]，首次 upsert 抛唯一约束冲突
        when(entityNodeRepository.lockByKbIdAndNames(KB_ID, List.of(ENTITY_ALICE)))
                .thenReturn(List.of(persistedEntity(ENTITY_ALICE, "alice-desc", List.of(SOURCE_101))));
        stubEntityLedger(ENTITY_ALICE, List.of(SOURCE_101));
        doThrow(new DuplicateKeyException("dup-key")).doNothing()
                .when(entityNodeRepository).upsert(any(EntityNode.class), eq(OPERATOR));

        // when：执行合并（本批来源 102）
        GraphMergeReport report = graphMergeService.mergeNodesAndEdges(defaultCtx(),
                List.of(extractedEntity(ENTITY_ALICE, SOURCE_102)), List.of());

        // then：重试后成功并入本批来源，实体写入计数 1
        assertEquals(1, report.entitiesWritten(), "冲突重试后应成功并入本批贡献而非丢弃");
        ArgumentCaptor<EntityNode> captor = ArgumentCaptor.captor();
        verify(entityNodeRepository, times(2)).upsert(captor.capture(), eq(OPERATOR));
        assertEquals(List.of(SOURCE_101, SOURCE_102), captor.getValue().properties().sourceIds(),
                "重试后写回值应并入本批来源");
        verify(entityInfoVectorRepository, times(1)).upsert(any(EntityInfoVector.class), eq(OPERATOR));
        verify(transactionTemplate, times(2)).executeWithoutResult(any());
        verify(descriptionSummarizer, times(1)).summarize(any(), anyString(), anyString(), anyList());
        verify(embeddingClient, times(1)).embed(anyString(), anyString());
    }

    /**
     * 记录首次插入的并发竞争（关系侧，spec Scenario）：写回事务首次 upsert 命中唯一约束冲突时重试
     * 并成功并入本批贡献，weight 与账本均含本批全额；重试仅重跑数据库事务体，远程调用不重复。
     */
    @Test
    public void should_retryAndMergeOwnContribution_when_mergeOneEdge_given_duplicateKeyOnFirstInsert() {
        // given：加锁读恒为重试前的当前边（weight=1.0、向量账本 [1]），首次 upsertAll 抛冲突
        when(relationEdgeRepository.lockByKbIdAndUnorderedPair(KB_ID, ENTITY_ALICE, ENTITY_BOB))
                .thenReturn(List.of(persistedEdge(ENTITY_ALICE, ENTITY_BOB, DEFAULT_EDGE_WEIGHT,
                        EDGE_DESC_NORMAL, List.of("knows"), List.of(SOURCE_1))));
        stubRelationLedger(ENTITY_ALICE, ENTITY_BOB, List.of(SOURCE_1));
        doThrow(new DuplicateKeyException("dup-key")).doNothing()
                .when(relationEdgeRepository).upsertAll(anyList(), eq(OPERATOR));

        // when：执行合并（本批来源 101）
        GraphMergeReport report = graphMergeService.mergeNodesAndEdges(defaultCtx(), List.of(),
                List.of(extractedEdge(ENTITY_ALICE, ENTITY_BOB, DEFAULT_EDGE_WEIGHT, EDGE_DESC_NORMAL,
                        List.of("knows"), SOURCE_101)));

        // then：重试后 weight 含本批全额、账本并入本批来源，关系写入计数 1
        assertEquals(1, report.relationsWritten(), "冲突重试后应成功并入本批贡献而非丢弃");
        ArgumentCaptor<List<RelationEdge>> captor = ArgumentCaptor.captor();
        verify(relationEdgeRepository, times(2)).upsertAll(captor.capture(), eq(OPERATOR));
        assertEquals(2 * DEFAULT_EDGE_WEIGHT, captor.getValue().get(0).properties().weight(),
                "重试后 weight 应含本批来源的全额贡献");
        assertEquals(List.of(SOURCE_1, SOURCE_101), captor.getValue().get(0).properties().sourceIds(),
                "重试后账本应并入本批来源");
        verify(relationInfoVectorRepository, times(1)).upsertAll(anyList(), eq(OPERATOR));
        verify(transactionTemplate, times(2)).executeWithoutResult(any());
        verify(descriptionSummarizer, times(1)).summarize(any(), anyString(), anyString(), anyList());
        verify(embeddingClient, times(1)).embed(anyString(), anyString());
    }

    /**
     * 重试上界：唯一约束冲突持续发生时，达到重试上界（3 次）后按批次失败处理，不无限重试。
     */
    @Test
    public void should_failBatch_when_mergeOneEntity_given_duplicateKeyPersists() {
        // given：写回落库持续抛唯一约束冲突
        when(entityNodeRepository.lockByKbIdAndNames(KB_ID, List.of(ENTITY_ALICE)))
                .thenReturn(List.of(persistedEntity(ENTITY_ALICE, "alice-desc", List.of(SOURCE_101))));
        doThrow(new DuplicateKeyException("dup-key"))
                .when(entityNodeRepository).upsert(any(EntityNode.class), eq(OPERATOR));

        // when：执行合并
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> graphMergeService.mergeNodesAndEdges(defaultCtx(),
                        List.of(extractedEntity(ENTITY_ALICE, SOURCE_102)), List.of()));

        // then：写回事务恰好重试至上界后上抛，由阶段汇总为失败
        assertEquals("实体合并失败：dup-key", thrown.getMessage(), "超出重试上界应按阶段失败处理");
        assertInstanceOf(DuplicateKeyException.class, thrown.getCause(), "cause 应为唯一约束冲突异常");
        verify(transactionTemplate, times(3)).executeWithoutResult(any());
        verify(entityNodeRepository, times(3)).upsert(any(EntityNode.class), eq(OPERATOR));
    }

    /**
     * 非唯一约束异常不触发重试：写回事务体仅执行一次后原样上抛（沿用既有异常处理）。
     */
    @Test
    public void should_notRetry_when_mergeOneEntity_given_nonUniqueConstraintFailure() {
        // given：写回落库抛出非唯一约束异常（不具备可重试性）
        when(entityNodeRepository.lockByKbIdAndNames(KB_ID, List.of(ENTITY_ALICE)))
                .thenReturn(List.of(persistedEntity(ENTITY_ALICE, "alice-desc", List.of(SOURCE_101))));
        doThrow(new IllegalStateException("boom")).when(entityNodeRepository)
                .upsert(any(EntityNode.class), eq(OPERATOR));

        // when：执行合并
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> graphMergeService.mergeNodesAndEdges(defaultCtx(),
                        List.of(extractedEntity(ENTITY_ALICE, SOURCE_102)), List.of()));

        // then：写回事务体仅执行一次，异常原样上抛并由阶段汇总
        assertEquals("实体合并失败：boom", thrown.getMessage(), "非冲突异常应按阶段失败原样上抛");
        assertEquals("boom", thrown.getCause().getMessage(), "cause 应为原始失败异常");
        verify(transactionTemplate, times(1)).executeWithoutResult(any());
        verify(entityNodeRepository, times(1)).upsert(any(EntityNode.class), eq(OPERATOR));
    }

    /**
     * 重试后重新判定截断分支：首次写回抛唯一冲突，重试时加锁读到的账本已变化，
     * 断言以重试后的当前值重新判定截断分支并重算写回值（而非沿用首轮读到的旧值）。
     */
    @Test
    public void should_reevaluateTruncationAfterRetry_when_mergeOneEntity_given_duplicateKeyThenNewLedger() {
        // given：上限 2；首轮加锁读到旧账本 [101,102]，重试时读到并发他人已提交的 [101,301]
        when(entityNodeRepository.lockByKbIdAndNames(KB_ID, List.of(ENTITY_ALICE)))
                .thenReturn(List.of(persistedEntity(ENTITY_ALICE, "old-desc",
                                List.of(SOURCE_101, SOURCE_102))),
                        List.of(persistedEntity(ENTITY_ALICE, "old-desc",
                                List.of(SOURCE_101, SOURCE_301))));
        when(entityInfoVectorRepository.lockByKbIdAndName(KB_ID, ENTITY_ALICE))
                .thenReturn(Optional.of(aliceVectorWithLedger(List.of(SOURCE_101, SOURCE_102))),
                        Optional.of(aliceVectorWithLedger(List.of())));
        doThrow(new DuplicateKeyException("dup-key")).doNothing()
                .when(entityNodeRepository).upsert(any(EntityNode.class), eq(OPERATOR));

        // when：执行合并（本批来源 301）
        graphMergeService.mergeNodesAndEdges(ctxWithSourceLimit(2),
                List.of(extractedEntity(ENTITY_ALICE, SOURCE_301)), List.of());

        // then：写回值取重试后加锁读到的账本（[101,301]），而非首轮旧值（截断后应为 [101,102]）
        ArgumentCaptor<EntityNode> captor = ArgumentCaptor.captor();
        verify(entityNodeRepository, times(2)).upsert(captor.capture(), eq(OPERATOR));
        assertEquals(List.of(SOURCE_101, SOURCE_301), captor.getValue().properties().sourceIds(),
                "重试后应以当前账本重新判定截断分支并重算写回值");
    }

    /**
     * 读-改-写位于同一事务（spec Scenario）：远程调用（描述摘要、向量化）全部发生在写回事务开启之前，
     * 写回事务体内仅含数据库 CRUD——以调用顺序与调用次数双重锁定事务纯净性。
     */
    @Test
    public void should_notInvokeRemoteCallsInsideWriteTransaction_when_mergeOneEntity_given_normalMerge() {
        // given：既有节点但账本缺失（fail-open 不短路）→ 实体正常写回
        when(entityNodeRepository.lockByKbIdAndNames(KB_ID, List.of(ENTITY_ALICE)))
                .thenReturn(List.of(persistedEntity(ENTITY_ALICE, "old-desc", List.of(SOURCE_101))));

        // when：执行合并
        graphMergeService.mergeNodesAndEdges(defaultCtx(),
                List.of(extractedEntity(ENTITY_ALICE, SOURCE_102)), List.of());

        // then：摘要/向量化 → 开启写回事务 → 库写入，远程调用均先于事务
        InOrder order = inOrder(descriptionSummarizer, embeddingClient, transactionTemplate,
                entityNodeRepository, entityInfoVectorRepository);
        order.verify(descriptionSummarizer).summarize(any(GraphMergeContext.class), anyString(), anyString(),
                anyList());
        order.verify(embeddingClient).embed(eq(EMBED_PROFILE), anyString());
        order.verify(transactionTemplate).executeWithoutResult(any());
        order.verify(entityNodeRepository).upsert(any(EntityNode.class), eq(OPERATOR));
        order.verify(entityInfoVectorRepository).upsert(any(EntityInfoVector.class), eq(OPERATOR));
        // 写回期间（含重试路径）不再产生任何远程调用
        verify(descriptionSummarizer, times(1)).summarize(any(), anyString(), anyString(), anyList());
        verify(embeddingClient, times(1)).embed(anyString(), anyString());
    }

    /**
     * 读-改-写位于同一事务（spec Scenario，覆盖实体与关系两条写回路径）：以写回事务回调的执行期标记
     * 复核「加锁读」与「写回」是否落在同一事务窗口内，并锁定加锁读先于写回的相对顺序；
     * 同时断言事务体内不出现任何远程调用（事务体内仅含数据库 CRUD）。
     */
    @Test
    public void should_lockAndWriteInsideSameTransaction_when_mergeNodesAndEdges_given_entityAndEdgeBatches() {
        // given：事务回调执行期标记 + 各端口调用时点的执行期归属采集
        AtomicBoolean inTransaction = new AtomicBoolean(false);
        AtomicBoolean entityLockInTransaction = new AtomicBoolean(false);
        AtomicBoolean entityUpsertInTransaction = new AtomicBoolean(false);
        AtomicBoolean edgeLockInTransaction = new AtomicBoolean(false);
        AtomicBoolean edgeUpsertInTransaction = new AtomicBoolean(false);
        AtomicBoolean remoteCallInTransaction = new AtomicBoolean(false);
        doAnswer(invocation -> {
            Consumer<TransactionStatus> consumer = invocation.getArgument(0);
            inTransaction.set(true);
            try {
                consumer.accept(mock(TransactionStatus.class));
            } finally {
                inTransaction.set(false);
            }
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        when(entityNodeRepository.lockByKbIdAndNames(KB_ID, List.of(ENTITY_ALICE)))
                .thenAnswer(invocation -> {
                    entityLockInTransaction.set(inTransaction.get());
                    return List.of(persistedEntity(ENTITY_ALICE, "alice-desc", List.of(SOURCE_101)));
                });
        // 关系写回事务内只加锁读边行与边向量（不再为端点加锁读实体行）
        when(relationEdgeRepository.lockByKbIdAndUnorderedPair(KB_ID, ENTITY_ALICE, ENTITY_BOB))
                .thenAnswer(invocation -> {
                    edgeLockInTransaction.set(inTransaction.get());
                    return List.of();
                });
        // 实体阶段的事务外普通读可见既有 Alice（账本缺失 → fail-open 不短路，写回确定发生）
        when(entityNodeRepository.findByKbIdAndName(KB_ID, ENTITY_ALICE))
                .thenReturn(Optional.of(persistedEntity(ENTITY_ALICE, "alice-desc", List.of(SOURCE_101))));
        doAnswer(invocation -> {
            entityUpsertInTransaction.set(inTransaction.get());
            return null;
        }).when(entityNodeRepository).upsert(any(EntityNode.class), eq(OPERATOR));
        doAnswer(invocation -> {
            edgeUpsertInTransaction.set(inTransaction.get());
            return null;
        }).when(relationEdgeRepository).upsertAll(anyList(), eq(OPERATOR));
        doAnswer(invocation -> {
            remoteCallInTransaction.set(inTransaction.get());
            return EMBEDDING_VECTOR;
        }).when(embeddingClient).embed(anyString(), anyString());
        doAnswer(invocation -> {
            remoteCallInTransaction.set(inTransaction.get());
            return null;
        }).when(descriptionSummarizer).summarize(any(), anyString(), anyString(), anyList());

        // when：同一批含 1 个实体与 1 条关系，两条写回路径各开启一次写回事务
        graphMergeService.mergeNodesAndEdges(defaultCtx(),
                List.of(extractedEntity(ENTITY_ALICE, SOURCE_101)),
                List.of(extractedEdge(ENTITY_ALICE, ENTITY_BOB, DEFAULT_EDGE_WEIGHT, EDGE_DESC_NORMAL,
                        List.of("knows"), SOURCE_101)));

        // then：两条路径的加锁读与写回均位于同一事务执行期
        assertTrue(entityLockInTransaction.get(), "实体加锁读 MUST 位于写回事务内");
        assertTrue(entityUpsertInTransaction.get(), "实体写回 MUST 与加锁读处于同一事务");
        assertTrue(edgeLockInTransaction.get(), "关系加锁读 MUST 位于写回事务内");
        assertTrue(edgeUpsertInTransaction.get(), "关系写回 MUST 与加锁读处于同一事务");
        assertFalse(remoteCallInTransaction.get(), "写回事务体内 MUST NOT 出现远程调用");
        // then：加锁读严格先于写回，且各路径仅开启一次写回事务
        InOrder entityOrder = inOrder(entityNodeRepository);
        entityOrder.verify(entityNodeRepository).lockByKbIdAndNames(KB_ID, List.of(ENTITY_ALICE));
        entityOrder.verify(entityNodeRepository).upsert(any(EntityNode.class), eq(OPERATOR));
        InOrder edgeOrder = inOrder(relationEdgeRepository);
        edgeOrder.verify(relationEdgeRepository).lockByKbIdAndUnorderedPair(KB_ID, ENTITY_ALICE, ENTITY_BOB);
        edgeOrder.verify(relationEdgeRepository).upsertAll(anyList(), eq(OPERATOR));
        verify(transactionTemplate, times(2)).executeWithoutResult(any());
        verify(descriptionSummarizer, times(2)).summarize(any(), anyString(), anyString(), anyList());
        verify(embeddingClient, times(2)).embed(anyString(), anyString());
    }

    /**
     * 批内同名实体聚合：两条同名实体归一为一次写回，描述列表精确累积供摘要收敛。
     */
    @Test
    public void should_aggregateSameNameEntities_when_mergeNodesAndEdges_given_duplicateNamesInBatch() {
        // given：批内两条同名 Alice（描述与来源不同）

        // when：执行合并
        GraphMergeReport report = graphMergeService.mergeNodesAndEdges(defaultCtx(),
                List.of(extractedEntityWithDesc(ENTITY_ALICE, "desc-a", SOURCE_101),
                        extractedEntityWithDesc(ENTITY_ALICE, "desc-b", SOURCE_102)),
                List.of());

        // then：仅一次写回；摘要描述候选按首现顺序累积，来源并入
        ArgumentCaptor<List<String>> descCaptor = ArgumentCaptor.captor();
        verify(descriptionSummarizer).summarize(eq(defaultCtx()), eq(ENTITY_ALICE), eq(ENTITY_TYPE_PERSON),
                descCaptor.capture());
        assertEquals(List.of("desc-a", "desc-b"), descCaptor.getValue(), "描述候选应按首现顺序精确累积");
        ArgumentCaptor<EntityNode> nodeCaptor = ArgumentCaptor.captor();
        verify(entityNodeRepository).upsert(nodeCaptor.capture(), eq(OPERATOR));
        assertEquals(List.of(SOURCE_101, SOURCE_102), nodeCaptor.getValue().properties().sourceIds(),
                "同名实体来源应去重累积");
        assertEquals(new GraphMergeReport(1, 0, 0, 0, 0), report, "同名实体应聚合为一次写回");
    }

    /**
     * 写前锚点机制已退役（任务 4.5）：正常批次合并实体与关系后，写回的实体仅为真实抽取条目，
     * MUST NOT 出现任何以保留前缀 {@code __full_entities__} / {@code __full_relations__} 命名的锚点条目。
     */
    @Test
    public void should_notWriteReservedAnchorEntities_when_mergeNodesAndEdges_given_regularBatch() {
        // given：正常批次——2 个新实体（Alice/Bob）+ 1 条 belongs_to 关系（Doc1→Folder1）

        // when：执行合并
        GraphMergeReport report = graphMergeService.mergeNodesAndEdges(defaultCtx(),
                List.of(extractedEntity(ENTITY_ALICE, SOURCE_101), extractedEntity(ENTITY_BOB, SOURCE_102)),
                List.of(extractedEdge(ENTITY_DOC, ENTITY_FOLDER, BELONGS_TO_WEIGHT, EDGE_DESC_BELONGS_TO,
                        List.of(KEYWORD_BELONGS_TO), SOURCE_301)));

        // then：捕获实体图行 upsert 全部入参，应仅为真实抽取条目（精确匹配两次写回）
        ArgumentCaptor<EntityNode> captor = ArgumentCaptor.captor();
        verify(entityNodeRepository, times(2)).upsert(captor.capture(), eq(OPERATOR));
        List<String> writtenNames = captor.getAllValues().stream()
                .map(EntityNode::entityName)
                .toList();
        assertEquals(List.of(ENTITY_ALICE, ENTITY_BOB), writtenNames, "写入实体应为本批全部真实抽取条目");
        // then：任何写入名均不得携带已退役机制的保留锚点前缀
        for (String writtenName : writtenNames) {
            assertFalse(writtenName.startsWith(RESERVED_ENTITY_ANCHOR_PREFIX),
                    "MUST NOT 写入实体侧保留锚点前缀条目: " + RESERVED_ENTITY_ANCHOR_PREFIX);
            assertFalse(writtenName.startsWith(RESERVED_RELATION_ANCHOR_PREFIX),
                    "MUST NOT 写入关系侧保留锚点前缀条目: " + RESERVED_RELATION_ANCHOR_PREFIX);
        }
        // then：锚点退役不影响正常合并，关系仍照常落库一次
        assertEquals(1, report.relationsWritten(), "正常批次的关系仍应写回");
    }

    /**
     * 权重基准为向量账本而非图行展示列（新增用例 1）：既有边的展示来源列被刻意置空，
     * 但向量账本已记录本批来源 → 重放该来源时权重增量恒为 0。
     *
     * <p>若计权基准误取图行 source_ids（此处为空），本批来源会被判为「未记账」而把 1.0 加到 2.0。</p>
     */
    @Test
    public void should_keepWeightUnchanged_when_mergeNodesAndEdges_given_replayedSameSourcesCoveredByVectorLedger() {
        // given：既有边 weight=1.0 且展示来源列为空、向量账本 [101]；
        //        本批重放来源 101（全额 1.0）；旧行关键词与本批不重叠以绕开幂等短路
        when(relationEdgeRepository.lockByKbIdAndUnorderedPair(KB_ID, ENTITY_ALICE, ENTITY_BOB))
                .thenReturn(List.of(persistedEdge(ENTITY_ALICE, ENTITY_BOB, DEFAULT_EDGE_WEIGHT, "old-rel",
                        List.of("other"), List.of())));
        stubRelationLedger(ENTITY_ALICE, ENTITY_BOB, List.of(SOURCE_101));

        // when：执行合并
        GraphMergeReport report = graphMergeService.mergeNodesAndEdges(defaultCtx(), List.of(),
                List.of(extractedEdge(ENTITY_ALICE, ENTITY_BOB, DEFAULT_EDGE_WEIGHT, EDGE_DESC_NORMAL,
                        List.of("knows"), SOURCE_101)));

        // then：以向量账本为基准，已记账来源增量为 0，权重保持既有值
        ArgumentCaptor<List<RelationEdge>> captor = ArgumentCaptor.captor();
        verify(relationEdgeRepository).upsertAll(captor.capture(), eq(OPERATOR));
        assertEquals(DEFAULT_EDGE_WEIGHT, captor.getValue().get(0).properties().weight(),
                "权重过滤基准 MUST 为向量账本而非图行展示来源列");
        assertEquals(List.of(SOURCE_101), captor.getValue().get(0).properties().sourceIds(),
                "展示来源列应重新并入本批来源（纯展示维护）");
        assertEquals(1, report.relationsWritten(), "关键词未覆盖时不短路，仍应写回");
    }

    /**
     * 新文档来源正常累加（新增用例 3）：账本 [101]、本批为未记账来源 102 → 权重加一次全额且账本并入 102。
     */
    @Test
    public void should_accumulateWeight_when_mergeNodesAndEdges_given_newDocumentSource() {
        // given：既有边 weight=1.0、展示来源 [101]、向量账本 [101]；本批为新文档来源 102
        when(relationEdgeRepository.lockByKbIdAndUnorderedPair(KB_ID, ENTITY_ALICE, ENTITY_BOB))
                .thenReturn(List.of(persistedEdge(ENTITY_ALICE, ENTITY_BOB, DEFAULT_EDGE_WEIGHT, "old-rel",
                        List.of("knows"), List.of(SOURCE_101))));
        stubRelationLedger(ENTITY_ALICE, ENTITY_BOB, List.of(SOURCE_101));

        // when：执行合并
        GraphMergeReport report = graphMergeService.mergeNodesAndEdges(defaultCtx(), List.of(),
                List.of(extractedEdge(ENTITY_ALICE, ENTITY_BOB, DEFAULT_EDGE_WEIGHT, EDGE_DESC_NORMAL,
                        List.of("knows"), SOURCE_102)));

        // then：weight = 1.0 + 1.0，图行展示列与向量账本均并入新来源
        ArgumentCaptor<List<RelationEdge>> captor = ArgumentCaptor.captor();
        verify(relationEdgeRepository).upsertAll(captor.capture(), eq(OPERATOR));
        assertEquals(2 * DEFAULT_EDGE_WEIGHT, captor.getValue().get(0).properties().weight(),
                "未记账来源应全额累加一次");
        assertEquals(List.of(SOURCE_101, SOURCE_102), captor.getValue().get(0).properties().sourceIds(),
                "图行展示来源列应并入新来源");
        ArgumentCaptor<List<RelationInfoVector>> vectorCaptor = ArgumentCaptor.captor();
        verify(relationInfoVectorRepository).upsertAll(vectorCaptor.capture(), eq(OPERATOR));
        assertEquals(List.of(SOURCE_101, SOURCE_102), vectorCaptor.getValue().get(0).chunkIds(),
                "权威向量账本应并入新来源，供后续重放幂等判定");
        assertEquals(1, report.relationsWritten(), "新来源未覆盖账本时应写回");
    }

    /**
     * 截断不吃账本（新增用例 4）：来源数超过图行展示上限并被截断后，重放全量来源仍应幂等短路。
     *
     * <p>向量账本恒为截断前的全量去重并集，故「展示列饱和」不会再造成「永不幂等」。
     * 本用例只打桩写回事务内的加锁读（事务外快照不可见），令权威短路判定被真正执行。</p>
     */
    @Test
    public void should_stillShortCircuit_when_mergeNodesAndEdges_given_sourcesExceedTruncationLimit() {
        // given：上限 2；既有实体/关系图行展示列均被截断为 [101,102]（103 仅存活于向量账本），
        //        向量账本为截断前全量 [101,102,103]
        when(entityNodeRepository.lockByKbIdAndNames(KB_ID, List.of(ENTITY_ALICE)))
                .thenReturn(List.of(persistedEntity(ENTITY_ALICE, "old-desc",
                        List.of(SOURCE_101, SOURCE_102))));
        stubEntityLedger(ENTITY_ALICE, List.of(SOURCE_101, SOURCE_102, SOURCE_103));
        when(relationEdgeRepository.lockByKbIdAndUnorderedPair(KB_ID, ENTITY_ALICE, ENTITY_BOB))
                .thenReturn(List.of(persistedEdge(ENTITY_ALICE, ENTITY_BOB, 3 * DEFAULT_EDGE_WEIGHT,
                        "old-rel", List.of("knows"), List.of(SOURCE_101, SOURCE_102))));
        stubRelationLedger(ENTITY_ALICE, ENTITY_BOB, List.of(SOURCE_101, SOURCE_102, SOURCE_103));

        // when：实体与关系各重放三个来源
        GraphMergeReport report = graphMergeService.mergeNodesAndEdges(ctxWithSourceLimit(2),
                List.of(extractedEntityWithDesc(ENTITY_ALICE, "desc-a", SOURCE_101),
                        extractedEntityWithDesc(ENTITY_ALICE, "desc-b", SOURCE_102),
                        extractedEntity(ENTITY_ALICE, SOURCE_103)),
                List.of(extractedEdge(ENTITY_ALICE, ENTITY_BOB, DEFAULT_EDGE_WEIGHT, EDGE_DESC_NORMAL,
                                List.of("knows"), SOURCE_101),
                        extractedEdge(ENTITY_ALICE, ENTITY_BOB, DEFAULT_EDGE_WEIGHT, EDGE_DESC_NORMAL,
                                List.of("knows"), SOURCE_102),
                        extractedEdge(ENTITY_ALICE, ENTITY_BOB, DEFAULT_EDGE_WEIGHT, EDGE_DESC_NORMAL,
                                List.of("knows"), SOURCE_103)));

        // then：覆盖判定成立，实体与关系均短路，零写回（写放大为零）
        assertEquals(new GraphMergeReport(0, 1, 0, 1, 0), report, "展示列截断 MUST NOT 影响幂等覆盖判定");
        verify(entityNodeRepository, never()).upsert(any(EntityNode.class), any());
        verify(entityInfoVectorRepository, never()).upsert(any(EntityInfoVector.class), any());
        verify(relationEdgeRepository, never()).upsertAll(anyList(), any());
        verify(relationInfoVectorRepository, never()).upsertAll(anyList(), any());
    }

    /**
     * 反向端点对复用同一条目（新增用例 5）：旁路传入的反向贡献边 (B,A) 与既有行 (A,B) 命中同一无向对，
     * 复用既有条目计权，写回仍为单行且方向为归一方向，MUST NOT 产生任何删除类交互。
     */
    @Test
    public void should_reuseSingleEntry_when_mergeNodesAndEdges_given_reversedEndpointPairIncoming() {
        // given：既有行 Alice→Bob（weight=1.0、展示来源 [101]）、向量账本 [101]；
        //        本批边以反序 Bob→Alice 传入（模拟未经归一的旁路入口），来源 102
        when(relationEdgeRepository.lockByKbIdAndUnorderedPair(KB_ID, ENTITY_ALICE, ENTITY_BOB))
                .thenReturn(List.of(persistedEdge(ENTITY_ALICE, ENTITY_BOB, DEFAULT_EDGE_WEIGHT, "old-rel",
                        List.of("knows"), List.of(SOURCE_101))));
        stubRelationLedger(ENTITY_ALICE, ENTITY_BOB, List.of(SOURCE_101));

        // when：执行合并
        graphMergeService.mergeNodesAndEdges(defaultCtx(), List.of(),
                List.of(extractedEdge(ENTITY_BOB, ENTITY_ALICE, DEFAULT_EDGE_WEIGHT, EDGE_DESC_NORMAL,
                        List.of("knows"), SOURCE_102)));

        // then：加锁读以归一对进行，写回单行方向仍为 Alice→Bob，权重 1.0 + 1.0
        verify(relationEdgeRepository).lockByKbIdAndUnorderedPair(KB_ID, ENTITY_ALICE, ENTITY_BOB);
        ArgumentCaptor<List<RelationEdge>> captor = ArgumentCaptor.captor();
        verify(relationEdgeRepository).upsertAll(captor.capture(), eq(OPERATOR));
        assertEquals(1, captor.getValue().size(), "反向条目 MUST 复用既有单条目");
        RelationEdge finalEdge = captor.getValue().get(0);
        assertEquals(ENTITY_ALICE, finalEdge.sourceName(), "写回方向应为字典序归一方向");
        assertEquals(ENTITY_BOB, finalEdge.targetName(), "写回方向应为字典序归一方向");
        assertEquals(2 * DEFAULT_EDGE_WEIGHT, finalEdge.properties().weight(), "新来源应全额累加一次");
        ArgumentCaptor<List<RelationInfoVector>> vectorCaptor = ArgumentCaptor.captor();
        verify(relationInfoVectorRepository).upsertAll(vectorCaptor.capture(), eq(OPERATOR));
        assertEquals(List.of(ENTITY_ALICE, ENTITY_BOB),
                vectorCaptor.getValue().get(0).normalizedPair(), "向量行身份仍为同一无向端点对");
        assertEquals(List.of(SOURCE_101, SOURCE_102), vectorCaptor.getValue().get(0).chunkIds(),
                "向量账本应并入本批新来源");
        // then：关系仓储交互仅「读 + upsert」，不存在任何删除类写交互
        verify(relationEdgeRepository).findByKbIdAndUnorderedPair(KB_ID, ENTITY_ALICE, ENTITY_BOB);
        verifyNoMoreInteractions(relationEdgeRepository);
    }

    /**
     * 收口接入①：实体未短路（确有写入）时，提交后以「知识库 + 实体名」身份触发实体侧收口一次。
     */
    @Test
    public void should_reconcileEntityOnce_when_mergeOneEntity_given_writeHappened() {
        // given：账本缺失 fail-open → 实体写回（非短路）
        when(entityNodeRepository.lockByKbIdAndNames(KB_ID, List.of(ENTITY_ALICE)))
                .thenReturn(List.of(persistedEntity(ENTITY_ALICE, "old-desc", List.of(SOURCE_101))));

        // when：执行合并
        graphMergeService.mergeNodesAndEdges(defaultCtx(),
                List.of(extractedEntity(ENTITY_ALICE, SOURCE_102)), List.of());

        // then：以身份 + 生效配置触发收口一次（不传入本次描述/写入值，见 design D5）
        verify(vectorContentReconciler, times(1)).reconcileEntity(KB_ID, ENTITY_ALICE, EMBED_PROFILE,
                GraphMergeParams.DEFAULT_EMBEDDING_TOKEN_LIMIT);
    }

    /**
     * 收口接入②：实体被幂等短路跳过（未产生写入）时，MUST NOT 触发收口。
     */
    @Test
    public void should_notReconcileEntity_when_mergeOneEntity_given_idempotentShortCircuit() {
        // given：事务外快照即覆盖本批来源 → 短路跳过
        when(entityNodeRepository.findByKbIdAndName(KB_ID, ENTITY_ALICE))
                .thenReturn(Optional.of(persistedEntity(ENTITY_ALICE, "old-desc",
                        List.of(SOURCE_101, SOURCE_102))));
        when(entityInfoVectorRepository.findByKbIdAndName(KB_ID, ENTITY_ALICE))
                .thenReturn(Optional.of(EntityInfoVector.create(KB_ID, ENTITY_ALICE,
                        EntityInfoVector.buildContent(ENTITY_ALICE, "ledger-desc"),
                        List.of(SOURCE_101, SOURCE_102), OLD_VECTOR)));

        // when：执行合并
        GraphMergeReport report = graphMergeService.mergeNodesAndEdges(ctxWithSourceLimit(2),
                List.of(extractedEntity(ENTITY_ALICE, SOURCE_101)), List.of());

        // then：短路计数 1、写入计数 0，且收口零触发
        assertEquals(new GraphMergeReport(0, 1, 0, 0, 0), report);
        verify(vectorContentReconciler, never()).reconcileEntity(any(), anyString(), anyString(), anyInt());
    }

    /**
     * 收口接入③：收口抛异常时降级——合并仍计入写入成功，异常不上抛为阶段失败。
     */
    @Test
    public void should_keepEntityWrittenCount_when_mergeOneEntity_given_reconcileThrows() {
        // given：实体正常写回；收口原语抛异常
        when(entityNodeRepository.lockByKbIdAndNames(KB_ID, List.of(ENTITY_ALICE)))
                .thenReturn(List.of(persistedEntity(ENTITY_ALICE, "old-desc", List.of(SOURCE_101))));
        when(vectorContentReconciler.reconcileEntity(any(), anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("reconcile down"));

        // when：执行合并（不应因收口异常而阶段失败）
        GraphMergeReport report = graphMergeService.mergeNodesAndEdges(defaultCtx(),
                List.of(extractedEntity(ENTITY_ALICE, SOURCE_102)), List.of());

        // then：合并计数仍为写入成功 1
        assertEquals(1, report.entitiesWritten(), "收口异常 MUST NOT 影响已提交的合并写入计数");
    }

    /**
     * 方向归一④：批次方向 (A,B) 而库中行为 (B,A) 时，落库方向恒为 (A,B)，
     * 且属性（权重/描述/关键词/来源账本）与合并结果一致（读侧不丢反向行属性）。
     */
    @Test
    public void should_writeNormalizedDirectionWithMergedProperties_when_mergeOneEdge_given_batchABAndLegacyReverseBA() {
        // given：库内仅存反向行 Bob→Alice（weight=1.0、描述 legacy-rel、关键词 [knows]、来源 [201]），
        //        本批以归一方向 Alice→Bob 报新来源 303
        when(relationEdgeRepository.lockByKbIdAndUnorderedPair(KB_ID, ENTITY_ALICE, ENTITY_BOB))
                .thenReturn(List.of(persistedEdge(ENTITY_BOB, ENTITY_ALICE, DEFAULT_EDGE_WEIGHT,
                        "legacy-rel", List.of("knows"), List.of(SOURCE_201))));

        // when：执行合并
        graphMergeService.mergeNodesAndEdges(defaultCtx(), List.of(),
                List.of(extractedEdge(ENTITY_ALICE, ENTITY_BOB, DEFAULT_EDGE_WEIGHT, EDGE_DESC_NORMAL,
                        List.of("knows"), SOURCE_303)));

        // then：落库方向恒为字典序 (Alice, Bob)，非沿用历史反向行方向
        ArgumentCaptor<List<RelationEdge>> captor = ArgumentCaptor.captor();
        verify(relationEdgeRepository).upsertAll(captor.capture(), eq(OPERATOR));
        RelationEdge finalEdge = captor.getValue().get(0);
        assertEquals(ENTITY_ALICE, finalEdge.sourceName(), "落库源端点 MUST 为归一方向字典序较小者");
        assertEquals(ENTITY_BOB, finalEdge.targetName(), "落库目标端点 MUST 为归一方向字典序较大者");
        // then：属性取合并结果——权重累加、描述与关键词并入历史反向行、来源账本为并集
        assertEquals(2 * DEFAULT_EDGE_WEIGHT, finalEdge.properties().weight(), "历史反向行权重应与本批全额累加");
        assertEquals("legacy-rel", finalEdge.properties().description(), "历史反向行描述 MUST 参与合并");
        assertEquals(List.of("knows"), finalEdge.properties().keywords(), "关键词 MUST 并入合并结果");
        assertEquals(List.of(SOURCE_201, SOURCE_303), finalEdge.properties().sourceIds(),
                "来源账本 MUST 为历史反向行与本批来源的并集");
    }

    /**
     * 状态化仓储桩读取当前边行集合：方向归一后同一无向端点对至多一行（按 source/target 升序输出）。
     *
     * @param edgeStore 边存储（键为带方向的 {@link #edgeKey}）
     * @return 当前存在的边行列表
     */
    private List<RelationEdge> storedRows(Map<String, RelationEdge> edgeStore) {
        RelationEdge row = edgeStore.get(edgeKey(ENTITY_ALICE, ENTITY_BOB));
        return ObjectUtils.isEmpty(row) ? List.of() : List.of(row);
    }

    /**
     * 打桩状态化关系仓储：边图行与边向量行的「事务外普通读 / 事务内加锁读」共用同一份内存状态，
     * upsert 回写该状态，模拟真实库内「读-改-写」的逐次演化。
     *
     * @param edgeStore   边图行内存态（键为带方向的 {@link #edgeKey}）
     * @param vectorStore 边向量行内存态（键为归一端点对的 {@link #edgeKey}）
     */
    private void wireStatefulRelationStores(Map<String, RelationEdge> edgeStore,
                                            Map<String, RelationInfoVector> vectorStore) {
        when(relationEdgeRepository.findByKbIdAndUnorderedPair(KB_ID, ENTITY_ALICE, ENTITY_BOB))
                .thenAnswer(invocation -> storedRows(edgeStore));
        when(relationEdgeRepository.lockByKbIdAndUnorderedPair(KB_ID, ENTITY_ALICE, ENTITY_BOB))
                .thenAnswer(invocation -> storedRows(edgeStore));
        doAnswer(invocation -> {
            List<RelationEdge> edges = invocation.getArgument(0);
            for (RelationEdge edge : edges) {
                edgeStore.put(edgeKey(edge.sourceName(), edge.targetName()), edge);
            }
            return null;
        }).when(relationEdgeRepository).upsertAll(anyList(), eq(OPERATOR));
        when(relationInfoVectorRepository.findByKbIdAndUnorderedPair(KB_ID, ENTITY_ALICE, ENTITY_BOB))
                .thenAnswer(invocation -> Optional.ofNullable(vectorStore.get(edgeKey(ENTITY_ALICE, ENTITY_BOB))));
        when(relationInfoVectorRepository.lockByKbIdAndUnorderedPair(KB_ID, ENTITY_ALICE, ENTITY_BOB))
                .thenAnswer(invocation -> Optional.ofNullable(vectorStore.get(edgeKey(ENTITY_ALICE, ENTITY_BOB))));
        doAnswer(invocation -> {
            List<RelationInfoVector> vectors = invocation.getArgument(0);
            for (RelationInfoVector vector : vectors) {
                vectorStore.put(edgeKey(vector.sourceName(), vector.targetName()), vector);
            }
            return null;
        }).when(relationInfoVectorRepository).upsertAll(anyList(), eq(OPERATOR));
    }
}
