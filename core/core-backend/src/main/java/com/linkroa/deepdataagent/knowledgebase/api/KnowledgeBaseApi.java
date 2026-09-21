package com.linkroa.deepdataagent.knowledgebase.api;

import com.linkroa.deepdataagent.knowledgebase.application.contract.ChunkScoreDTO;
import com.linkroa.deepdataagent.knowledgebase.application.contract.KnowledgeBaseReferenceDTO;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Chunk;
import com.linkroa.deepdataagent.knowledgebase.domain.model.EntityType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.RetrievalStrategyConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.LifecycleStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 知识库服务契约（跨 BC 服务边界，未来 Feign 落点）。
 * <p>与领域出站端口分离：本接口是 knowledgebase BC 对外暴露的跨 BC 能力面，
 * 消费方（rag / agent / runtime）依赖本接口而非进程内领域仓储。当前由
 * {@code DefaultKnowledgeBaseApi} 进程内实现，未来接入 Feign 时仅需在本接口
 * 追加 {@code @FeignClient} 注解并提供远程实现，消费方无需改动。</p>
 *
 * <p>能力分为三类：读侧引用解析（{@code resolveById} / {@code isActive}）、
 * 读侧检索召回（{@code searchByVector} / {@code searchByKeywords}）、
 * 写侧状态驱动（{@code executeDelete} / {@code markFailed}）。
 * 写侧方法只改动知识库自有状态，不反向调用消费方，从而保持 RAG → KB 的单向依赖。</p>
 */
public interface KnowledgeBaseApi {

    /**
     * 按主键解析知识库引用。
     *
     * @param kbId 知识库主键，可为空
     * @return 知识库引用契约；入参为空或知识库不存在时返回 {@link Optional#empty()}
     */
    Optional<KnowledgeBaseReferenceDTO> resolveById(Long kbId);

    /**
     * 判断知识库是否存在且处于 ACTIVE 状态。
     *
     * @param kbId 知识库主键，可为空
     * @return true 表示知识库存在且可正常检索；入参为空、不存在或已处于删除流程时返回 false
     */
    boolean isActive(Long kbId);

    /**
     * 按知识库ID读取检索策略配置（读侧只读，供检索编排消费）。
     * <p>策略以 JSON 字符串落库于 {@code knowledge_base.retrieval_strategy}，本方法负责
     * JSON → 领域值对象 {@link RetrievalStrategyConfig} 的解析（策略类型已归一化为枚举名）。
     * 只读不加锁，不区分知识库生命周期状态（与 {@link #resolveById(Long)} 口径一致）。</p>
     *
     * @param kbId 知识库主键，可为空
     * @return 检索策略配置领域模型；入参为空、知识库不存在或未配置策略（JSON 为空白）时返回 {@code null}
     * @throws com.linkroa.deepdataagent.shared.exception.DeepDataAgentException 库中策略 JSON 非法（数据损坏，正常写入口已校验不应触发）
     */
    RetrievalStrategyConfig findRetrievalStrategyByKbId(Long kbId);

    /**
     * 按知识库ID读取知识库语言配置（读侧只读投影，供检索侧与摄入端语言同源消费，
     * ；真相源口径见）。
     * <p>语言以字符串落库于 {@code knowledge_base.language} 列——知识库语言的<strong>唯一真相源</strong>
     * （11 语言全名值域，见 {@code KbLanguage}；{@code rag_engine_config} JSONB 的 language 键已弃用，
     * 本方法不再解析该键）。本方法负责列值 → 语言全名的读取归一（大小写不敏感归一为枚举规范全名，
     * 存量值如 {@code ENGLISH} 归入 English；历史裸语言码原样透传的宽容口径不变）。
     * 只读不加锁，不区分知识库生命周期状态（与 {@link #findRetrievalStrategyByKbId(Long)} 口径一致）。</p>
     *
     * @param kbId 知识库主键，可为空
     * @return 归一后的语言全名；入参为空、知识库不存在或列值空白（缺省视同 Chinese 由调用方回落）
     *         时返回 {@code null}
     */
    String findLanguageByKbId(Long kbId);

    /**
     * 按生命周期状态列举知识库主键（读侧只读投影，保留能力）。
     * <p>启动恢复链路已改为按本实例在飞注册表键读取残留，经状态一致性校验收敛为 {@code DELETE_FAILED}；
     * 本方法当前无生产调用点，也不再承担「重启后自动重触发续跑」职责。
     * 保留语义：排除逻辑删除行，结果按主键升序，扫描顺序稳定；只读不加锁。</p>
     *
     * @param lifecycle 目标生命周期状态，必填；为空时返回空列表
     * @return 命中的知识库主键列表（升序）；无命中或入参为空返回空列表
     */
    List<Long> findIdsByLifecycle(LifecycleStatus lifecycle);

    /**
     * 按知识库ID读取知识库级 LLM 模型 profileId（读侧只读投影）。
     * <p><strong>语义范围</strong>：{@code multi_model_config} 虽以「多模态」命名，但该 profileId
     * 是该知识库<strong>统一的通用大模型引用</strong>——媒体描述、附图转译、实体抽取、关系与关键词
     * 提取、摘要生成、检索侧问题改写与作答共用同一个模型，不存在第二个 LLM 配置项。方法名沿用
     * {@code Media} 前缀以与列名 {@code multi_model_config} 对齐，不代表仅服务视觉场景。</p>
     * <p>profileId 以字符串落库于 {@code knowledge_base.multi_model_config} JSONB 的
     * {@code modelProfileId} 键；「该库 LLM 能力可用」判定口径＝该值已配置（非空白），
     * 不查询模型注册表、不校验模型是否真支持多模态。</p>
     * <p>只读不加锁，不区分知识库生命周期状态（与 {@link #findLanguageByKbId(Long)} 口径一致）。</p>
     *
     * @param kbId 知识库主键，可为空
     * @return 知识库级 LLM 模型 profileId；入参为空、知识库不存在、未配置该列或键缺失/空白时返回 {@code null}
     * @throws com.linkroa.deepdataagent.shared.exception.DeepDataAgentException 库中多模态配置 JSON 非法（数据损坏，正常写入口已校验不应触发）
     */
    String findMediaModelProfileIdByKbId(Long kbId);

    /**
     * 按知识库ID读取嵌入（EMBEDDING）模型 profileId（读侧只读投影，供检索侧 query 向量化消费，
     * 与摄入侧切片向量化<strong>同源</strong>）。
     * <p>profileId 以字符串落库于 {@code knowledge_base.embedding_config} JSONB 的
     * {@code modelProfileId} 键（解析口径与 {@code DefaultIngestionSourceReader#parseEmbeddingConfig}
     * 一致）。本方法是模型选型的唯一真相源：写入与检索必须取到同一个 profileId，否则 query 向量与
     * 切片向量分属不同语义空间，向量召回会静默产出噪声而无任何错误信号。</p>
     * <p>只读不加锁，不区分知识库生命周期状态（与 {@link #findMediaModelProfileIdByKbId(Long)} 口径一致）。</p>
     *
     * @param kbId 知识库主键，可为空
     * @return 嵌入模型 profileId（已 trim）；入参为空、知识库不存在、未配置嵌入配置或该键缺失/空白时
     *         返回 {@code null}（由消费方按「向量检索不可用」处理，不回退任何全局默认值）
     * @throws com.linkroa.deepdataagent.shared.exception.DeepDataAgentException 库中嵌入配置 JSON 非法（数据损坏，正常写入口已校验不应触发）
     */
    String findEmbeddingModelProfileIdByKbId(Long kbId);

    /**
     * 按知识库ID读取实体类型自定义清单（读侧只读投影，供删除期图谱重建与抽取端同源消费）。
     * <p>清单以 JSON 落库于 {@code knowledge_base.entity_type_config} 的 {@code entityTypes} 键
     * （形态 {@code {"entityTypes":[{"entityType":"名称"}]}}，兼容纯字符串数组）；解析口径与
     * {@code DefaultIngestionSourceReader#readContext} 完全一致——非关键路径配置，任何解析失败
     * 均按空清单兜底并告警，MUST NOT 抛出（重建与抽取在空清单下仅按内置类型放行）。</p>
     * <p>只读不加锁，不区分知识库生命周期状态（与 {@link #findLanguageByKbId(Long)} 口径一致；
     * 删除链触发时库常处于 DELETING，MUST NOT 因此取不到配置）。</p>
     *
     * @param kbId 知识库主键，可为空
     * @return 实体类型清单；入参为空、知识库不存在或未配置时返回空列表（非 null）
     */
    List<EntityType> findEntityTypesByKbId(Long kbId);

    /**
     * 按知识库ID读取 RAG 引擎配置 JSON 原文（读侧只读无损投影，供消费方自行解析所需子段）。
     * <p>列值原样透传（trim 后返回），提供方不做任何子段解读：图合并段（{@code graphMerge /
     * graph_merge}）等消费方私有的配置语义由其归属 BC（rag）自行解析，避免 knowledgebase BC
     * 反向理解消费方的参数结构。只读不加锁，口径与 {@link #findLanguageByKbId(Long)} 一致。</p>
     *
     * @param kbId 知识库主键，可为空
     * @return 配置 JSON 原文；入参为空、知识库不存在或该列空白时返回 {@code null}
     */
    String findRagEngineConfigJsonByKbId(Long kbId);

    /**
     * 按知识库ID与切片ID集合批量回取切片（读侧只读，供检索侧 {@code chunksOf} 消费）。
     * <p>带 {@code kbId} 等值过滤保证单库隔离：仅回取属于该知识库的切片，跨库 ID 不命中；
     * 物理删体系下行消失即「已删除」。返回切片为完整领域模型（含正文与来源文件名）。</p>
     * <p><b>可见性约束</b>：仅回取所属文档处于「已处理」（{@code PROCESSED}）状态的切片——
     * 摄入中、已失败与删除链状态的文档其切片按未命中处理，MUST NOT 进入检索结果；
     * 这是检索链路按主键回取切片正文的单点收口（图谱通道账本、精排、上下文构建、答案引用共用）。</p>
     *
     * @param kbId     知识库主键，可为空
     * @param chunkIds 切片ID集合，可为空
     * @return 命中切片列表（按 id 升序）；入参为空或无命中返回空列表
     */
    List<Chunk> findChunksByKbIdAndChunkIds(Long kbId, Collection<Long> chunkIds);

    /**
     * VECTOR 通道只读检索：按余弦距离阈值命中切片并返回相关度分。
     * <p>查询向量以字面量字符串传入（本 BC 无 float[] → 字面量转换器，由消费方
     * rag BC 在跨边界前自行转换）；阈值约定为余弦距离阈值（= {@code 1 - similarThreshold}）。
     * 只读不加锁，不区分知识库生命周期状态（与 {@link #resolveById(Long)} 口径一致）。</p>
     * <p><b>可见性约束</b>：仅命中所属文档处于「已处理」（{@code PROCESSED}）状态的切片，
     * 摄入中、失败与删除链状态的文档其切片 MUST NOT 进入召回结果（不占用 {@code limit} 名额）。</p>
     *
     * @param kbId       知识库主键，可为空（空返回空列表）
     * @param threshold  余弦距离阈值
     * @param vecLiteral 查询向量字面量，形如 "[0.1,0.2,...]"，可为空（空返回空列表）
     * @param limit      结果数量上限，非正数返回空列表
     * @return 命中切片契约列表（按相似度降序）；无命中或入参非法返回空列表
     */
    List<ChunkScoreDTO> searchByVector(Long kbId, double threshold, String vecLiteral, int limit);

    /**
     * BM25 通道只读检索：按关键词做全文检索并返回相关度分。
     * <p>关键词按 websearch_to_tsquery 语法解析，命中返回 ts_rank_cd 相关度分。
     * 只读不加锁，不区分知识库生命周期状态（与 {@link #resolveById(Long)} 口径一致）。</p>
     * <p><b>可见性约束</b>：仅命中所属文档处于「已处理」（{@code PROCESSED}）状态的切片，
     * 摄入中、失败与删除链状态的文档其切片 MUST NOT 进入召回结果（不占用 {@code limit} 名额）。</p>
     *
     * @param kbId  知识库主键，可为空（空返回空列表）
     * @param query 检索关键词文本，可为空（空返回空列表）
     * @param limit 结果数量上限，非正数返回空列表
     * @return 命中切片契约列表（按相关度降序）；无命中或入参非法返回空列表
     */
    List<ChunkScoreDTO> searchByKeywords(Long kbId, String query, int limit);

    /**
     * 执行知识库删除收口：单条条件物理 DELETE。
     * <p>由清退编排（rag BC）在全部自有数据清退完成后回调：以
     * {@code DELETE FROM knowledge_base WHERE id = ? AND lifecycle_status IN ('DELETING','DELETE_FAILED')}
     * 单语句物理删除该库行——行消失即收口完成（DELETED 常量已移除，「已删除」的唯一表达是
     * 行缺失 404），同名（uk_kb_name）随之释放、删库后同名立即可用。
     * 0 行命中（行已不存在＝已收口、状态不符）按幂等处理返回 {@code false}，MUST NOT 抛出。</p>
     *
     * @param kbId 知识库主键，必填；为空抛参数错误异常
     * @return {@code true} 表示本次调用命中并完成收口；{@code false} 表示未命中（已收口幂等空转）
     * @throws com.linkroa.deepdataagent.shared.exception.DeepDataAgentException 知识库ID为空（400）
     */
    boolean executeDelete(Long kbId);

    /**
     * 清退失败留痕：单语句 CAS {@code DELETING → DELETE_FAILED} 并写入 {@code error_message}
     * （零重试语义的失败落点）。
     * <p>由清退编排（rag BC）在某关键步骤重试耗尽 / 失败时回调，只推进知识库自有生命周期状态并留痕
     * （形如 {@code [KB-CLEANUP] step=…}，超长由提供方截断）；非 DELETING 源态（已收口行缺失、已是
     * DELETE_FAILED 或 ACTIVE）按幂等空转处理，MUST NOT 覆盖并发链已写入的状态、不抛异常。
     * 对用户呈现「知识库不可用，请重新执行删除」，由用户重删推回 DELETING 续跑。</p>
     *
     * @param kbId   知识库主键，必填；为空抛参数错误异常
     * @param reason 失败步骤与原因摘要（可为空，空则仅置态不落具体文案）
     * @throws com.linkroa.deepdataagent.shared.exception.DeepDataAgentException 知识库ID为空（400）
     */
    void markFailed(Long kbId, String reason);
}
