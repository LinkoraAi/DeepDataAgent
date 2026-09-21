package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.knowledgebase.domain.model.EntityType;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * 图谱贡献重建执行上下文（{@link GraphContributionRebuildService} 两段式 API 的库级输入）。
 * <p>字段集合按「删除期可构造」口径收敛：删除链只持有被删文档身份、库级配置
 * （实体类型清单 / 输出模式 / 合并参数，与摄入端同源）与模型引用（摘要、向量化各一），
 * 不依赖任何摄入期一次性状态。{@link GraphMergeParams} 直接复用图合并参数——
 * 重建的并发闸门（{@code concurrencyGate()}）、摘要条数/token 阈值
 * （{@code forceLlmSummaryOnMerge()} / {@code summaryMaxTokens()}）、来源路径上限与占位词、
 * 展示列截断与向量内容 token 上限 MUST 与合并路径同一套配置来源，保证两条路径产出同形
 * （spec graph-contribution-rebuild / R2 与 design D8 的「同口径」要求在参数侧的落点）。</p>
 *
 * @param kbId                  所属知识库ID（必填，扫描面与缓存隔离维度）
 * @param deletedDocumentId     本批被删分块所属文档ID（必填——审计锚点与摘要上下文定位；
 *                              删除链批次恒来自单一文档，重解析清退旧代同理）
 * @param entityTypes           知识库可配置实体类型清单（null 归一空清单；重放类型过滤与抽取同口径）
 * @param jsonMode              是否 JSON 输出模式（系统配置，决定重放解析分支；与抽取期取同一配置值）
 * @param summaryModelProfileId 摘要 LLM 模型 profileId（重建路径描述摘要的远程调用引用，必填）
 * @param embeddingModelProfileId 向量化模型 profileId（重建路径向量化的远程调用引用，必填）
 * @param language              知识库语言全名（摘要提示词渲染用；空白按图合并上下文同款兜底为英文口径）
 * @param params                图合并参数（null 回退默认值；重建与合并共用同一参数族）
 * @param operator              操作人标识（写回审计字段，可空）
 * @param cancellationCheck     取消检查器（null 视为永不取消；删除链当前无取消语义，预留对齐合并端形态）
 * @author DeepDataAgent
 */
public record GraphRebuildContext(
        Long kbId,
        Long deletedDocumentId,
        List<EntityType> entityTypes,
        boolean jsonMode,
        String summaryModelProfileId,
        String embeddingModelProfileId,
        String language,
        GraphMergeParams params,
        String operator,
        BooleanSupplier cancellationCheck) {

    /**
     * 紧凑构造器：不变量校验与兜底（口径对齐 {@link GraphMergeContext}）。
     */
    public GraphRebuildContext {
        if (ObjectUtils.isEmpty(kbId)) {
            throw new IllegalArgumentException("重建上下文 kbId 不能为空");
        }
        if (ObjectUtils.isEmpty(deletedDocumentId)) {
            throw new IllegalArgumentException("重建上下文 deletedDocumentId 不能为空");
        }
        if (StringUtils.isBlank(summaryModelProfileId)) {
            throw new IllegalArgumentException("重建上下文 summaryModelProfileId 不能为空");
        }
        if (StringUtils.isBlank(embeddingModelProfileId)) {
            throw new IllegalArgumentException("重建上下文 embeddingModelProfileId 不能为空");
        }
        entityTypes = ObjectUtils.isEmpty(entityTypes)
                ? List.of()
                : entityTypes.stream().filter(Objects::nonNull).toList();
        params = ObjectUtils.isEmpty(params) ? GraphMergeParams.defaults() : params;
    }

    /**
     * 是否已被请求取消（检查器抛出的异常原样上抛，由调用方统一兜底）。
     *
     * @return 已取消返回 true
     */
    public boolean isCancelled() {
        return ObjectUtils.isNotEmpty(cancellationCheck) && cancellationCheck.getAsBoolean();
    }
}
