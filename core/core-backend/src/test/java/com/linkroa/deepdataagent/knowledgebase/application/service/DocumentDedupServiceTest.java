package com.linkroa.deepdataagent.knowledgebase.application.service;

import com.linkroa.deepdataagent.knowledgebase.application.result.DedupHit;
import com.linkroa.deepdataagent.knowledgebase.domain.model.DedupPolicyConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Document;
import com.linkroa.deepdataagent.knowledgebase.domain.model.KnowledgeBase;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DedupConflictAction;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DedupMatchRule;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentStatus;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FileType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ImportType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.LifecycleStatus;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DocumentDedupService} 单元测试。
 * <p>覆盖策略解析降级链、服务端可信内容哈希、两轴标量检测与短路、
 * 命中轴直接比对与脏数据回落、重复摘要格式与删除链两态过滤。</p>
 */
@ExtendWith(MockitoExtension.class)
class DocumentDedupServiceTest {

    /** 测试用知识库ID */
    private static final Long KB_ID = 10L;

    /** 测试用文件名 */
    private static final String FILE_NAME = "手册.pdf";

    /** 测试用另一个文件名（验证多命中摘要拼接） */
    private static final String OTHER_FILE_NAME = "报表.xlsx";

    /** 测试用内容哈希（64 位小写十六进制，与 SHA-256 输出形态一致） */
    private static final String CONTENT_HASH = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

    /** 测试用另一个内容哈希（合法形态，用于构造「文件名命中但内容轴不命中」场景） */
    private static final String OTHER_HASH = "60303ae22b998861bce3b28f33eec1be758a213c86c93c076dbe9f558c11c752";

    /** 已知向量：字符串 "abc" 的 SHA-256（64 位小写十六进制） */
    private static final String SHA256_OF_ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Mock
    private DocumentRepository documentRepository;

    @InjectMocks
    private DocumentDedupService service;

    /**
     * 构造携带指定去重策略 JSON 的知识库聚合根。
     */
    private KnowledgeBase buildKbWithPolicy(String dedupPolicy) {
        OffsetDateTime now = OffsetDateTime.now();
        return KnowledgeBase.restore(KB_ID, "产品手册", "描述", "Chinese", LifecycleStatus.ACTIVE,
                null, null, dedupPolicy, null, null, null, null, now, now);
    }

    /**
     * 构造携带指定判重两轴取值的存量文档。
     *
     * @param id              文档ID
     * @param fileName        文件名（文件名判重轴，对应 file_name 列）
     * @param fileContentHash 判重内容哈希（内容判重轴，对应 file_content_hash 列），可为 null
     */
    private Document buildDuplicate(Long id, String fileName, String fileContentHash) {
        OffsetDateTime now = OffsetDateTime.now();
        return Document.restore(id, KB_ID, fileName, FileType.PDF, DocumentStatus.PROCESSED, null,
                1024L, 3, null, ImportType.UPLOAD, null, fileContentHash, null, now, now);
    }

    /**
     * 构造携带指定处理状态的存量文档（用于验证 describeHits 对 DELETING / DELETE_FAILED 项的过滤）。
     */
    private Document buildDuplicateWithStatus(Long id, String fileName, DocumentStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        return Document.restore(id, KB_ID, fileName, FileType.PDF, status, null,
                1024L, 3, null, ImportType.UPLOAD, null, null, null, now, now);
    }

    // ==================== 去重策略解析降级链 ====================

    @Test
    void should_returnNull_when_resolvePolicy_given_blankPolicy() {
        // given
        KnowledgeBase kb = buildKbWithPolicy("  ");

        // when
        DedupPolicyConfig policy = service.resolvePolicy(kb);

        // then
        assertNull(policy);
    }

    @Test
    void should_returnNull_when_resolvePolicy_given_invalidJson() {
        // given
        KnowledgeBase kb = buildKbWithPolicy("{invalid json");

        // when
        DedupPolicyConfig policy = service.resolvePolicy(kb);

        // then
        assertNull(policy);
    }

    @Test
    void should_returnNull_when_resolvePolicy_given_missingMatchRule() {
        // given
        KnowledgeBase kb = buildKbWithPolicy("{\"conflictAction\":\"REJECT\"}");

        // when
        DedupPolicyConfig policy = service.resolvePolicy(kb);

        // then
        assertNull(policy);
    }

    @Test
    void should_returnNull_when_resolvePolicy_given_unknownMatchRule() {
        // given
        KnowledgeBase kb = buildKbWithPolicy("{\"matchRule\":\"BY_SIZE\",\"conflictAction\":\"REJECT\"}");

        // when
        DedupPolicyConfig policy = service.resolvePolicy(kb);

        // then
        assertNull(policy);
    }

    @Test
    void should_returnNull_when_resolvePolicy_given_missingConflictAction() {
        // given
        KnowledgeBase kb = buildKbWithPolicy("{\"matchRule\":\"BY_NAME\"}");

        // when
        DedupPolicyConfig policy = service.resolvePolicy(kb);

        // then
        assertNull(policy);
    }

    @Test
    void should_returnNull_when_resolvePolicy_given_unknownConflictAction() {
        // given
        KnowledgeBase kb = buildKbWithPolicy("{\"matchRule\":\"BY_NAME\",\"conflictAction\":\"MERGE\"}");

        // when
        DedupPolicyConfig policy = service.resolvePolicy(kb);

        // then
        assertNull(policy);
    }

    @Test
    void should_returnDisabledPolicy_when_resolvePolicy_given_noneMatchRule() {
        // given：显式关闭检测，动作字段无意义
        KnowledgeBase kb = buildKbWithPolicy("{\"matchRule\":\"NONE\",\"conflictAction\":\"REJECT\"}");

        // when
        DedupPolicyConfig policy = service.resolvePolicy(kb);

        // then
        assertNotNull(policy);
        assertEquals(DedupMatchRule.NONE, policy.matchRule());
        assertNull(policy.conflictAction());
        assertFalse(policy.isDetectionEnabled());
    }

    @Test
    void should_returnPolicy_when_resolvePolicy_given_validPolicy() {
        // given
        KnowledgeBase kb = buildKbWithPolicy("{\"matchRule\":\"BY_NAME_OR_CONTENT\",\"conflictAction\":\"SKIP\"}");

        // when
        DedupPolicyConfig policy = service.resolvePolicy(kb);

        // then
        assertNotNull(policy);
        assertEquals(DedupMatchRule.BY_NAME_OR_CONTENT, policy.matchRule());
        assertEquals(DedupConflictAction.SKIP, policy.conflictAction());
        assertTrue(policy.isDetectionEnabled());
    }

    @Test
    void should_returnPolicy_when_resolvePolicy_given_lowercaseValues() {
        // given：枚举取值容忍大小写
        KnowledgeBase kb = buildKbWithPolicy("{\"matchRule\":\"by_name\",\"conflictAction\":\"reject\"}");

        // when
        DedupPolicyConfig policy = service.resolvePolicy(kb);

        // then
        assertNotNull(policy);
        assertEquals(DedupMatchRule.BY_NAME, policy.matchRule());
        assertEquals(DedupConflictAction.REJECT, policy.conflictAction());
    }

    // ==================== 服务端可信内容哈希 ====================

    @Test
    void should_returnKnownVector_when_sha256Hex_given_abcBytes() {
        // given
        byte[] content = "abc".getBytes(StandardCharsets.UTF_8);

        // when
        String hash = service.sha256Hex(content);

        // then：对上传的原始文件字节实算的 SHA-256，64 位小写十六进制
        assertEquals(SHA256_OF_ABC, hash);
        assertEquals(64, hash.length());
        assertEquals(hash, hash.toLowerCase());
    }

    @Test
    void should_returnDifferentHash_when_sha256Hex_given_sameNameDifferentBytes() {
        // given：同名文件的两份不同内容
        byte[] first = new byte[] {1, 2, 3};
        byte[] second = new byte[] {1, 2, 4};

        // when
        String hashOfFirst = service.sha256Hex(first);
        String hashOfSecond = service.sha256Hex(second);

        // then：内容轴不命中，同名不再被误判为同内容
        assertNotEquals(hashOfFirst, hashOfSecond);
    }

    // ==================== 两轴标量检测与短路 ====================

    @Test
    void should_queryContentAxisWithServerHashOnly_when_detect_given_sameNameDifferentContent() {
        // given：库级 BY_CONTENT，上传文件名与既有文档相同，但内容哈希取自服务端实算字节
        DedupPolicyConfig policy = new DedupPolicyConfig(DedupMatchRule.BY_CONTENT, DedupConflictAction.REJECT);
        when(documentRepository.findDuplicates(KB_ID, null, CONTENT_HASH)).thenReturn(List.of());

        // when
        List<DedupHit> hits = service.detect(KB_ID, policy, FILE_NAME, CONTENT_HASH);

        // then：文件名不参与内容轴查询，同名字节不同的文件判为不重复
        assertTrue(hits.isEmpty());
        verify(documentRepository).findDuplicates(KB_ID, null, CONTENT_HASH);
    }

    @Test
    void should_returnEmptyWithoutQuery_when_detect_given_nullPolicy() {
        // given

        // when
        List<DedupHit> hits = service.detect(KB_ID, null, FILE_NAME, CONTENT_HASH);

        // then
        assertTrue(hits.isEmpty());
        verify(documentRepository, never()).findDuplicates(any(), any(), any());
    }

    @Test
    void should_returnEmptyWithoutQuery_when_detect_given_disabledPolicy() {
        // given：显式关闭检测的策略不触发查询
        DedupPolicyConfig policy = new DedupPolicyConfig(DedupMatchRule.NONE, null);

        // when
        List<DedupHit> hits = service.detect(KB_ID, policy, FILE_NAME, CONTENT_HASH);

        // then
        assertTrue(hits.isEmpty());
        verify(documentRepository, never()).findDuplicates(any(), any(), any());
    }

    @Test
    void should_returnEmptyWithoutQuery_when_detect_given_nullQueryAxes() {
        // given：两轴均为 null
        DedupPolicyConfig policy = new DedupPolicyConfig(DedupMatchRule.BY_NAME, DedupConflictAction.REJECT);

        // when
        List<DedupHit> hits = service.detect(KB_ID, policy, null, null);

        // then
        assertTrue(hits.isEmpty());
        verify(documentRepository, never()).findDuplicates(any(), any(), any());
    }

    @Test
    void should_returnEmptyWithoutQuery_when_detect_given_allBlankQueryAxes() {
        // given：查询轴全空白时短路，不触库
        DedupPolicyConfig policy = new DedupPolicyConfig(DedupMatchRule.BY_NAME_OR_CONTENT, DedupConflictAction.REJECT);

        // when
        List<DedupHit> hits = service.detect(KB_ID, policy, "  ", " ");

        // then
        assertTrue(hits.isEmpty());
        verify(documentRepository, never()).findDuplicates(any(), any(), any());
    }

    @Test
    void should_queryNameAxisOnly_when_detect_given_byNameRule() {
        // given
        DedupPolicyConfig policy = new DedupPolicyConfig(DedupMatchRule.BY_NAME, DedupConflictAction.REJECT);
        when(documentRepository.findDuplicates(KB_ID, FILE_NAME, null)).thenReturn(List.of());

        // when
        List<DedupHit> hits = service.detect(KB_ID, policy, FILE_NAME, CONTENT_HASH);

        // then：BY_NAME 只带文件名轴，内容哈希不参与查询
        assertTrue(hits.isEmpty());
        verify(documentRepository).findDuplicates(KB_ID, FILE_NAME, null);
    }

    @Test
    void should_queryHashAxisOnlyWithTrim_when_detect_given_byContentRule() {
        // given：查询值两端空白应被裁剪
        DedupPolicyConfig policy = new DedupPolicyConfig(DedupMatchRule.BY_CONTENT, DedupConflictAction.SKIP);
        when(documentRepository.findDuplicates(KB_ID, null, CONTENT_HASH)).thenReturn(List.of());

        // when
        List<DedupHit> hits = service.detect(KB_ID, policy, "  " + FILE_NAME + "  ", " " + CONTENT_HASH + " ");

        // then：BY_CONTENT 只带内容哈希轴
        assertTrue(hits.isEmpty());
        verify(documentRepository).findDuplicates(KB_ID, null, CONTENT_HASH);
    }

    @Test
    void should_queryBothAxes_when_detect_given_byNameOrContentRule() {
        // given
        DedupPolicyConfig policy =
                new DedupPolicyConfig(DedupMatchRule.BY_NAME_OR_CONTENT, DedupConflictAction.OVERWRITE);
        when(documentRepository.findDuplicates(KB_ID, FILE_NAME, CONTENT_HASH)).thenReturn(List.of());

        // when
        List<DedupHit> hits = service.detect(KB_ID, policy, FILE_NAME, CONTENT_HASH);

        // then
        assertTrue(hits.isEmpty());
        verify(documentRepository).findDuplicates(KB_ID, FILE_NAME, CONTENT_HASH);
    }

    @Test
    void should_notQuery_when_detect_given_repoReturnsEmpty() {
        // given：仓储返回空列表时的短路行为
        DedupPolicyConfig policy = new DedupPolicyConfig(DedupMatchRule.BY_NAME, DedupConflictAction.REJECT);
        when(documentRepository.findDuplicates(anyLong(), anyString(), any())).thenReturn(List.of());

        // when
        List<DedupHit> hits = service.detect(KB_ID, policy, FILE_NAME, null);

        // then
        assertTrue(hits.isEmpty());
        verify(documentRepository).findDuplicates(KB_ID, FILE_NAME, null);
    }

    // ==================== 命中轴直接比对与脏数据回落 ====================

    @Test
    void should_resolveNameAxisHit_when_detect_given_existingFileNameMatched() {
        // given：文件名轴命中、内容轴不命中
        DedupPolicyConfig policy = new DedupPolicyConfig(DedupMatchRule.BY_NAME_OR_CONTENT, DedupConflictAction.REJECT);
        Document duplicate = buildDuplicate(90L, FILE_NAME, OTHER_HASH);
        when(documentRepository.findDuplicates(KB_ID, FILE_NAME, CONTENT_HASH)).thenReturn(List.of(duplicate));

        // when
        List<DedupHit> hits = service.detect(KB_ID, policy, FILE_NAME, CONTENT_HASH);

        // then
        assertEquals(1, hits.size());
        assertEquals(90L, hits.get(0).document().id().longValue());
        assertEquals(List.of(DocumentDedupService.AXIS_FILE_NAME), hits.get(0).matchAxes());
    }

    @Test
    void should_resolveHashAxisHit_when_detect_given_existingContentHashMatched() {
        // given：内容轴命中、文件名轴不命中
        DedupPolicyConfig policy = new DedupPolicyConfig(DedupMatchRule.BY_NAME_OR_CONTENT, DedupConflictAction.SKIP);
        Document duplicate = buildDuplicate(94L, OTHER_FILE_NAME, CONTENT_HASH);
        when(documentRepository.findDuplicates(KB_ID, FILE_NAME, CONTENT_HASH)).thenReturn(List.of(duplicate));

        // when
        List<DedupHit> hits = service.detect(KB_ID, policy, FILE_NAME, CONTENT_HASH);

        // then：命中文档取其自身一等列取值直接比对，仅内容轴成立
        assertEquals(1, hits.size());
        assertEquals(List.of(DocumentDedupService.AXIS_CONTENT_HASH), hits.get(0).matchAxes());
    }

    @Test
    void should_resolveBothAxesHit_when_detect_given_bothAxesMatched() {
        // given
        DedupPolicyConfig policy = new DedupPolicyConfig(DedupMatchRule.BY_NAME_OR_CONTENT, DedupConflictAction.SKIP);
        Document duplicate = buildDuplicate(91L, FILE_NAME, CONTENT_HASH);
        when(documentRepository.findDuplicates(KB_ID, FILE_NAME, CONTENT_HASH)).thenReturn(List.of(duplicate));

        // when
        List<DedupHit> hits = service.detect(KB_ID, policy, FILE_NAME, CONTENT_HASH);

        // then：双轴按「文件名、内容哈希」顺序给出
        assertEquals(1, hits.size());
        assertEquals(List.of(DocumentDedupService.AXIS_FILE_NAME, DocumentDedupService.AXIS_CONTENT_HASH),
                hits.get(0).matchAxes());
    }

    @Test
    void should_fallbackToNameAxis_when_detect_given_neitherAxisComparable() {
        // given：存量行两轴取值均与本次查询轴不等（脏数据），比对不出任何轴，回退为本次实际携带的首个查询轴
        DedupPolicyConfig policy = new DedupPolicyConfig(DedupMatchRule.BY_NAME_OR_CONTENT, DedupConflictAction.REJECT);
        Document duplicate = buildDuplicate(92L, "历史脏数据.pdf", null);
        when(documentRepository.findDuplicates(KB_ID, FILE_NAME, CONTENT_HASH)).thenReturn(List.of(duplicate));

        // when
        List<DedupHit> hits = service.detect(KB_ID, policy, FILE_NAME, CONTENT_HASH);

        // then：文件名轴优先回退
        assertEquals(1, hits.size());
        assertEquals(List.of(DocumentDedupService.AXIS_FILE_NAME), hits.get(0).matchAxes());
    }

    @Test
    void should_fallbackToHashAxis_when_detect_given_hashOnlyQueryAndDirtyRow() {
        // given：BY_CONTENT 单轴 + 存量行内容哈希缺失，回退为内容哈希轴
        DedupPolicyConfig policy = new DedupPolicyConfig(DedupMatchRule.BY_CONTENT, DedupConflictAction.SKIP);
        Document duplicate = buildDuplicate(93L, OTHER_FILE_NAME, null);
        when(documentRepository.findDuplicates(KB_ID, null, CONTENT_HASH)).thenReturn(List.of(duplicate));

        // when
        List<DedupHit> hits = service.detect(KB_ID, policy, FILE_NAME, CONTENT_HASH);

        // then
        assertEquals(1, hits.size());
        assertEquals(List.of(DocumentDedupService.AXIS_CONTENT_HASH), hits.get(0).matchAxes());
    }

    // ==================== 重复摘要格式与删除链两态过滤 ====================

    @Test
    void should_returnGenericMessage_when_describeHits_given_emptyHits() {
        // given

        // when
        String message = service.describeHits(List.of());

        // then
        assertEquals("检测到重复文档", message);
    }

    @Test
    void should_returnFormattedSummary_when_describeHits_given_singleHit() {
        // given
        DedupHit hit = new DedupHit(buildDuplicate(90L, FILE_NAME, null), List.of(DocumentDedupService.AXIS_FILE_NAME));

        // when
        String message = service.describeHits(List.of(hit));

        // then
        assertEquals("检测到重复文档：文档ID=90「手册.pdf」（命中轴：fileName）", message);
    }

    @Test
    void should_joinHitsAndAxes_when_describeHits_given_multipleHits() {
        // given
        DedupHit first = new DedupHit(buildDuplicate(90L, FILE_NAME, null),
                List.of(DocumentDedupService.AXIS_FILE_NAME));
        DedupHit second = new DedupHit(buildDuplicate(91L, OTHER_FILE_NAME, null),
                List.of(DocumentDedupService.AXIS_FILE_NAME, DocumentDedupService.AXIS_CONTENT_HASH));

        // when
        String message = service.describeHits(List.of(first, second));

        // then：多条命中用「；」分隔，多轴用「、」分隔
        assertEquals("检测到重复文档：文档ID=90「手册.pdf」（命中轴：fileName）"
                + "；文档ID=91「报表.xlsx」（命中轴：fileName、contentHash）", message);
    }

    @Test
    void should_excludeDeletingItems_when_describeHits_given_hitsWithDeleting() {
        // given：混合状态命中项，DELETING 项不应计入用户可见文案
        DedupHit processed = new DedupHit(buildDuplicateWithStatus(90L, FILE_NAME, DocumentStatus.PROCESSED),
                List.of(DocumentDedupService.AXIS_FILE_NAME));
        DedupHit deleting = new DedupHit(buildDuplicateWithStatus(91L, "删除中.pdf", DocumentStatus.DELETING),
                List.of(DocumentDedupService.AXIS_FILE_NAME));

        // when
        String message = service.describeHits(List.of(processed, deleting));

        // then：仅保留 PROCESSED 项
        assertEquals("检测到重复文档：文档ID=90「手册.pdf」（命中轴：fileName）", message);
    }

    @Test
    void should_excludeDeleteFailedItems_when_describeHits_given_hitsWithDeleteFailed() {
        // given：混合状态命中项，DELETE_FAILED 项不应计入用户可见文案
        DedupHit deleting = new DedupHit(buildDuplicateWithStatus(90L, "删除中.pdf", DocumentStatus.DELETING),
                List.of(DocumentDedupService.AXIS_FILE_NAME));
        DedupHit deleteFailed = new DedupHit(
                buildDuplicateWithStatus(91L, "删除失败.pdf", DocumentStatus.DELETE_FAILED),
                List.of(DocumentDedupService.AXIS_CONTENT_HASH));
        DedupHit processed = new DedupHit(buildDuplicateWithStatus(92L, OTHER_FILE_NAME, DocumentStatus.PROCESSED),
                List.of(DocumentDedupService.AXIS_FILE_NAME, DocumentDedupService.AXIS_CONTENT_HASH));

        // when
        String message = service.describeHits(List.of(deleting, deleteFailed, processed));

        // then：DELETING / DELETE_FAILED 均被排除，只剩 PROCESSED 项
        assertEquals("检测到重复文档：文档ID=92「报表.xlsx」（命中轴：fileName、contentHash）", message);
    }

    @Test
    void should_returnGenericMessage_when_describeHits_given_allHitsDeletingOrDeleteFailed() {
        // given：命中集合全部处于删除中间态
        DedupHit deleting = new DedupHit(buildDuplicateWithStatus(90L, "删除中.pdf", DocumentStatus.DELETING),
                List.of(DocumentDedupService.AXIS_FILE_NAME));
        DedupHit deleteFailed = new DedupHit(
                buildDuplicateWithStatus(91L, "删除失败.pdf", DocumentStatus.DELETE_FAILED),
                List.of(DocumentDedupService.AXIS_FILE_NAME));

        // when
        String message = service.describeHits(List.of(deleting, deleteFailed));

        // then：与「无命中」路径返回一致
        assertEquals("检测到重复文档", message);
    }
}