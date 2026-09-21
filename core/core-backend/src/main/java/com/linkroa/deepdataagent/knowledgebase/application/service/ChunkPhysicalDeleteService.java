package com.linkroa.deepdataagent.knowledgebase.application.service;

import com.linkroa.deepdataagent.knowledgebase.api.DocumentGraphConvergenceApi;
import com.linkroa.deepdataagent.knowledgebase.api.DocumentGraphConvergencePlan;
import com.linkroa.deepdataagent.knowledgebase.api.DocumentGraphConvergenceResult;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Document;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ChunkSource;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.ChunkRepresentationRepository;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.ChunkRepository;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.DocumentRepository;
import org.apache.commons.lang3.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 切片物理删除原语（三层删除链共用的事务内核；OpenSpec rebuild-kg-on-document-delete / 组 6
 * 重排为「事务外重建计算 → 事务内收敛与删除」两段式，design D3）。
 *
 * <p><b>执行次序（编号即固定序，不可调换）</b>：</p>
 * <ol>
 *     <li>【事务外·纯读】预取「切片 → 来源」投影，推导本批是否需要图谱收敛（含解析来源才收敛）；</li>
 *     <li>【事务外·纯读】按需预取「切片 → 文档」投影（计数分组 + 重建审计锚点）与
 *     「切片 → 知识库」投影（收敛按库收窄扫描面）——切片行一旦消失即无从反查，全部删行前取数；</li>
 *     <li>【事务外·远程】按库分组逐组经 {@link DocumentGraphConvergenceApi#prepare} 完成图谱
 *     重建计算（重放 + 摘要 + 向量化，仅内存、零写入）——远程调用 MUST NOT 进入数据库事务
 *     （事务规范 1.1），故计算段必须前置到事务开启之前；全人工批整体跳过（连探测读都不发起）；</li>
 *     <li>【单事务·纯 DB】{@code transactionTemplate} 内固定序：
 *     ① 逐组 {@link DocumentGraphConvergenceApi#apply}（重建写回 → 账本收缩 → 展示列收缩 →
 *     归属清理与按引用计数的缓存回收）→ ② 删 1:1 向量/全文表示（仓储实现内「向量 → 全文」
 *     固定序）→ ③ 物理删切片行 → ④ 按需回写文档分块计数。
 *     <b>顺序不变量：切片行删除 MUST 晚于重建读取抽取缓存与缓存回收</b>——这是整链崩溃可重入的
 *     全部依据：prepare 与事务之间崩溃时零写入残留（第③步尚未开始）；事务内任何一步失败整体
 *     回滚、切片行仍在——重试从「条目账本 ∩ 被删分块」重新推导同一批受影响条目，
 *     MUST NOT 依赖任何日志表 / 待办记录 / 恢复证明（Non-Goals）。整链（含收敛、回收、删行）
 *     原子生效，不存在「部分删除」破坏态；</li>
 *     <li>【事务提交后·事务外·远程】逐组
 *     {@link DocumentGraphConvergenceApi#reconcilePendingVectorContent} 向量收口（含向量化远程
 *     调用，MUST 在提交后）；收口异常仅 ERROR 留痕，MUST NOT 反噬已提交的删除事实。</li>
 * </ol>
 *
 * <p><b>事务边界与传播语义（选型说明）</b>：原实现为方法级
 * {@code @Transactional(REQUIRED)} 单事务，收敛（纯 SQL）可以同事务完成；重建计算引入远程调用
 * 后声明式事务无法表达「部分在事务外」——按事务规范 2.1/2.3 本原语改由
 * {@link TransactionTemplate} 精确包裹第④步 DB 写段（模板默认传播 REQUIRED：调用方已有事务时
 * 并入既有事务、不新起，与既有 {@code Propagation.REQUIRED} 语义逐字一致——重排后该传播仍
 * 成立，因为本原语与调用方共享同一事务管理器连接）。同一方法内不再混用声明式注解与编程式
 * 模板（事务规范 2.1 禁令），故移除 {@code @Transactional}。</p>
 *
 * <p><b>固定加锁顺序与三链一致性（事务规范 3.1）</b>：人工块链（{@code ChunkApplicationService}）、
 * 文档链（{@code DocumentApplicationService}）、整库链（{@code DefaultKbCleanupWriter}）三方复用
 * 本原语，事务体内步骤序逐字相同（先锁图行、后删分块行）；批内恒按切片主键升序取数与删行、
 * 按库分组稳定迭代、组内条目按名/端点对升序加锁（rag 侧写回纪律）——三链之间以及与并发摄入
 * 交叉时不产生反向加锁序，本次重排未破坏该一致性。</p>
 *
 * <p><b>收敛触发由数据推导，不由调用方声明</b>（分块引用完整性不变量的结构性保证）：人工块
 * 构造过程不产生图谱贡献，全人工批跳过第③⑤步（prepare/reconcile）与第④步中的图谱段是数据
 * 事实的自然结果——人工块批 MUST 零远程、零图谱读写（连 {@code chunk_extract_cache} 追溯读也
 * 不发起）。仅保留 {@code syncDocumentChunkCount} 一个计数侧开关（文档链 / 整库链终删前置下
 * 回写属白做功）。</p>
 *
 * <p><b>失败语义（零重试）</b>：第①②③④步任一步异常照实上抛——第③步（prepare 远程失败）失败时
 * 尚无任何 DB 写入；第④步失败整体回滚（切片行仍在、缓存未回收、图账本未收缩），用户重删即
 * 自第①步完整重推（各步幂等）。第⑤步收口异常不上抛（删除已提交，收口以 WARN/ERROR 留痕后
 * 由条目下次整体重建收敛）。</p>
 *
 * <p><b>批次口径</b>：本方法为单批实现，不做内部切片；{@code chunkIds} 超过 500 条的分批由
 * 调用方负责（事务规范「批量 500~1000 条/批」）。空集合入参直接返回，零 DB 交互。</p>
 *
 * @author DeepDataAgent
 */
@Service
public class ChunkPhysicalDeleteService {

    private static final Logger log = LoggerFactory.getLogger(ChunkPhysicalDeleteService.class);

    /** 切片仓储（删行前取来源 / 文档 / 知识库投影，删行与计数） */
    private final ChunkRepository chunkRepository;

    /** 切片 1:1 派生表示仓储（向量 → 全文固定序清退） */
    private final ChunkRepresentationRepository chunkRepresentationRepository;

    /** 文档仓储（分块计数回写前的存活读取） */
    private final DocumentRepository documentRepository;

    /** 图谱贡献收敛契约（进程内消费，实现位于 rag BC；prepare 事务外 / apply 事务内 / 收口提交后） */
    private final DocumentGraphConvergenceApi documentGraphConvergenceApi;

    /** 编程式事务模板（精确包裹 DB 写段；REQUIRED——调用方已有事务时并入） */
    private final TransactionTemplate transactionTemplate;

    /**
     * 构造切片物理删除原语。
     *
     * @param chunkRepository               切片仓储
     * @param chunkRepresentationRepository 切片 1:1 派生表示仓储
     * @param documentRepository            文档仓储
     * @param documentGraphConvergenceApi   图谱贡献收敛契约（跨 BC 端口，实现位于 rag BC）
     * @param transactionTemplate           编程式事务模板
     */
    public ChunkPhysicalDeleteService(ChunkRepository chunkRepository,
                                      ChunkRepresentationRepository chunkRepresentationRepository,
                                      DocumentRepository documentRepository,
                                      DocumentGraphConvergenceApi documentGraphConvergenceApi,
                                      TransactionTemplate transactionTemplate) {
        this.chunkRepository = chunkRepository;
        this.chunkRepresentationRepository = chunkRepresentationRepository;
        this.documentRepository = documentRepository;
        this.documentGraphConvergenceApi = documentGraphConvergenceApi;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 物理删除一批切片及其全部派生数据（三层删除链共用原语；两段式执行次序见类 Javadoc）。
     * <p>图谱账本收敛与缓存回收不再由调用方声明：本方法在删行之前按「切片 → 来源」投影推导——
     * 批内含「解析产生」的切片即执行「事务外重建计算 → 事务内写回收敛与回收」，全为「人工新增」
     * 则整体跳过、对图谱账本、图行与抽取缓存零读写。</p>
     *
     * @param chunkIds                待删除切片ID集合；为空（或全为空元素）直接返回，零 DB 交互；
     *                                单批不超过 500 条（分批由调用方负责）
     * @param operator                操作人标识，可为空；用于审计口径透传（重建写回与文档计数
     *                                回写的 {@code updated_by} 由持久层统一填充兜底）
     * @param syncDocumentChunkCount  是否回写受影响文档的分块计数：人工删除入口传 {@code true}；
     *                                文档链 / 整库链每批清退传 {@code false}（文档行即将终删，回写属白做功）
     */
    public void deleteChunks(Collection<Long> chunkIds, String operator, boolean syncDocumentChunkCount) {
        if (CollectionUtils.isEmpty(chunkIds)) {
            return;
        }
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(chunkIds));
        ids.removeIf(ObjectUtils::isEmpty);
        if (CollectionUtils.isEmpty(ids)) {
            return;
        }
        // 全部投影取数都必须在删行之前：切片行一旦消失，受影响文档集合、所属知识库与来源标识即无从回查
        boolean convergeRequired = containsParsedSource(chunkRepository.findSourcesByChunkIds(ids));
        Map<Long, Long> documentIdByChunk = syncDocumentChunkCount || convergeRequired
                ? chunkRepository.findDocumentIdsByChunkIds(ids)
                : Map.of();
        Collection<Long> affectedDocumentIds = syncDocumentChunkCount
                ? distinctDocumentIds(documentIdByChunk)
                : List.of();
        Map<Long, List<Long>> idsByKbId = convergeRequired ? groupIdsByKbId(ids) : Map.of();
        // 【事务外·远程】逐库分组完成重建计算（全人工批整体跳过，零远程、零图谱读写）
        List<PreparedGroup> preparedGroups = convergeRequired
                ? prepareGroupedByKbId(idsByKbId, documentIdByChunk, ids.size(), operator)
                : List.of();
        // 【单事务】写回收敛与回收 → 删表示 → 删切片行 → 按需计数回写（顺序不变量：删行 MUST 最后）
        List<DocumentGraphConvergenceResult> applyResults = transactionTemplate.execute(status -> {
            List<DocumentGraphConvergenceResult> results = new ArrayList<>(preparedGroups.size());
            int removedEntries = 0;
            int rebuiltEntries = 0;
            for (PreparedGroup group : preparedGroups) {
                DocumentGraphConvergenceResult result =
                        documentGraphConvergenceApi.apply(group.convergencePlan());
                results.add(result);
                removedEntries += result.prunedEntries();
                rebuiltEntries += result.rebuiltEntries();
            }
            // ①② 1:1 表示：向量 → 全文（仓储实现内即此固定序，标准 delete 即物理 DELETE）
            chunkRepresentationRepository.deleteByChunkIds(ids);
            // ③ 切片行物理删除（MUST 晚于收敛与缓存回收——崩溃可重入的全部依据，见类 Javadoc）
            chunkRepository.deleteByIds(ids);
            // ④ 文档分块计数回写（按需）
            if (syncDocumentChunkCount) {
                syncChunkCount(affectedDocumentIds, operator);
            }
            logCounts(convergeRequired, idsByKbId, ids.size(), affectedDocumentIds.size(),
                    removedEntries, rebuiltEntries, operator);
            return results;
        });
        // 【事务提交后·事务外·远程】向量收口（异常仅 ERROR 留痕，MUST NOT 反噬已提交删除）
        reconcileQuietly(preparedGroups, applyResults);
    }

    /**
     * 删除完成日志（收敛批与全人工批两种口径）。
     *
     * @param convergeRequired 本批是否触发图谱收敛
     * @param idsByKbId        知识库分组投影
     * @param chunkCount       本批切片数
     * @param documentCount    计数回写文档数
     * @param removedEntries   收敛删除条目数合计
     * @param rebuiltEntries   重建条目数合计
     * @param operator         操作人（留痕口径）
     */
    private void logCounts(boolean convergeRequired, Map<Long, List<Long>> idsByKbId, int chunkCount,
                           int documentCount, int removedEntries, int rebuiltEntries, String operator) {
        if (convergeRequired) {
            log.info("切片物理删除原语完成（批次含解析来源，已收敛图谱账本并回收抽取缓存）, 切片数={},"
                            + " 知识库分组数={}, 收敛图条目数={}, 重建条目数={}, 计数回写文档数={}, operator={}",
                    chunkCount, idsByKbId.size(), removedEntries, rebuiltEntries, documentCount, operator);
        } else {
            log.info("切片物理删除原语完成（批次全为人工来源，跳过图谱收敛）, 切片数={},"
                            + " 计数回写文档数={}, operator={}", chunkCount, documentCount, operator);
        }
    }

    /**
     * 【事务外·远程】按预取的知识库分组逐组执行收敛 prepare（重建计算，零写入）。
     * <p>每组审计锚点取该组切片所属文档的最小文档ID（文档链批次恒为单一文档即该文档本身；
     * 整库清退跨文档批次取最小值仅作日志定位，不参与判定）；锚点不可得（并发下投影缺失）时
     * 契约侧按「重建能力缺失」降级——账本收缩与缓存回收照常，删除链不阻断。</p>
     *
     * @param idsByKbId         预取的「知识库 → 切片ID集合」分组
     * @param documentIdByChunk 预取的「切片 → 文档ID」投影
     * @param totalIds          本批切片总数（缺组留痕定位）
     * @param operator          操作人标识（透传重建写回审计）
     * @return 逐组 prepared 结果（与分组序一致，供事务内 apply 与提交后收口配对）
     */
    private List<PreparedGroup> prepareGroupedByKbId(Map<Long, List<Long>> idsByKbId,
                                                     Map<Long, Long> documentIdByChunk,
                                                     int totalIds, String operator) {
        List<PreparedGroup> prepared = new ArrayList<>(idsByKbId.size());
        int groupedIds = 0;
        for (Map.Entry<Long, List<Long>> entry : idsByKbId.entrySet()) {
            Long anchorDocumentId = resolveAnchorDocumentId(entry.getValue(), documentIdByChunk);
            DocumentGraphConvergencePlan plan = documentGraphConvergenceApi.prepare(
                    entry.getKey(), anchorDocumentId, entry.getValue(), operator);
            prepared.add(new PreparedGroup(entry.getKey(), plan));
            groupedIds += entry.getValue().size();
        }
        if (groupedIds < totalIds) {
            log.warn("部分切片行删行前已不存在（并发重复删除），其图谱贡献由首次删除同事务收敛兜底, "
                    + "本批切片数={}, 已分组切片数={}", totalIds, groupedIds);
        }
        return prepared;
    }

    /**
     * 取该组切片的审计锚点文档ID：组内投影值的最小值（升序稳定、跨批次重入同值）。
     *
     * @param groupChunkIds     该组切片ID（升序）
     * @param documentIdByChunk 「切片 → 文档ID」投影（可缺行——并发已消失）
     * @return 最小文档ID；投影全缺失返回 {@code null}
     */
    private Long resolveAnchorDocumentId(List<Long> groupChunkIds, Map<Long, Long> documentIdByChunk) {
        if (CollectionUtils.isEmpty(documentIdByChunk)) {
            return null;
        }
        return groupChunkIds.stream()
                .map(documentIdByChunk::get)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    /**
     * 【事务提交后】逐组向量收口（事务外远程；单组异常仅 ERROR 留痕不阻断后续组，
     * MUST NOT 反噬已提交的删除事实）。
     *
     * @param preparedGroups 事务前逐组 prepared 结果
     * @param applyResults   事务内逐组 apply 结果（与分组序一致；事务未产出时按零操作）
     */
    private void reconcileQuietly(List<PreparedGroup> preparedGroups,
                                  List<DocumentGraphConvergenceResult> applyResults) {
        if (CollectionUtils.isEmpty(preparedGroups) || CollectionUtils.isEmpty(applyResults)) {
            return;
        }
        for (int index = 0; index < preparedGroups.size(); index++) {
            PreparedGroup group = preparedGroups.get(index);
            try {
                documentGraphConvergenceApi.reconcilePendingVectorContent(
                        group.convergencePlan(), applyResults.get(index));
            } catch (RuntimeException e) {
                log.error("图谱重建向量收口异常（删除已提交，残留偏差由条目下次整体重建收敛）, kbId={}",
                        group.kbId(), e);
            }
        }
    }

    /**
     * 由「切片 → 来源」投影推导本批是否需要图谱账本收敛：含任何「解析产生」的切片即需收敛。
     * <p>投影缺失的行（并发下已物理消失）不构成收敛依据——其图谱贡献在该行首次删除的同事务内
     * 已被收敛剔除，无需重复清退（与 {@link #groupIdsByKbId(List)} 的缺行口径一致）。</p>
     *
     * @param sourceByChunk 删行前预取的「切片主键 → 来源标识」投影（可为空映射）
     * @return 本批含解析来源返回 {@code true}；全为人工来源或投影为空返回 {@code false}
     */
    private boolean containsParsedSource(Map<Long, ChunkSource> sourceByChunk) {
        if (CollectionUtils.isEmpty(sourceByChunk)) {
            return false;
        }
        return sourceByChunk.containsValue(ChunkSource.PARSED);
    }

    /**
     * 取本批切片所属的去重文档集合（保持文档ID升序，令多文档批次的行锁顺序稳定）。
     *
     * @param documentIdByChunk 预取的「切片 → 文档ID」投影（可为空映射）
     * @return 去重升序后的文档ID集合；无命中返回空集合
     */
    private Collection<Long> distinctDocumentIds(Map<Long, Long> documentIdByChunk) {
        if (CollectionUtils.isEmpty(documentIdByChunk)) {
            return List.of();
        }
        return documentIdByChunk.values().stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 删行之前预取本批切片的「知识库 → 切片ID集合」分组（收敛按库收窄扫描面的前置取数）。
     * <p>与 {@link #distinctDocumentIds(Map)} 同一时机：切片行一旦物理消失，所属知识库即无从
     * 反查。已消失行（并发重复删除等）不产分组——其账本贡献在该行首次删除的同事务内已被
     * 收敛剔除，无需重复清退。</p>
     *
     * @param chunkIds 本批切片ID集合（非空）
     * @return 知识库ID → 该库切片ID列表（保持切片ID升序）；无存活行返回空映射
     */
    private Map<Long, List<Long>> groupIdsByKbId(List<Long> chunkIds) {
        Map<Long, Long> kbIdByChunk = chunkRepository.findKbIdsByChunkIds(chunkIds);
        if (CollectionUtils.isEmpty(kbIdByChunk)) {
            return Map.of();
        }
        Map<Long, List<Long>> idsByKbId = new LinkedHashMap<>();
        kbIdByChunk.forEach((chunkId, kbId) ->
                idsByKbId.computeIfAbsent(kbId, key -> new ArrayList<>()).add(chunkId));
        return idsByKbId;
    }

    /**
     * 按文档分组重算并回写分块计数（切片行已物理消失，COUNT 即存活切片数，无需减计）。
     * <p>文档行不可见（并发终删收口）时跳过该篇回写，MUST NOT 因缺失行而中断本批清退。
     * 单批受影响文档数量级极小（人工删除通常同一文档），逐篇 COUNT + UPDATE 即可，无需批量化。</p>
     *
     * @param documentIds 受影响文档ID集合（去重、稳定序）
     * @param operator    操作人标识，可为空（仅用于留痕口径）
     */
    private void syncChunkCount(Collection<Long> documentIds, String operator) {
        if (CollectionUtils.isEmpty(documentIds)) {
            return;
        }
        for (Long documentId : documentIds) {
            if (ObjectUtils.isEmpty(documentId)) {
                continue;
            }
            long total = chunkRepository.countByDocumentId(documentId);
            int chunkCount = (int) Math.min(total, Integer.MAX_VALUE);
            boolean updated = documentRepository.findById(documentId)
                    .map(document -> documentRepository.update(document.withChunkCount(chunkCount)))
                    .isPresent();
            if (!updated) {
                log.info("文档行已不存在，跳过分块计数回写（并发终删或收口已完成）, documentId={}, operator={}",
                        documentId, operator);
            }
        }
    }

    /**
     * 单库分组的 prepare 结果（句柄与库身份配对，供事务内 apply 与提交后收口按序消费）。
     *
     * @param kbId             知识库ID（日志定位）
     * @param convergencePlan  不透明收敛计划句柄
     */
    private record PreparedGroup(Long kbId, DocumentGraphConvergencePlan convergencePlan) {
    }
}
