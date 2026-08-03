package com.linkroa.deepdataagent.agent.infrastructure.persistence;

import com.linkroa.deepdataagent.agent.domain.repository.DialogueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * DialogueCleanupService 单元测试
 * <p>覆盖应用启动时扫描并标记崩溃遗留的 RUNNING 对话为 FAILED 的清理逻辑。</p>
 */
@ExtendWith(MockitoExtension.class)
class DialogueCleanupServiceTest {

    @Mock
    private DialogueRepository dialogueRepository;

    private DialogueCleanupService service;

    @BeforeEach
    void setUp() {
        service = new DialogueCleanupService(dialogueRepository);
    }

    @Test
    void should_markAllRunningAsFailed_when_cleanupRunningDialogues_given_startup() {
        // when
        service.cleanupRunningDialogues();

        // then
        verify(dialogueRepository).markAllRunningAsFailed();
    }

    @Test
    void should_notThrow_when_cleanupRunningDialogues_given_repositoryError() {
        // given
        doThrow(new RuntimeException("db down")).when(dialogueRepository).markAllRunningAsFailed();

        // when & then
        assertDoesNotThrow(() -> service.cleanupRunningDialogues());
    }
}