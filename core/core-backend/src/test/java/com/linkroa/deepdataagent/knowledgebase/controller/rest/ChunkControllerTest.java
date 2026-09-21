package com.linkroa.deepdataagent.knowledgebase.controller.rest;

import com.linkroa.deepdataagent.knowledgebase.application.command.CreateChunkCommand;
import com.linkroa.deepdataagent.knowledgebase.application.command.DeleteChunkCommand;
import com.linkroa.deepdataagent.knowledgebase.application.command.DeleteChunksCommand;
import com.linkroa.deepdataagent.knowledgebase.application.command.UpdateChunkCommand;
import com.linkroa.deepdataagent.knowledgebase.application.query.ListChunkQuery;
import com.linkroa.deepdataagent.knowledgebase.application.service.ChunkApplicationService;
import com.linkroa.deepdataagent.knowledgebase.controller.request.CreateChunkRequest;
import com.linkroa.deepdataagent.knowledgebase.controller.request.DeleteChunksRequest;
import com.linkroa.deepdataagent.knowledgebase.controller.request.UpdateChunkRequest;
import com.linkroa.deepdataagent.knowledgebase.controller.response.ChunkResponse;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Chunk;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ChunkContentType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ChunkSource;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import com.linkroa.deepdataagent.shared.result.PaginatedResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChunkController} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class ChunkControllerTest {

    private static final String ORIGINAL_ITEM = "{\"imageUrl\":\"http://x/1.png\"}";

    @Mock
    private ChunkApplicationService applicationService;

    @InjectMocks
    private ChunkController controller;

    @Test
    void should_returnChunk_when_create_given_validRequest() {
        // given
        when(applicationService.create(any(CreateChunkCommand.class))).thenReturn(chunk(1L));

        // when
        ApiResponse<ChunkResponse> response = controller.create(createRequest());

        // then
        assertTrue(response.success());
        assertEquals(1L, response.data().id());
        assertEquals("切片内容", response.data().chunkContent());
        assertEquals(ChunkContentType.TEXT.name(), response.data().chunkContentType());
        // 来源标识以枚举名透出（管理侧据此区分「可删除」与「仅可编辑」的切片）
        assertEquals(ChunkSource.MANUAL.name(), response.data().sourceType());
    }

    @Test
    void should_buildCreateCommandInOrder_when_create_given_allFields() {
        // given
        when(applicationService.create(any(CreateChunkCommand.class))).thenReturn(chunk(1L));

        // when
        controller.create(createRequest());

        // then
        ArgumentCaptor<CreateChunkCommand> captor = ArgumentCaptor.forClass(CreateChunkCommand.class);
        verify(applicationService).create(captor.capture());
        CreateChunkCommand command = captor.getValue();
        assertEquals(10L, command.kbId());
        assertEquals(20L, command.documentId());
        assertEquals(3, command.sequence());
        assertEquals(128, command.tokens());
        assertEquals("切片内容", command.chunkContent());
        assertEquals(ORIGINAL_ITEM, command.originalItem());
        assertEquals("TEXT", command.chunkContentType());
        assertEquals("a.pdf", command.sourceFileName());
    }

    @Test
    void should_buildUpdateCommandWithPathId_when_update_given_request() {
        // given
        when(applicationService.update(any(UpdateChunkCommand.class))).thenReturn(chunk(5L));

        // when
        ApiResponse<ChunkResponse> response = controller.update(5L, new UpdateChunkRequest("新内容", 66));

        // then
        verify(applicationService).update(new UpdateChunkCommand(5L, "新内容", 66));
        assertEquals(5L, response.data().id());
    }

    @Test
    void should_returnEmptyData_when_delete_given_id() {
        // when
        ApiResponse<Void> response = controller.delete(8L);

        // then
        assertTrue(response.success());
        assertNull(response.data());
        verify(applicationService).delete(new DeleteChunkCommand(8L));
    }

    @Test
    void should_returnEmptyDataAndBuildBatchCommand_when_batchDelete_given_ids() {
        // when
        ApiResponse<Void> response = controller.batchDelete(new DeleteChunksRequest(List.of(8L, 9L)));

        // then
        assertTrue(response.success());
        assertNull(response.data());
        verify(applicationService).deleteBatch(new DeleteChunksCommand(List.of(8L, 9L)));
    }

    @Test
    void should_useDefaultSizeTwenty_when_list_given_noPageParams() {
        // given
        when(applicationService.list(new ListChunkQuery(10L, 20L, 3, "kw", 1, 20))).thenReturn(List.of());
        when(applicationService.count(10L, 20L, 3, "kw")).thenReturn(0L);

        // when
        ApiResponse<PaginatedResponse<ChunkResponse>> response = controller.list(10L, 20L, 3, "kw", null, null);

        // then
        assertEquals(1, response.data().page());
        assertEquals(20, response.data().size());
    }

    @Test
    void should_capPageSize_when_list_given_oversizedSize() {
        // given
        when(applicationService.list(new ListChunkQuery(10L, null, null, null, 1, 100))).thenReturn(List.of());
        when(applicationService.count(10L, null, null, null)).thenReturn(0L);

        // when
        ApiResponse<PaginatedResponse<ChunkResponse>> response = controller.list(10L, null, null, null, 0, 200);

        // then
        assertEquals(1, response.data().page());
        assertEquals(100, response.data().size());
    }

    @Test
    void should_returnDetail_when_detail_given_existingId() {
        // given
        when(applicationService.get(6L)).thenReturn(chunk(6L));

        // when
        ApiResponse<ChunkResponse> response = controller.detail(6L);

        // then
        assertEquals(6L, response.data().id());
        assertEquals(20L, response.data().documentId());
    }

    @Test
    void should_propagateException_when_get_given_chunkNotFound() {
        // given
        when(applicationService.get(404L)).thenThrow(new IllegalArgumentException("切片不存在"));

        // when & then
        assertThrows(IllegalArgumentException.class, () -> controller.detail(404L));
    }

    private CreateChunkRequest createRequest() {
        return new CreateChunkRequest(10L, 20L, 3, 128, "切片内容", ORIGINAL_ITEM, "TEXT", "a.pdf");
    }

    private Chunk chunk(Long id) {
        return Chunk.restore(id, 10L, 20L, 3, 128, "切片内容", ORIGINAL_ITEM,
                ChunkContentType.TEXT, "a.pdf", null, ChunkSource.MANUAL, null, null);
    }
}
