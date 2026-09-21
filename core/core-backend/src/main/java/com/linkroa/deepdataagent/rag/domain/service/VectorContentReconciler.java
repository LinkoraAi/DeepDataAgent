package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.rag.domain.model.EntityInfoVector;
import com.linkroa.deepdataagent.rag.domain.model.EntityNode;
import com.linkroa.deepdataagent.rag.domain.model.RelationEdge;
import com.linkroa.deepdataagent.rag.domain.model.RelationInfoVector;
import com.linkroa.deepdataagent.rag.domain.port.EmbeddingClient;
import com.linkroa.deepdataagent.rag.domain.repository.EntityInfoVectorRepository;
import com.linkroa.deepdataagent.rag.domain.repository.EntityNodeGraphRepository;
import com.linkroa.deepdataagent.rag.domain.repository.RelationEdgeGraphRepository;
import com.linkroa.deepdataagent.rag.domain.repository.RelationInfoVectorRepository;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 向量内容收口原语（rag 领域层，维护不变量「向量行内容 = 已提交描述的函数」）。
 *
 * <p><b>不变量</b>：实体/关系的向量行内容必须由图行<b>已提交</b>的描述（关系侧另含关键词）派生。
 * 合并写回时向量内容与向量是在事务外基于过期快照算好的（事务规范禁止远程调用进入事务），
 * 并发合并下会与事务内重算并已提交的权威描述一次性错位；本原语在每次合并提交后（或任何
 * 权威描述被改写之后）主动比对并修复该错位，使错位不再无界存活。</p>
 *
 * <p><b>入口只依赖「知识库 + 条目身份」</b>（{@code kbId} + 实体名 / 无向端点对），
 * MUST NOT 依赖合并过程的内部变量（如本次算得的描述或本次写入值）：原语自行读取当前图行描述与
 * 向量行内容完成判定与修复，故后续的「文档删除后重建描述」等场景可直接复用同一入口，
 * 无需另写一套向量化与更新逻辑（design D5）。</p>
 *
 * <p><b>判定与修复</b>：读当前图行描述与向量行内容 → 以与写入路径<b>完全相同的截断口径</b>
 * （{@link TokenCounter#truncate} + 同一 {@code embeddingTokenLimit}）构造期望内容 →
 * 图行缺失 / 向量行缺失 / 期望内容为空则跳过 → 期望与当前内容一致则「无需收口」→
 * 不一致则补一次向量化并调用 {@link EntityInfoVectorRepository#updateContentIfUnchanged}
 * （或关系侧同形方法）做<b>窄更新</b>（只改内容类列、带乐观守卫，绝不整行覆盖账本，design D3）。</p>
 *
 * <p><b>非事务、无锁取舍</b>（design D4）：本原语的两次读（图行、向量行）之间不加锁、不开事务——
 * 向量化为远程调用 MUST NOT 进入事务；并发正确性完全由窄更新的乐观守卫（{@code content = 当前值}）
 * 承担：任一收口若因他人插入而让位（守卫落空），则由更晚的提交者收口，全部写入者结束后内容必等于
 * 已提交描述的函数（收敛性）。因此本原语 MUST NOT 被 {@code @Transactional} 包裹。</p>
 *
 * <p><b>失败降级</b>（design D6）：向量化或窄更新抛出的任何异常都在本原语内被捕获，转成
 * {@link Result#FAILED} 返回、只记 WARN，MUST NOT 外抛，以免影响调用方（合并）的已提交结果。</p>
 *
 * @author DeepDataAgent
 */
@Service
public class VectorContentReconciler {

    /** 日志器 */
    private static final Logger log = LoggerFactory.getLogger(VectorContentReconciler.class);

    /** 图谱实体节点仓储（读权威描述） */
    private final EntityNodeGraphRepository entityNodeRepository;

    /** 图谱关系边仓储（读权威描述与关键词） */
    private final RelationEdgeGraphRepository relationEdgeRepository;

    /** 实体向量仓储（读当前内容 + 窄更新） */
    private final EntityInfoVectorRepository entityInfoVectorRepository;

    /** 关系向量仓储（读当前内容 + 窄更新） */
    private final RelationInfoVectorRepository relationInfoVectorRepository;

    /** 向量化端口（罕见不一致路径上的远程调用，事务外执行） */
    private final EmbeddingClient embeddingClient;

    /** Token 计数器（与写入路径同一截断口径，避免超长描述被反复判为不一致而空转） */
    private final TokenCounter tokenCounter;

    /**
     * 构造收口原语。
     *
     * @param entityNodeRepository       图谱实体节点仓储
     * @param relationEdgeRepository     图谱关系边仓储
     * @param entityInfoVectorRepository 实体向量仓储
     * @param relationInfoVectorRepository 关系向量仓储
     * @param embeddingClient            向量化端口
     * @param tokenCounter               Token 计数器（与写入路径同源实现）
     */
    public VectorContentReconciler(EntityNodeGraphRepository entityNodeRepository,
                                   RelationEdgeGraphRepository relationEdgeRepository,
                                   EntityInfoVectorRepository entityInfoVectorRepository,
                                   RelationInfoVectorRepository relationInfoVectorRepository,
                                   EmbeddingClient embeddingClient,
                                   TokenCounter tokenCounter) {
        this.entityNodeRepository = entityNodeRepository;
        this.relationEdgeRepository = relationEdgeRepository;
        this.entityInfoVectorRepository = entityInfoVectorRepository;
        this.relationInfoVectorRepository = relationInfoVectorRepository;
        this.embeddingClient = embeddingClient;
        this.tokenCounter = tokenCounter;
    }

    /**
     * 收口判定与修复结果（供调用方计数与日志，design D5）。
     */
    public enum Result {

        /** 期望内容与当前内容一致：零向量化、零写入 */
        CONSISTENT,

        /** 不一致且窄更新命中：已修复为已提交描述的函数 */
        RECONCILED,

        /** 不一致但窄更新守卫落空（他人已改写）：跳过、由更晚提交者收口，不视为错误 */
        GUARD_MISSED,

        /** 前置条件不满足（图行缺失 / 向量行缺失 / 期望内容为空）：跳过 */
        SKIPPED,

        /** 向量化或窄更新异常：降级（仅 WARN + 计数），不影响调用方 */
        FAILED
    }

    /**
     * 实体侧收口：以「知识库 + 实体名」为入口，比对当前向量行内容与「名\n已提交描述」的期望值，
     * 不一致则补一次向量化并窄更新（只改内容类列）。
     *
     * @param kbId                   所属知识库ID
     * @param entityName             实体名称（条目身份）
     * @param embeddingModelProfileId 向量化模型 profileId（期望内容不一致时用于补算向量）
     * @param embeddingTokenLimit    向量内容 token 上限（须与写入路径同值，保证截断口径一致）
     * @return 收口结果（见 {@link Result}）
     */
    public Result reconcileEntity(Long kbId, String entityName, String embeddingModelProfileId,
                                  int embeddingTokenLimit) {
        try {
            EntityNode node = entityNodeRepository.findByKbIdAndName(kbId, entityName).orElse(null);
            if (ObjectUtils.isEmpty(node)) {
                return Result.SKIPPED;
            }
            EntityInfoVector vector = entityInfoVectorRepository.findByKbIdAndName(kbId, entityName).orElse(null);
            if (ObjectUtils.isEmpty(vector)) {
                return Result.SKIPPED;
            }
            String expected = tokenCounter.truncate(
                    EntityInfoVector.buildContent(entityName, node.effectiveDescription()), embeddingTokenLimit);
            if (StringUtils.isBlank(expected)) {
                return Result.SKIPPED;
            }
            String current = vector.content();
            if (StringUtils.equals(current, expected)) {
                return Result.CONSISTENT;
            }
            float[] newVector = embeddingClient.embed(embeddingModelProfileId, expected);
            int affected = entityInfoVectorRepository.updateContentIfUnchanged(kbId, entityName, expected,
                    newVector, current);
            return affected > 0 ? Result.RECONCILED : Result.GUARD_MISSED;
        } catch (RuntimeException e) {
            log.warn("实体向量内容收口失败（降级不影响合并）：kbId=[{}]，实体=[{}]，原因=[{}]",
                    kbId, entityName, e.getMessage());
            return Result.FAILED;
        }
    }

    /**
     * 关系侧收口：以「知识库 + 无向端点对」为入口，比对当前向量行内容与「关键词\t源\n目标\n已提交描述」
     * （以归一方向构造、与写入路径同构同截断）的期望值，不一致则补一次向量化并窄更新（只改内容类列）。
     *
     * @param kbId                   所属知识库ID
     * @param nameA                  无向端点对之一（内部按字典序归一）
     * @param nameB                  无向端点对之二
     * @param embeddingModelProfileId 向量化模型 profileId（期望内容不一致时用于补算向量）
     * @param embeddingTokenLimit    向量内容 token 上限（须与写入路径同值，保证截断口径一致）
     * @return 收口结果（见 {@link Result}）
     */
    public Result reconcileRelation(Long kbId, String nameA, String nameB, String embeddingModelProfileId,
                                    int embeddingTokenLimit) {
        try {
            List<RelationEdge> rows = relationEdgeRepository.findByKbIdAndUnorderedPair(kbId, nameA, nameB);
            if (ObjectUtils.isEmpty(rows)) {
                return Result.SKIPPED;
            }
            RelationInfoVector vector = relationInfoVectorRepository.findByKbIdAndUnorderedPair(kbId, nameA, nameB)
                    .orElse(null);
            if (ObjectUtils.isEmpty(vector)) {
                return Result.SKIPPED;
            }
            String source = minEndpoint(nameA, nameB);
            String target = maxEndpoint(nameA, nameB);
            RelationEdge keepRow = pickNormalizedDirectionRow(rows, source, target);
            String expected = tokenCounter.truncate(RelationInfoVector.buildContent(
                    keepRow.properties().keywords(), source, target, keepRow.properties().description()),
                    embeddingTokenLimit);
            if (StringUtils.isBlank(expected)) {
                return Result.SKIPPED;
            }
            String current = vector.content();
            if (StringUtils.equals(current, expected)) {
                return Result.CONSISTENT;
            }
            float[] newVector = embeddingClient.embed(embeddingModelProfileId, expected);
            int affected = relationInfoVectorRepository.updateContentIfUnchanged(kbId, source, target, expected,
                    newVector, current);
            return affected > 0 ? Result.RECONCILED : Result.GUARD_MISSED;
        } catch (RuntimeException e) {
            log.warn("关系向量内容收口失败（降级不影响合并）：kbId=[{}]，端点对=[{}~{}]，原因=[{}]",
                    kbId, nameA, nameB, e.getMessage());
            return Result.FAILED;
        }
    }

    /**
     * 字典序较小端点（归一方向源）。
     */
    private static String minEndpoint(String nameA, String nameB) {
        return nameA.compareTo(nameB) <= 0 ? nameA : nameB;
    }

    /**
     * 字典序较大端点（归一方向目标）。
     */
    private static String maxEndpoint(String nameA, String nameB) {
        return nameA.compareTo(nameB) <= 0 ? nameB : nameA;
    }

    /**
     * 从历史行集合中选出归一方向行（读取其已提交关键词与描述构造期望内容）；
     * 干净环境下仅存在归一方向单行，脏环境若同时有反向行则优先取归一方向行、回落取首行。
     */
    private static RelationEdge pickNormalizedDirectionRow(List<RelationEdge> rows, String source, String target) {
        return rows.stream()
                .filter(row -> StringUtils.equals(row.sourceName(), source)
                        && StringUtils.equals(row.targetName(), target))
                .findFirst()
                .orElseGet(() -> rows.get(0));
    }
}
