package com.linkroa.deepdataagent.agent.infrastructure.persistence;

import com.linkroa.deepdataagent.agent.domain.repository.DialogueRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 对话轮次崩溃清理服务
 * <p>应用启动时扫描所有 RUNNING 状态的对话轮次（僵尸对话），批量标记为 FAILED，
 * 形成崩溃恢复闭环：流式过程中崩溃遗留的 RUNNING 对话不会污染后续分析，
 * 且保证旧数据不会在下次查询时被视为"进行中"。</p>
 */
@Service
public class DialogueCleanupService {

    private static final Logger log = LoggerFactory.getLogger(DialogueCleanupService.class);

    private final DialogueRepository dialogueRepository;

    /**
     * 构造方法
     *
     * @param dialogueRepository 对话轮次仓储
     */
    public DialogueCleanupService(DialogueRepository dialogueRepository) {
        this.dialogueRepository = dialogueRepository;
    }

    /**
     * 启动时清理僵尸对话
     * <p>将上次进程崩溃遗留的所有 RUNNING 对话批量标记为 FAILED。</p>
     */
    @PostConstruct
    public void cleanupRunningDialogues() {
        try {
            dialogueRepository.markAllRunningAsFailed();
            log.info("DialogueCleanupService: 已清理崩溃遗留的 RUNNING 对话");
        } catch (Exception e) {
            log.error("DialogueCleanupService: 清理 RUNNING 对话失败", e);
        }
    }
}