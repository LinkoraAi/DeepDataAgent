package com.linkroa.deepdataagent.knowledgebase.infrastructure;

import com.linkroa.deepdataagent.knowledgebase.application.contract.ChunkIdentity;
import com.linkroa.deepdataagent.knowledgebase.application.contract.IngestionDocumentContext;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Document;
import com.linkroa.deepdataagent.knowledgebase.domain.model.DocumentParseConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.EmbeddingModelConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.EngineConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.EntityType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.KnowledgeBase;
import com.linkroa.deepdataagent.knowledgebase.domain.model.MultiModelConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.RagEngineConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.S3File;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentStatus;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FileType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ImportType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.KbLanguage;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.LifecycleStatus;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ParseEngineProvider;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.RagEngineType;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.ChunkRepository;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.DocumentRepository;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.KnowledgeBaseRepository;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultIngestionSourceReader} 契约实现单测（mock 三个仓储）。
 * <p>覆盖：正常解析（含 {@code multi_model_config} → {@code ctx.multiModelConfig()} 的
 * 知识库级通用 LLM 真相源解析）、聚合缺失、各配置 JSON 非法 / 数据损坏、实体类型兜底、
 * 空白配置归一为 null、知识库语言归一（真相源 = {@code knowledge_base.language} 列，
 *）、DELETING 早闸门、状态查询与切片身份映射。</p>
 */
@ExtendWith(MockitoExtension.class)
class DefaultIngestionSourceReaderTest {

    private static final Long KB_ID = 10L;
    private static final Long DOC_ID = 100L;
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-01T10:00:00+08:00");

    /** 合法且完整的 rag_engine_config：含引擎类型、解析引擎段与分块策略段 */
    private static final String FULL_ENGINE_JSON = """
            {"engineType":"DOCUMENT_ENGINE","parseEngine":{"provider":"MINERU","engineConfig":{"params":{"baseUrl":"http://mineru.local","timeout":30,"enable":true}}},"chunkStrategy":{"mode":"precise"}}""";

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Mock
    private ChunkRepository chunkRepository;

    @InjectMocks
    private DefaultIngestionSourceReader reader;

    /**
     * 构造文档聚合根（PENDING 状态，携带源文件引用与文档级分块策略 JSON）。
     */
    private static Document documentOf(String s3FileJson, String chunkStrategyJson) {
        return Document.restore(DOC_ID, KB_ID, "手册.pdf", FileType.PDF, DocumentStatus.PENDING,
                null, 1024L, 0, null, ImportType.UPLOAD, s3FileJson, null, chunkStrategyJson, NOW, NOW);
    }

    /**
     * 构造知识库聚合根（language 列空白，仅关心四个 JSONB 配置列）。
     */
    private static KnowledgeBase knowledgeBaseOf(String ragEngineConfig, String embeddingConfig,
                                                 String multiModelConfig, String entityTypeConfig) {
        return knowledgeBaseOf(null, ragEngineConfig, embeddingConfig, multiModelConfig, entityTypeConfig);
    }

    /**
     * 构造知识库聚合根（显式携带 language 列值，语言真相源用例用）。
     */
    private static KnowledgeBase knowledgeBaseOf(String language, String ragEngineConfig, String embeddingConfig,
                                                 String multiModelConfig, String entityTypeConfig) {
        return KnowledgeBase.restore(KB_ID, "产品手册", "描述", language, LifecycleStatus.ACTIVE,
                null, ragEngineConfig, null, null, embeddingConfig, multiModelConfig, entityTypeConfig, NOW, NOW);
    }

    /**
     * 打桩文档与知识库查询。
     */
    private void stubFind(Document document, KnowledgeBase knowledgeBase) {
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(document));
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.of(knowledgeBase));
    }

    @Test
    void should_returnFullContext_when_readContext_given_allConfigsValid() {
        // given
        String s3FileJson = "{\"objectKey\":\"rag/100/source/100/手册.pdf\"}";
        String docChunkStrategyJson = "{\"mode\":\"custom\",\"modeConfig\":{\"params\":{\"chunkSize\":512}}}";
        stubFind(documentOf(s3FileJson, docChunkStrategyJson),
                knowledgeBaseOf("Chinese", FULL_ENGINE_JSON,
                        "{\"modelProfileId\":\"emb-profile\"}",
                        "{\"modelProfileId\":\"mm-profile\"}",
                        "{\"entityTypes\":[{\"entityType\":\"人物\"},{\"entityType\":\"组织\"}]}"));

        // when
        IngestionDocumentContext context = reader.readContext(DOC_ID);

        // then
        assertEquals(DOC_ID, context.documentId());
        assertEquals(KB_ID, context.kbId());
        assertEquals("手册.pdf", context.fileName());
        assertEquals(DocumentStatus.PENDING, context.status());
        assertEquals(new S3File("rag/100/source/100/手册.pdf"), context.s3File());
        assertEquals(docChunkStrategyJson, context.documentChunkStrategyJson());
        assertEquals(RagEngineType.DOCUMENT_ENGINE, context.ragEngineConfig().engineType());
        assertTrue(context.ragEngineConfig().parseEngine().contains("MINERU"));
        assertTrue(context.ragEngineConfig().chunkStrategy().contains("precise"));
        assertEquals(new DocumentParseConfig(ParseEngineProvider.MINERU,
                new EngineConfig(Map.of("baseUrl", "http://mineru.local", "timeout", 30L, "enable", true))),
                context.parseConfig());
        assertEquals(new EmbeddingModelConfig("emb-profile"), context.embeddingConfig());
        assertEquals(new MultiModelConfig("mm-profile"), context.multiModelConfig());
        assertEquals(List.of(new EntityType("人物"), new EntityType("组织")), context.entityTypes());
        assertEquals(FULL_ENGINE_JSON, context.ragEngineConfigJson());
        // 语言取 knowledge_base.language 列值
        assertEquals("Chinese", context.language());
    }

    @Test
    void should_throwConflictAndStop_when_readContext_given_knowledgeBaseDeleting() {
        // given：知识库处于清退中（删除流程早闸门，/）
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(documentOf(null, null)));
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.of(
                KnowledgeBase.restore(KB_ID, "产品手册", "描述", "Chinese", LifecycleStatus.DELETING,
                        null, FULL_ENGINE_JSON, null, null, null, null, null, NOW, NOW)));

        // when
        ResourceConflictException exception = assertThrows(ResourceConflictException.class,
                () -> reader.readContext(DOC_ID));

        // then：闸门在任何后续组装/解析前抛 409 语义异常，不再触碰切片仓储
        assertTrue(exception.getMessage().contains("知识库清退中，拒绝摄入"));
        verify(chunkRepository, never()).findIdAndSequenceByDocumentId(anyLong());
    }

    @Test
    void should_throwConflictAndStop_when_readContext_given_knowledgeBaseDeleteFailed() {
        // given：知识库处于删除失败态（DELETE_FAILED 同为非 ACTIVE，读写拒绝口径与 DELETING 一致）
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(documentOf(null, null)));
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.of(
                KnowledgeBase.restore(KB_ID, "产品手册", "描述", "Chinese", LifecycleStatus.DELETE_FAILED,
                        "[KB-CLEANUP] step=chunk_cleanup", FULL_ENGINE_JSON, null, null, null, null, null,
                        NOW, NOW)));

        // when
        ResourceConflictException exception = assertThrows(ResourceConflictException.class,
                () -> reader.readContext(DOC_ID));

        // then：早闸门拒绝，不再触碰切片仓储
        assertTrue(exception.getMessage().contains("拒绝摄入"));
        verify(chunkRepository, never()).findIdAndSequenceByDocumentId(anyLong());
    }

    @Test
    void should_throwBusinessException_when_readContext_given_nullDocumentId() {
        // when
        DeepDataAgentException exception =
                assertThrows(DeepDataAgentException.class, () -> reader.readContext(null));

        // then
        assertTrue(exception.getMessage().contains("文档ID不能为空"));
        verifyNoInteractions(documentRepository, knowledgeBaseRepository, chunkRepository);
    }

    @Test
    void should_throwNotFound_when_readContext_given_documentMissing() {
        // given
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.empty());

        // when
        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class,
                () -> reader.readContext(DOC_ID));

        // then
        assertTrue(exception.getMessage().contains("文档不存在"));
    }

    @Test
    void should_throwNotFound_when_readContext_given_knowledgeBaseMissing() {
        // given
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(documentOf(null, null)));
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.empty());

        // when
        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class,
                () -> reader.readContext(DOC_ID));

        // then
        assertTrue(exception.getMessage().contains("知识库不存在"));
    }

    @Test
    void should_returnNullProjections_when_readContext_given_allConfigBlank() {
        // given：s3File 与各级配置均空白，应归一为 null / 空清单
        stubFind(documentOf("  ", "   "), knowledgeBaseOf(null, "", " ", null));

        // when
        IngestionDocumentContext context = reader.readContext(DOC_ID);

        // then
        assertNull(context.s3File());
        assertNull(context.documentChunkStrategyJson());
        assertNull(context.ragEngineConfig());
        assertNull(context.parseConfig());
        assertNull(context.embeddingConfig());
        assertNull(context.multiModelConfig());
        assertEquals(List.of(), context.entityTypes());
        assertNull(context.ragEngineConfigJson());
        assertNull(context.language());
    }

    @Test
    void should_normalizeKbLanguage_when_readContext_given_paddedLanguageColumn() {
        // given：language 列值大小写带空白（真相源 = 列，读取侧归一口径）
        stubFind(documentOf(null, null), knowledgeBaseOf(
                " japanese ", "{\"engineType\":\"DOCUMENT_ENGINE\"}", null, null, null));

        // when
        IngestionDocumentContext context = reader.readContext(DOC_ID);

        // then：归一为枚举规范全名
        assertEquals(KbLanguage.Japanese.name(), context.language());
    }

    @Test
    void should_normalizeLegacyUppercase_when_readContext_given_legacyStoredSample() {
        // given：存量大小写变体样本（如历史缺省值 ENGLISH）
        stubFind(documentOf(null, null), knowledgeBaseOf("ENGLISH", null, null, null, null));

        // when
        IngestionDocumentContext context = reader.readContext(DOC_ID);

        // then：读取侧归一为 English
        assertEquals(KbLanguage.English.name(), context.language());
    }

    @Test
    void should_passThroughLegacyBareCode_when_readContext_given_bareLanguageCode() {
        // given：历史裸语言码（写入口已拒绝新值，读侧宽容透传由 PromptCatalog 归 en 套）
        stubFind(documentOf(null, null), knowledgeBaseOf(" ja ", null, null, null, null));

        // when
        IngestionDocumentContext context = reader.readContext(DOC_ID);

        // then：值域外原样 trim 透传
        assertEquals("ja", context.language());
    }

    @Test
    void should_returnNullLanguage_when_readContext_given_languageBlankOrMissing() {
        // given：language 列为空白字符串（视同未配置）
        stubFind(documentOf(null, null), knowledgeBaseOf("  ", null, null, null, null));

        // when
        IngestionDocumentContext context = reader.readContext(DOC_ID);

        // then：null = 未配置，消费方回落全局默认语言
        assertNull(context.language());
    }

    @Test
    void should_parseMultiModelConfig_when_readContext_given_multiModelProfileIdConfigured() {
        // given：multi_model_config 配置 modelProfileId（摄入侧 LLM 与 VLM 的共同真相源）
        stubFind(documentOf(null, null), knowledgeBaseOf(
                "{\"engineType\":\"DOCUMENT_ENGINE\"}", null, "{\"modelProfileId\":\"llm-profile-1\"}", null));

        // when
        IngestionDocumentContext context = reader.readContext(DOC_ID);

        // then：投影为多模态模型配置值对象；引擎配置仅 3 分量，不承载模型选型与语言
        assertEquals(new MultiModelConfig("llm-profile-1"), context.multiModelConfig());
        assertEquals(new RagEngineConfig(RagEngineType.DOCUMENT_ENGINE, null, null),
                context.ragEngineConfig());
    }

    @Test
    void should_throwBusinessException_when_readContext_given_s3FileInvalidJson() {
        // given
        stubFind(documentOf("{oops", null), knowledgeBaseOf(null, null, null, null));

        // when
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> reader.readContext(DOC_ID));

        // then
        assertTrue(exception.getMessage().contains("文档源文件引用不是合法的JSON"));
    }

    @Test
    void should_throwBusinessException_when_readContext_given_s3FileMissingRequiredField() {
        // given：JSON 合法但缺少 objectKey（引用仅对象键单分量），与 S3File 不变量冲突视为数据损坏
        stubFind(documentOf("{}", null), knowledgeBaseOf(null, null, null, null));

        // when
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> reader.readContext(DOC_ID));

        // then
        assertTrue(exception.getMessage().contains("文档源文件引用数据损坏"));
    }

    @Test
    void should_throwBusinessException_when_readContext_given_ragEngineConfigInvalidJson() {
        // given
        stubFind(documentOf(null, null), knowledgeBaseOf("{oops", null, null, null));

        // when
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> reader.readContext(DOC_ID));

        // then
        assertTrue(exception.getMessage().contains("RAG引擎配置不是合法的JSON"));
    }

    @Test
    void should_throwBusinessException_when_readContext_given_ragEngineConfigMissingEngineType() {
        // given：缺少 engineType 与 RagEngineConfig 不变量冲突
        stubFind(documentOf(null, null), knowledgeBaseOf("{\"parseEngine\":{}}", null, null, null));

        // when
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> reader.readContext(DOC_ID));

        // then
        assertTrue(exception.getMessage().contains("RAG引擎配置数据损坏"));
    }

    @Test
    void should_returnNullParseConfig_when_readContext_given_parseEngineNotObject() {
        // given：parseEngine 为文本节点，便利投影保留原文，解析配置返回 null
        stubFind(documentOf(null, null),
                knowledgeBaseOf("{\"engineType\":\"DOCUMENT_ENGINE\",\"parseEngine\":\"mineru\"}",
                        null, null, null));

        // when
        IngestionDocumentContext context = reader.readContext(DOC_ID);

        // then
        assertEquals("mineru", context.ragEngineConfig().parseEngine());
        assertNull(context.parseConfig());
    }

    @Test
    void should_tolerateUnknownProvider_when_readContext_given_parseEngineUnknownProvider() {
        // given：provider 取值磁盘上可能出现未知枚举，按 null 处理由消费方回落默认
        stubFind(documentOf(null, null),
                knowledgeBaseOf("{\"engineType\":\"DOCUMENT_ENGINE\",\"parseEngine\":{\"provider\":\"UNDEFINED\"}}",
                        null, null, null));

        // when
        IngestionDocumentContext context = reader.readContext(DOC_ID);

        // then
        assertEquals(new DocumentParseConfig(null, new EngineConfig(Map.of())), context.parseConfig());
    }

    @Test
    void should_throwBusinessException_when_readContext_given_embeddingConfigInvalidJson() {
        // given
        stubFind(documentOf(null, null), knowledgeBaseOf(null, "{oops", null, null));

        // when
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> reader.readContext(DOC_ID));

        // then
        assertTrue(exception.getMessage().contains("嵌入模型配置不是合法的JSON"));
    }

    @Test
    void should_throwBusinessException_when_readContext_given_multiModelConfigMissingProfileId() {
        // given：JSON 合法但缺少 modelProfileId，视为数据损坏
        stubFind(documentOf(null, null), knowledgeBaseOf(null, null, "{\"other\":1}", null));

        // when
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> reader.readContext(DOC_ID));

        // then
        assertTrue(exception.getMessage().contains("多模态模型配置数据损坏"));
    }

    @Test
    void should_fallbackEmptyList_when_readContext_given_entityTypesInvalidJson() {
        // given：实体类型为非关键路径，解析失败按空清单兜底且不阻断
        stubFind(documentOf(null, null), knowledgeBaseOf(null, null, null, "{oops"));

        // when
        IngestionDocumentContext context = reader.readContext(DOC_ID);

        // then
        assertEquals(List.of(), context.entityTypes());
    }

    @Test
    void should_parseStringArray_when_readContext_given_entityTypesInTextForm() {
        // given：兼容纯字符串数组形态，空白名称条目跳过
        stubFind(documentOf(null, null),
                knowledgeBaseOf(null, null, null, "{\"entityTypes\":[\"人物\",\"\",{\"entityType\":\"组织\"}]}"));

        // when
        IngestionDocumentContext context = reader.readContext(DOC_ID);

        // then
        assertEquals(List.of(new EntityType("人物"), new EntityType("组织")), context.entityTypes());
    }

    @Test
    void should_returnStatus_when_statusOf_given_documentExists() {
        // given
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(documentOf(null, null)));

        // when
        DocumentStatus status = reader.statusOf(DOC_ID);

        // then
        assertEquals(DocumentStatus.PENDING, status);
    }

    @Test
    void should_returnNull_when_statusOf_given_documentMissing() {
        // given
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.empty());

        // when
        DocumentStatus status = reader.statusOf(DOC_ID);

        // then
        assertNull(status);
    }

    @Test
    void should_returnNullWithoutQuery_when_statusOf_given_nullDocumentId() {
        // when
        DocumentStatus status = reader.statusOf(null);

        // then
        assertNull(status);
        verifyNoInteractions(documentRepository);
    }

    @Test
    void should_mapIdentitiesKeepOrder_when_listChunkIdentitiesByDocument_given_sequencesPresent() {
        // given
        Map<Integer, Long> idBySequence = new LinkedHashMap<>();
        idBySequence.put(1, 1000L);
        idBySequence.put(2, 1001L);
        when(chunkRepository.findIdAndSequenceByDocumentId(DOC_ID)).thenReturn(idBySequence);

        // when
        List<ChunkIdentity> identities = reader.listChunkIdentitiesByDocument(DOC_ID);

        // then
        assertEquals(List.of(new ChunkIdentity(1, 1000L), new ChunkIdentity(2, 1001L)), identities);
    }

    @Test
    void should_returnEmptyWithoutQuery_when_listChunkIdentitiesByDocument_given_nullDocumentId() {
        // when
        List<ChunkIdentity> identities = reader.listChunkIdentitiesByDocument(null);

        // then
        assertEquals(List.of(), identities);
        verifyNoInteractions(chunkRepository);
    }

    @Test
    void should_returnEmptyList_when_listChunkIdentitiesByDocument_given_noChunksStored() {
        // given
        when(chunkRepository.findIdAndSequenceByDocumentId(anyLong())).thenReturn(Map.of());

        // when
        List<ChunkIdentity> identities = reader.listChunkIdentitiesByDocument(DOC_ID);

        // then
        assertEquals(List.of(), identities);
    }
}
