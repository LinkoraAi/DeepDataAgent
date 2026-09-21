package com.linkroa.deepdataagent.knowledgebase.domain.repository;

import com.linkroa.deepdataagent.knowledgebase.domain.model.Document;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentStatus;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 文档仓储接口。
 */
public interface DocumentRepository {

    Document save(Document document);

    Document update(Document document);

    Optional<Document> findById(Long id);

    Optional<Document> findByIdForUpdate(Long id);

    List<Document> findByKbId(Long kbId, String fileName, DocumentStatus status, int page, int size);

    long countByKbId(Long kbId, String fileName, DocumentStatus status);

    void deleteById(Long id);

    void deleteByKbId(Long kbId);

    /**
     * 查询知识库内同名或同内容哈希的文档（判重预检，只读）。
     * <p>判重两轴各有唯一载体：文件名轴取 {@code document.file_name} 列，
     * 内容哈希轴取 {@code document.file_content_hash} 列（服务端对上传的原始文件字节计算 SHA-256，
     * 输出小写十六进制、恒 64 字符；该值唯一用途是判重轴，MUST NOT 用作文件完整性校验、
     * 秒传或解析产物指纹）。两轴入参为空白的轴不参与匹配。</p>
     *
     * @param kbId        知识库ID
     * @param fileName    文件名轴取值，可为空（不参与匹配）
     * @param contentHash 内容哈希轴取值，可为空（不参与匹配）
     * @return 命中文档列表（文档ID升序，构成覆盖处置的固定加锁顺序）；无命中返回空列表
     */
    List<Document> findDuplicates(Long kbId, String fileName, String contentHash);

    /**
     * 统计知识库内文档数。
     */
    long countByKbIdOnly(Long kbId);

    /**
     * 分批取知识库内存活文档的 ID（id 升序，清退调度用）。
     * <p>物理删体系下已收口文档行不存在，故每轮从头取 LIMIT 即可：上一批删除后自然轮到下一批，
     * 中断后重跑幂等、无需游标。</p>
     *
     * @param kbId  知识库ID
     * @param limit 单批上限
     * @return 文档 ID 列表（升序）；无命中返回空列表
     */
    List<Long> findIdsByKbId(Long kbId, int limit);

    /**
     * 按 ID 集合批量物理删除文档（标准 deleteByIds，无 @TableLogic，行消失即「已删除」）。
     *
     * @param ids 文档 ID 集合（空集合直接返回 0）
     * @return 实际删除行数
     */
    int deleteByIds(List<Long> ids);

    /**
     * 状态原子流转（CAS）：仅当文档当前状态命中 fromStatuses 之一时，
     * 才将状态更新为 toStatus 并回写失败原因（errorMessage 为 null 表示清除）。
     * <p>用于摄入领取 / 失败回写等并发安全场景：以条件 UPDATE 单条 SQL 完成判定与写入，
     * 不读取聚合根再做修改-写回，避免竞态覆盖。</p>
     *
     * @param id           文档ID
     * @param fromStatuses 允许的当前状态集合（CAS 前置条件，空集合直接返回 false）
     * @param toStatus     目标状态
     * @param errorMessage 处理失败原因（写入 error_message；传 null 表示清除）
     * @return true 表示流转成功（命中并更新 1 行）；false 表示状态未命中或记录不存在
     */
    boolean transitStatus(Long id, Set<DocumentStatus> fromStatuses, DocumentStatus toStatus, String errorMessage);

    /**
     * 删除链收口：单条条件物理 DELETE。
     * <p>{@code DELETE FROM document WHERE id = ? AND status IN ('DELETING','DELETE_FAILED')}——
     * 无 @TableLogic，条件 delete 即物理 DELETE，行消失即「已删除」唯一表达；
     * 0 行命中（行已不存在＝已收口、或状态不符）为幂等空转，MUST NOT 抛出。</p>
     *
     * @param documentId 文档ID；为空直接返回 false（零 DB 交互）
     * @return true 表示本次调用命中并完成收口；false 表示零行命中（幂等空转）
     */
    boolean executeDelete(Long documentId);

    /**
     * 清退失败留痕（零重试语义的失败落点）。
     * <p>单语句 CAS：DELETING → DELETE_FAILED 并同语句写入 {@code error_message}
     * 留痕（形如 {@code [DELETE-FAILED] step=…}）；非源态零行命中（行已收口、已是
     * DELETE_FAILED）按幂等空转处理，MUST NOT 覆盖并发链已写入的状态。</p>
     *
     * @param documentId 文档ID；为空直接返回 false（零 DB 交互）
     * @param reason     失败步骤与原因摘要（写入 error_message，调用方负责截断），可为空
     * @return true 表示 CAS 命中并完成留痕；false 表示未命中（幂等空转）
     */
    boolean markFailed(Long documentId, String reason);

    /**
     * 非终态文档批量状态流转（保留能力；启动恢复链路已改为按本实例在飞注册表键逐条一致性校验收敛，
     * 本方法当前无生产调用点）：
     * 以单条条件 UPDATE 把「状态命中 fromStatuses 之一」的全部存活文档行置为 toStatus
     * 并回写失败原因。
     * <p>语义为「一次性网式收敛」：无游标、无批量上限（崩溃残留量级远小于单语句承载能力），
     * 天然幂等（二次执行影响 0 行）。物理删体系下行消失即已删除、天然不参与；
     * 进入删除链（DELETING/DELETE_FAILED）的文档因状态不在 fromStatuses 集合内而不参与收敛。</p>
     *
     * @param fromStatuses 允许的非终态状态集合（空集合直接返回 0）
     * @param toStatus     目标状态（清理场景为 FAILED）
     * @param errorMessage 失败原因（写入 error_message），空白直接返回 0
     * @return 实际流转（命中并更新）的文档行数
     */
    int failNonTerminal(Set<DocumentStatus> fromStatuses, DocumentStatus toStatus, String errorMessage);
}
