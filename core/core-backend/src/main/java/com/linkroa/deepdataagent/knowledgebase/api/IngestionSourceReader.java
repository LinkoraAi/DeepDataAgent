package com.linkroa.deepdataagent.knowledgebase.api;

import com.linkroa.deepdataagent.knowledgebase.application.contract.ChunkIdentity;
import com.linkroa.deepdataagent.knowledgebase.application.contract.IngestionDocumentContext;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentStatus;

import java.util.List;

/**
 * 摄入源读取契约（跨 BC 服务边界，未来 Feign 落点）。
 * <p>提供方为 knowledgebase BC（文档与库级配置归本 BC 所有），消费方为 rag BC 的摄入管线。
 * 与 {@link ChunkBatchWriter}（写侧回传）配套，本契约承担摄入的读侧输入：
 * 处理前读取上下文，处理后回查状态与切片身份。</p>
 * <p>全部方法只读、不加锁；配置 JSON 在实现侧做防御式解析，库中数据损坏时抛业务异常，
 * 由消费方按失败语义处理（判定 FAILED 并回写原因）。</p>
 */
public interface IngestionSourceReader {

    /**
     * 读取单个文档的摄入上下文（文档信息 + 库级配置，一次性组装）。
     * <p>删除流程早闸门：
     * 所属知识库处于 DELETING / DELETE_FAILED 状态时立即以业务冲突异常失败（消费方经既有失败回写置
     * FAILED），删除流程中的库内排队文档快速出清，不再进入解析 / 分块增强 / 向量化等后续远程调用阶段。</p>
     *
     * @param documentId 目标文档主键，必填
     * @return 摄入文档上下文，字段口径见 {@link IngestionDocumentContext}
     * @throws com.linkroa.deepdataagent.shared.exception.DeepDataAgentException 文档ID为空（400）
     * @throws com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException 文档 / 知识库不存在（404，
     *         口径与 {@link ChunkBatchWriter#replaceForDocument} 一致）；或配置 JSON 数据损坏
     * @throws com.linkroa.deepdataagent.shared.exception.ResourceConflictException 知识库处于删除流程
     *         （409，DELETING / DELETE_FAILED 早闸门，消息「知识库清退中，拒绝摄入」）
     */
    IngestionDocumentContext readContext(Long documentId);

    /**
     * 查询文档当前处理状态（摄入末段复核状态是否被外部变更，如用户重新解析 / 删除）。
     *
     * @param documentId 目标文档主键，可为空
     * @return 文档状态；文档不存在、已逻辑删除或入参为空时返回 {@code null}
     */
    DocumentStatus statusOf(Long documentId);

    /**
     * 列举某文档全部切片的身份投影（sequence → 真实主键），供摄入产物溯源回填。
     * <p>仅回读序号与主键两列，不携带切片正文；按 sequence 升序返回。</p>
     *
     * @param documentId 目标文档主键，可为空
     * @return 切片身份列表（sequence 升序）；入参为空或无切片时返回空列表
     */
    List<ChunkIdentity> listChunkIdentitiesByDocument(Long documentId);
}
