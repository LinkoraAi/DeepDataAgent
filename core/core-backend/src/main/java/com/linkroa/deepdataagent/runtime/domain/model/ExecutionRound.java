package com.linkroa.deepdataagent.runtime.domain.model;

import com.linkroa.deepdataagent.runtime.domain.model.enums.RoundStatus;
import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * 执行轮次领域模型（对应 execution_round 表）。一轮对应一次 agent 运行（一次消息发送）。
 */
public record ExecutionRound(
        Long id,
        String roundId,
        String sessionId,
        String runId,
        int roundNumber,
        String input,
        String output,
        RoundStatus status,
        String replayedFromRoundId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String createdBy,
        String updatedBy
) {

    public ExecutionRound {
        if (StringUtils.isBlank(roundId)) {
            throw new IllegalArgumentException("轮次ID不能为空");
        }
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("会话ID不能为空");
        }
        if (roundNumber <= 0) {
            throw new IllegalArgumentException("轮次序号必须为正数");
        }
        if (input == null) {
            throw new IllegalArgumentException("轮次输入不能为空");
        }
        if (status == null) {
            throw new IllegalArgumentException("轮次状态不能为空");
        }
    }

    /**
     * 创建执行轮次（RUNNING 初始态）。
     *
     * @param sessionId   会话 ID
     * @param runId       OpenAPI 层 runId（UUID）
     * @param roundNumber 轮次序号（会话内递增，由仓储分配）
     * @param input       用户消息全文
     */
    public static ExecutionRound create(String sessionId, String runId, int roundNumber, String input) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        return new ExecutionRound(
                null,
                UUID.randomUUID().toString().replace("-", ""),
                sessionId,
                runId,
                roundNumber,
                input,
                null,
                RoundStatus.RUNNING,
                null,
                now,
                now,
                null,
                null
        );
    }

    /**
     * 创建重放轮次（UC-RUN-005）：
     *
     * @param sessionId           会话 ID
     * @param runId               OpenAPI 层 runId
     * @param roundNumber         轮次序号
     * @param input               输入
     * @param replayedFromRoundId 来源轮次 ID
     */
    public static ExecutionRound createReplayed(
            String sessionId, String runId, int roundNumber, String input, String replayedFromRoundId
    ) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        return new ExecutionRound(
                null,
                UUID.randomUUID().toString().replace("-", ""),
                sessionId,
                runId,
                roundNumber,
                input,
                null,
                RoundStatus.RUNNING,
                replayedFromRoundId,
                now,
                now,
                null,
                null
        );
    }

    /**
     * 从数据库恢复（查询场景）。
     */
    public static ExecutionRound restore(
            Long id,
            String roundId,
            String sessionId,
            String runId,
            int roundNumber,
            String input,
            String output,
            RoundStatus status,
            String replayedFromRoundId,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String createdBy,
            String updatedBy
    ) {
        return new ExecutionRound(
                id, roundId, sessionId, runId, roundNumber, input, output,
                status, replayedFromRoundId, createdAt, updatedAt, createdBy, updatedBy
        );
    }

    /**
     * 派生终态轮次（写入 output 与终态，时间戳刷新）。
     */
    public ExecutionRound complete(String finalOutput, RoundStatus finalStatus) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        return new ExecutionRound(
                id, roundId, sessionId, runId, roundNumber, input, finalOutput,
                finalStatus, replayedFromRoundId, createdAt, now, createdBy, updatedBy
        );
    }
}