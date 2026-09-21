package com.linkroa.deepdataagent.knowledgebase.controller.rest;

import com.linkroa.deepdataagent.knowledgebase.application.command.CreateKnowledgeBaseCommand;
import com.linkroa.deepdataagent.knowledgebase.application.command.DeleteKnowledgeBaseCommand;
import com.linkroa.deepdataagent.knowledgebase.application.command.UpdateEntityTypeConfigCommand;
import com.linkroa.deepdataagent.knowledgebase.application.command.UpdateKnowledgeBaseCommand;
import com.linkroa.deepdataagent.knowledgebase.application.command.UpdateRetrievalConfigCommand;
import com.linkroa.deepdataagent.knowledgebase.application.query.ListKnowledgeBaseQuery;
import com.linkroa.deepdataagent.knowledgebase.application.service.KnowledgeBaseApplicationService;
import com.linkroa.deepdataagent.knowledgebase.controller.request.CreateKnowledgeBaseRequest;
import com.linkroa.deepdataagent.knowledgebase.controller.request.UpdateEntityTypeConfigRequest;
import com.linkroa.deepdataagent.knowledgebase.controller.request.UpdateKnowledgeBaseRequest;
import com.linkroa.deepdataagent.knowledgebase.controller.request.UpdateRetrievalConfigRequest;
import com.linkroa.deepdataagent.knowledgebase.controller.response.KnowledgeBaseResponse;
import com.linkroa.deepdataagent.knowledgebase.controller.response.KnowledgeBaseStatsResponse;
import com.linkroa.deepdataagent.knowledgebase.domain.model.KnowledgeBase;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.LifecycleStatus;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import com.linkroa.deepdataagent.shared.result.PaginatedResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link KnowledgeBaseController} 单元测试。
 * <p>锁定 HTTP 协议适配契约：命令构建、分页参数兜底、响应转换与异常透传。</p>
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseControllerTest {

    private static final String RAG_CONFIG = "{\"engineType\":\"RAGFLOW\"}";
    private static final String DEDUP_POLICY = "{\"matchRule\":\"HASH\"}";
    private static final String RETRIEVAL_STRATEGY = "{\"strategyType\":\"HYBRID\"}";
    private static final String EMBEDDING_CONFIG = "{\"modelProfileId\":1}";
    private static final String MULTI_MODEL_CONFIG = "{\"modelProfileId\":2}";
    private static final String ENTITY_TYPE_CONFIG = "{\"entityTypes\":[]}";

    @Mock
    private KnowledgeBaseApplicationService applicationService;

    @InjectMocks
    private KnowledgeBaseController controller;

    @Test
    void should_returnKnowledgeBase_when_create_given_validRequest() {
        // given
        CreateKnowledgeBaseRequest request = createRequest();
        when(applicationService.create(any(CreateKnowledgeBaseCommand.class))).thenReturn(knowledgeBase(1L));

        // when
        ApiResponse<KnowledgeBaseResponse> response = controller.create(request);

        // then
        assertTrue(response.success());
        assertEquals(1L, response.data().id());
        assertEquals("测试知识库", response.data().name());
        assertEquals(LifecycleStatus.ACTIVE.name(), response.data().lifecycleStatus());
    }

    @Test
    void should_buildCreateCommandInOrder_when_create_given_allFields() {
        // given
        when(applicationService.create(any(CreateKnowledgeBaseCommand.class))).thenReturn(knowledgeBase(1L));

        // when
        controller.create(createRequest());

        // then
        ArgumentCaptor<CreateKnowledgeBaseCommand> captor = ArgumentCaptor.forClass(CreateKnowledgeBaseCommand.class);
        verify(applicationService).create(captor.capture());
        CreateKnowledgeBaseCommand command = captor.getValue();
        assertEquals("测试知识库", command.name());
        assertEquals("用于单测的知识库", command.description());
        assertEquals(RAG_CONFIG, command.ragEngineConfig());
        assertEquals(DEDUP_POLICY, command.dedupPolicy());
        assertEquals(RETRIEVAL_STRATEGY, command.retrievalStrategy());
        assertEquals(EMBEDDING_CONFIG, command.embeddingConfig());
        assertEquals(MULTI_MODEL_CONFIG, command.multiModelConfig());
        assertEquals(ENTITY_TYPE_CONFIG, command.entityTypeConfig());
    }

    @Test
    void should_updateWithPathVariableId_when_update_given_requestWithoutId() {
        // given
        UpdateKnowledgeBaseRequest request = new UpdateKnowledgeBaseRequest("新名称", null, "Chinese", null,
                null, null, null, null, null);
        when(applicationService.update(any(UpdateKnowledgeBaseCommand.class))).thenReturn(knowledgeBase(9L));

        // when
        ApiResponse<KnowledgeBaseResponse> response = controller.update(9L, request);

        // then
        verify(applicationService).update(new UpdateKnowledgeBaseCommand(9L, "新名称", null, "Chinese", null,
                null, null, null, null, null));
        assertTrue(response.success());
    }

    @Test
    void should_returnDeletingSnapshotWithAcceptedCode_when_delete_given_id() {
        // given：受理矩阵已完成 CAS 置态与清退投递，控制器只回显「删除中」快照
        when(applicationService.delete(new DeleteKnowledgeBaseCommand(7L)))
                .thenReturn(knowledgeBase(7L, LifecycleStatus.DELETING, null));

        // when
        ApiResponse<KnowledgeBaseResponse> response = controller.delete(7L);

        // then：202 受理语义 + 生命周期回显 DELETING，且不等待任何清退步骤
        assertTrue(response.success());
        assertEquals("202", response.code());
        assertTrue(response.message().contains("删除中"));
        assertEquals(LifecycleStatus.DELETING.name(), response.data().lifecycleStatus());
        assertNull(response.data().errorMessage());
        verify(applicationService).delete(new DeleteKnowledgeBaseCommand(7L));
    }

    @Test
    void should_echoFailureTrace_when_detail_given_deleteFailedKnowledgeBase() {
        // given：清退失败的库以 DELETE_FAILED + error_message 对外可感知（失败可见性）
        when(applicationService.get(7L)).thenReturn(
                knowledgeBase(7L, LifecycleStatus.DELETE_FAILED, "[KB-CLEANUP] step=GRAPH: 数据库异常"));

        // when
        ApiResponse<KnowledgeBaseResponse> response = controller.detail(7L);

        // then
        assertEquals(LifecycleStatus.DELETE_FAILED.name(), response.data().lifecycleStatus());
        assertEquals("[KB-CLEANUP] step=GRAPH: 数据库异常", response.data().errorMessage());
    }

    @Test
    void should_useDefaultPaging_when_list_given_noPageParams() {
        // given
        when(applicationService.list(new ListKnowledgeBaseQuery("kb", 1, 10, null, null))).thenReturn(List.of());
        when(applicationService.count("kb")).thenReturn(0L);

        // when
        ApiResponse<PaginatedResponse<KnowledgeBaseResponse>> response = controller.list("kb", null, null, null, null);

        // then
        assertEquals(1, response.data().page());
        assertEquals(10, response.data().size());
        assertEquals(0L, response.data().total());
        assertTrue(response.data().list().isEmpty());
    }

    @Test
    void should_capPageSize_when_list_given_oversizedSize() {
        // given
        when(applicationService.list(new ListKnowledgeBaseQuery(null, 2, 100, null, null))).thenReturn(List.of());
        when(applicationService.count(null)).thenReturn(0L);

        // when
        ApiResponse<PaginatedResponse<KnowledgeBaseResponse>> response = controller.list(null, 2, 500, null, null);

        // then
        assertEquals(2, response.data().page());
        assertEquals(100, response.data().size());
    }

    @Test
    void should_fallbackToDefaultPage_when_list_given_illegalPageParams() {
        // given
        when(applicationService.list(new ListKnowledgeBaseQuery(null, 1, 10, null, null))).thenReturn(List.of());
        when(applicationService.count(null)).thenReturn(0L);

        // when
        ApiResponse<PaginatedResponse<KnowledgeBaseResponse>> response = controller.list(null, 0, -5, null, null);

        // then
        assertEquals(1, response.data().page());
        assertEquals(10, response.data().size());
    }

    @Test
    void should_convertDomainList_when_list_given_keywordMatches() {
        // given
        when(applicationService.list(new ListKnowledgeBaseQuery("kw", 1, 10, null, null)))
                .thenReturn(List.of(knowledgeBase(1L), knowledgeBase(2L)));
        when(applicationService.count("kw")).thenReturn(2L);

        // when
        ApiResponse<PaginatedResponse<KnowledgeBaseResponse>> response = controller.list("kw", 1, 10, null, null);

        // then
        assertEquals(2, response.data().list().size());
        assertEquals(2L, response.data().total());
        assertEquals("测试知识库", response.data().list().get(0).name());
    }

    @Test
    void should_passThroughSortParams_when_list_given_sortByAndOrder() {
        // given
        when(applicationService.list(new ListKnowledgeBaseQuery("kw", 1, 10, "name", "asc"))).thenReturn(List.of());
        when(applicationService.count("kw")).thenReturn(0L);

        // when
        controller.list("kw", 1, 10, "name", "asc");

        // then
        verify(applicationService).list(new ListKnowledgeBaseQuery("kw", 1, 10, "name", "asc"));
    }

    @Test
    void should_returnZeroStats_when_stats_given_emptyStatistics() {
        // given
        when(applicationService.stats()).thenReturn(Map.of());

        // when
        ApiResponse<KnowledgeBaseStatsResponse> response = controller.stats();

        // then
        assertEquals(0L, response.data().totalKnowledgeBases());
        assertEquals(0L, response.data().totalDocuments());
        assertEquals(0L, response.data().totalChunks());
    }

    @Test
    void should_returnStatistics_when_stats_given_serviceCounts() {
        // given
        when(applicationService.stats()).thenReturn(Map.of(
                "totalKnowledgeBases", 3L, "totalDocuments", 12L, "totalChunks", 480L));

        // when
        ApiResponse<KnowledgeBaseStatsResponse> response = controller.stats();

        // then
        assertEquals(3L, response.data().totalKnowledgeBases());
        assertEquals(12L, response.data().totalDocuments());
        assertEquals(480L, response.data().totalChunks());
    }

    @Test
    void should_returnDetail_when_detail_given_existingId() {
        // given
        when(applicationService.get(5L)).thenReturn(knowledgeBase(5L));

        // when
        ApiResponse<KnowledgeBaseResponse> response = controller.detail(5L);

        // then
        assertEquals(5L, response.data().id());
        verify(applicationService).get(5L);
    }

    @Test
    void should_propagateException_when_detail_given_notExistingId() {
        // given
        when(applicationService.get(404L)).thenThrow(new IllegalStateException("知识库不存在"));

        // when & then
        assertThrows(IllegalStateException.class, () -> controller.detail(404L));
        verify(applicationService, never()).list(any(ListKnowledgeBaseQuery.class));
    }

    @Test
    void should_updateRetrievalConfig_when_updateRetrievalConfig_given_pathIdAndBody() {
        // given
        when(applicationService.updateRetrievalConfig(any(UpdateRetrievalConfigCommand.class)))
                .thenReturn(knowledgeBase(6L));

        // when
        ApiResponse<KnowledgeBaseResponse> response =
                controller.updateRetrievalConfig(6L, new UpdateRetrievalConfigRequest(RETRIEVAL_STRATEGY));

        // then
        verify(applicationService).updateRetrievalConfig(new UpdateRetrievalConfigCommand(6L, RETRIEVAL_STRATEGY));
        assertEquals(6L, response.data().id());
    }

    @Test
    void should_updateEntityTypeConfig_when_updateEntityTypeConfig_given_pathIdAndBody() {
        // given
        when(applicationService.updateEntityTypeConfig(any(UpdateEntityTypeConfigCommand.class)))
                .thenReturn(knowledgeBase(6L));

        // when
        ApiResponse<KnowledgeBaseResponse> response =
                controller.updateEntityTypeConfig(6L, new UpdateEntityTypeConfigRequest(ENTITY_TYPE_CONFIG));

        // then
        verify(applicationService).updateEntityTypeConfig(
                new UpdateEntityTypeConfigCommand(6L, ENTITY_TYPE_CONFIG));
        assertEquals(ENTITY_TYPE_CONFIG, response.data().entityTypeConfig());
    }

    private CreateKnowledgeBaseRequest createRequest() {
        return new CreateKnowledgeBaseRequest("测试知识库", "用于单测的知识库", "Chinese", RAG_CONFIG, DEDUP_POLICY,
                EMBEDDING_CONFIG, MULTI_MODEL_CONFIG, RETRIEVAL_STRATEGY, ENTITY_TYPE_CONFIG);
    }

    private KnowledgeBase knowledgeBase(Long id) {
        return knowledgeBase(id, LifecycleStatus.ACTIVE, null);
    }

    private KnowledgeBase knowledgeBase(Long id, LifecycleStatus lifecycleStatus, String errorMessage) {
        return KnowledgeBase.restore(id, "测试知识库", "用于单测的知识库", "Chinese", lifecycleStatus,
                errorMessage, RAG_CONFIG, DEDUP_POLICY, RETRIEVAL_STRATEGY, EMBEDDING_CONFIG, MULTI_MODEL_CONFIG,
                ENTITY_TYPE_CONFIG, null, null);
    }
}
