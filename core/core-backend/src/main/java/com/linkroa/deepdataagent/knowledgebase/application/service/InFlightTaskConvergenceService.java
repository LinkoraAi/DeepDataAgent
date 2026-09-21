package com.linkroa.deepdataagent.knowledgebase.application.service;

import com.linkroa.deepdataagent.knowledgebase.domain.model.Document;
import com.linkroa.deepdataagent.knowledgebase.domain.model.KnowledgeBase;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentStatus;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.LifecycleStatus;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.DocumentRepository;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.KnowledgeBaseRepository;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 在飞任务收敛原语（窄组件；承载跨 BC 契约 {@code InFlightTaskConvergenceApi} 的实现内核）。
 *
 * <p><b>为什么独立成类</b>：本类被知识库入站适配器 {@code DefaultInFlightTaskConvergenceApi}
 * 依赖。适配器属防腐层，MUST NOT 依赖持有跨 BC 出站端口的应用服务（否则知库与 rag 两个
 * BC 的 Bean 装配图将形成闭环，应用无法启动）。本类<b>只依赖领域仓储</b>，是可被适配器安全
 * 依赖的「叶子能力」；构造器签名即编译期约束——想再把应用服务塞回来会直接编译失败。</p>
 *
 * <p><b>语义＝「状态一致性校验 + 条件置态」原语</b>：任何由在飞注册表驱动的状态变更 MUST
 * 先读取数据库权威状态并与之比对，仅当数据库状态与注册表凭据所隐含的在飞态一致时才置目标态；
 * MUST NOT 以「注册表中仍有该 ID」为直接依据改写数据库状态。置态写入以数据库当前状态为
 * 前置条件（条件更新），MUST NOT 出现无条件覆盖。</p>
 *
 * <p><b>三个写入均为单条条件 UPDATE</b>（仓储层提供），不包裹额外事务上下文；不一致分支
 * （数据库已是终态、已被并发推进、或数据行已不存在）MUST NOT 变更数据库状态，
 * 且 MUST NOT 产生任何数据库写操作。</p>
 */
@Service
public class InFlightTaskConvergenceService {

    /** 在飞解析状态集合（状态一致性校验的前置条件：仅这两态可被收敛为 FAILED）。 */
    private static final Set<DocumentStatus> INGESTION_IN_FLIGHT_STATUSES = Set.of(
            DocumentStatus.PENDING, DocumentStatus.PROCESSING);

    /** 在飞清退状态集合（状态一致性校验的前置条件：仅 DELETING 可被收敛为 DELETE_FAILED）。 */
    private static final Set<DocumentStatus> CLEANUP_IN_FLIGHT_STATUSES = Set.of(DocumentStatus.DELETING);

    /** 失败留痕（error_message）截断上限，防止超长异常文案溢出留痕列。 */
    private static final int ERROR_MESSAGE_MAX_LENGTH = 500;

    /** 文档仓储（文档解析 / 文档清退两类收敛的权威状态读取与条件置态）。 */
    private final DocumentRepository documentRepository;

    /** 知识库仓储（整库清退收敛的权威状态读取与条件置态）。 */
    private final KnowledgeBaseRepository knowledgeBaseRepository;

    /**
     * 构造在飞任务收敛原语组件。
     *
     * @param documentRepository      文档仓储（仅依赖领域仓储，不依赖任何应用服务与跨 BC 端口）
     * @param knowledgeBaseRepository 知识库仓储（同上）
     */
    public InFlightTaskConvergenceService(DocumentRepository documentRepository,
                                          KnowledgeBaseRepository knowledgeBaseRepository) {
        this.documentRepository = documentRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
    }

    /**
     * 一致性校验收敛文档解析：仅当文档仍处于 {@code PENDING} 或 {@code PROCESSING} 时置 {@code FAILED}。
     *
     * @param documentId   文档主键，可为空（空视为不一致，直接返回 false，零 DB 交互）
     * @param errorMessage 失败原因（写入 {@code error_message}），可为空
     * @return {@code true} 表示一致且已置 {@code FAILED}；{@code false} 表示不一致或行不存在（零写操作）
     */
    public boolean convergeIngestion(Long documentId, String errorMessage) {
        if (ObjectUtils.isEmpty(documentId)) {
            return false;
        }
        Document document = documentRepository.findById(documentId).orElse(null);
        if (ObjectUtils.isEmpty(document) || !INGESTION_IN_FLIGHT_STATUSES.contains(document.status())) {
            return false;
        }
        return documentRepository.transitStatus(documentId, INGESTION_IN_FLIGHT_STATUSES,
                DocumentStatus.FAILED, abbreviateErrorMessage(errorMessage));
    }

    /**
     * 一致性校验收敛文档清退：仅当文档仍处于 {@code DELETING} 时置 {@code DELETE_FAILED}。
     *
     * @param documentId   文档主键，可为空（空视为不一致，直接返回 false，零 DB 交互）
     * @param errorMessage 失败留痕（写入 {@code error_message}），可为空
     * @return {@code true} 表示一致且已置 {@code DELETE_FAILED}；{@code false} 表示不一致或行不存在（零写操作）
     */
    public boolean convergeDocumentCleanup(Long documentId, String errorMessage) {
        if (ObjectUtils.isEmpty(documentId)) {
            return false;
        }
        Document document = documentRepository.findById(documentId).orElse(null);
        if (ObjectUtils.isEmpty(document) || !CLEANUP_IN_FLIGHT_STATUSES.contains(document.status())) {
            return false;
        }
        return documentRepository.markFailed(documentId, abbreviateErrorMessage(errorMessage));
    }

    /**
     * 一致性校验收敛知识库清退：仅当知识库仍处于 {@code DELETING} 时置 {@code DELETE_FAILED}。
     *
     * @param kbId         知识库主键，可为空（空视为不一致，直接返回 false，零 DB 交互）
     * @param errorMessage 失败留痕（写入 {@code error_message}），可为空
     * @return {@code true} 表示一致且已置 {@code DELETE_FAILED}；{@code false} 表示不一致或行不存在（零写操作）
     */
    public boolean convergeKbCleanup(Long kbId, String errorMessage) {
        if (ObjectUtils.isEmpty(kbId)) {
            return false;
        }
        KnowledgeBase current = knowledgeBaseRepository.findById(kbId).orElse(null);
        if (ObjectUtils.isEmpty(current) || current.lifecycleStatus() != LifecycleStatus.DELETING) {
            return false;
        }
        return knowledgeBaseRepository.markFailed(kbId, abbreviateErrorMessage(errorMessage));
    }

    /**
     * 截断失败留痕文案（超长文案溢出 error_message 列的统一出口）。
     *
     * @param errorMessage 原文案，可为空
     * @return 截断后的文案；入参为空时原样返回
     */
    private String abbreviateErrorMessage(String errorMessage) {
        return StringUtils.abbreviate(errorMessage, ERROR_MESSAGE_MAX_LENGTH);
    }
}