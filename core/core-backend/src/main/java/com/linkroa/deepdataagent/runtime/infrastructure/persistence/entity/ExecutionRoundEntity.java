package com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 执行轮次持久化实体（execution_round）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("execution_round")
public class ExecutionRoundEntity extends BaseEntity {

    private String roundId;
    private String sessionId;
    private String runId;
    private Integer roundNumber;
    private String input;
    private String output;
    private String status;
    private String replayedFromRoundId;
}