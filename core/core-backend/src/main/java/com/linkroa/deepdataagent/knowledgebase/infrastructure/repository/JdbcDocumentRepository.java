package com.linkroa.deepdataagent.knowledgebase.infrastructure.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Document;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentStatus;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.DocumentRepository;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.convert.KnowledgeBasePersistenceConvert;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.KbAuditFieldUtils;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.entity.DocumentEntity;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.mapper.DocumentMapper;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 文档仓储的关系型实现（MyBatis-Plus）。
 */
@Repository
public class JdbcDocumentRepository implements DocumentRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcDocumentRepository.class);

    private final DocumentMapper mapper;

    public JdbcDocumentRepository(DocumentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Document save(Document document) {
        DocumentEntity entity = KnowledgeBasePersistenceConvert.INSTANCE.toEntity(document);
        entity.setId(null);
        // 彻底物理删体系：audit 字段不再由 MetaObjectHandler 填充，插入前显式补齐（无操作人上下文回落 system）
        KbAuditFieldUtils.fillInsert(entity, null);
        mapper.insert(entity);
        return findById(entity.getId()).orElse(document);
    }

    @Override
    public Document update(Document document) {
        DocumentEntity entity = KnowledgeBasePersistenceConvert.INSTANCE.toEntity(document);
        // 更新前显式刷新 updatedAt/updatedBy（无操作人上下文回落系统缺省值）
        KbAuditFieldUtils.fillUpdate(entity, null);
        mapper.updateById(entity);
        return findById(document.id()).orElse(document);
    }

    @Override
    public Optional<Document> findById(Long id) {
        return Optional.ofNullable(KnowledgeBasePersistenceConvert.INSTANCE.toDomain(mapper.selectById(id)));
    }

    @Override
    public Optional<Document> findByIdForUpdate(Long id) {
        return Optional.ofNullable(KnowledgeBasePersistenceConvert.INSTANCE.toDomain(mapper.selectByIdForUpdate(id)));
    }

    @Override
    public List<Document> findByKbId(Long kbId, String fileName, DocumentStatus status, int page, int size) {
        return mapper.selectByKbId(kbId, fileName, statusName(status), offset(page, size), size)
                .stream()
                .map(KnowledgeBasePersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public long countByKbId(Long kbId, String fileName, DocumentStatus status) {
        return mapper.countByKbId(kbId, fileName, statusName(status));
    }

    @Override
    public void deleteById(Long id) {
        // 彻底物理删体系：无 @TableLogic，deleteById 直接生成 DELETE FROM（行消失即已删除）
        mapper.deleteById(id);
    }

    @Override
    public void deleteByKbId(Long kbId) {
        mapper.deleteByKbId(kbId);
    }

    @Override
    public List<Document> findDuplicates(Long kbId, String fileName, String contentHash) {
        // 两轴为标量入参，直接下推给 mapper 的纯 Lambda 条件构造（fileName 轴取 file_name 列、
        // contentHash 轴取 file_content_hash 列）；mapper 侧固定 orderByAsc(id)，
        // 故此处返回顺序恒为文档 ID 升序——即覆盖处置时对旧文档的固定加锁顺序
        return mapper.selectDuplicates(kbId, fileName, contentHash)
                .stream()
                .map(KnowledgeBasePersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public long countByKbIdOnly(Long kbId) {
        return mapper.countByKbIdOnly(kbId);
    }

    @Override
    public List<Long> findIdsByKbId(Long kbId, int limit) {
        return mapper.selectIdsByKbId(kbId, limit);
    }

    @Override
    public int deleteByIds(List<Long> ids) {
        return mapper.deleteByIds(ids);
    }

    @Override
    public boolean transitStatus(Long id, Set<DocumentStatus> fromStatuses, DocumentStatus toStatus, String errorMessage) {
        if (ObjectUtils.isEmpty(id) || ObjectUtils.isEmpty(fromStatuses) || ObjectUtils.isEmpty(toStatus)) {
            return false;
        }
        List<String> fromNames = fromStatuses.stream().map(Enum::name).toList();
        return mapper.transitStatus(id, fromNames, toStatus.name(), errorMessage) > 0;
    }

    @Override
    public boolean executeDelete(Long documentId) {
        // 入参非法视为未命中：收口语义下零 DB 交互，调用方按已收口幂等处理
        if (ObjectUtils.isEmpty(documentId)) {
            return false;
        }
        // 收口 = 条件物理 DELETE（无 @TableLogic，delete(wrapper) 即 DELETE FROM）：
        // 仅删除处于可收口态 {DELETING, DELETE_FAILED} 的行，行消失即「已删除」唯一表达；
        // 0 行命中＝已收口/状态不符，幂等空转；可收口态以枚举名下传，杜绝魔法值
        return mapper.delete(Wrappers.<DocumentEntity>lambdaQuery()
                .eq(DocumentEntity::getId, documentId)
                .in(DocumentEntity::getStatus, DocumentStatus.DELETING.name(), DocumentStatus.DELETE_FAILED.name())) > 0;
    }

    @Override
    public boolean markFailed(Long documentId, String reason) {
        // 复用单条条件 UPDATE CAS：DELETING → DELETE_FAILED 同语句写入留痕，
        // 非源态零行命中（幂等空转），空入参防御由 transitStatus 统一承担
        return transitStatus(documentId, Set.of(DocumentStatus.DELETING), DocumentStatus.DELETE_FAILED, reason);
    }

    @Override
    public int failNonTerminal(Set<DocumentStatus> fromStatuses, DocumentStatus toStatus, String errorMessage) {
        if (CollectionUtils.isEmpty(fromStatuses) || ObjectUtils.isEmpty(toStatus)
                || StringUtils.isBlank(errorMessage)) {
            return 0;
        }
        List<String> fromNames = fromStatuses.stream().map(Enum::name).toList();
        return mapper.failNonTerminal(fromNames, toStatus.name(), errorMessage);
    }

    private String statusName(DocumentStatus status) {
        return ObjectUtils.isEmpty(status) ? null : status.name();
    }

    private long offset(int page, int size) {
        return (long) Math.max(0, page - 1) * size;
    }
}
