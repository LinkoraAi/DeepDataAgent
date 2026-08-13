package com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.DialogueEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 对话轮次 Mapper
 * <p>SQL 定义见 DialogueMapper.xml。</p>
 */
@Mapper
public interface DialogueMapper extends BaseMapper<DialogueEntity> {

    List<DialogueEntity> selectBySessionId(@Param("sessionId") String sessionId);

    /**
     * 按轮次游标分页查询会话的对话轮次（id 倒序，最新在前）
     *
     * @param sessionId        会话 ID
     * @param beforeDialogueId 游标，非空时仅返回 id 小于该值的轮次（null 表示取最新）
     * @param limit            返回的最大轮次数
     * @return 对话轮次实体列表
     */
    List<DialogueEntity> selectRoundsBySessionId(@Param("sessionId") String sessionId,
                                                 @Param("beforeDialogueId") Long beforeDialogueId,
                                                 @Param("limit") int limit);

    List<DialogueEntity> selectRunning();

    /**
     * 查询对话的 messages 原始 JSON 字符串
     *
     * @param id 对话 ID
     * @return messages 列内容
     */
    String selectMessages(@Param("id") Long id);

    /**
     * 更新对话的 messages 内容
     *
     * @param id       对话 ID
     * @param messages 新 messages JSON 字符串
     * @return 影响行数
     */
    int updateMessages(@Param("id") Long id, @Param("messages") String messages);

    /**
     * 更新对话的 messages 与状态（同时写入结束时间）
     *
     * @param id       对话 ID
     * @param messages 新 messages JSON 字符串
     * @param status   新状态
     * @return 影响行数
     */
    int updateMessagesAndStatus(@Param("id") Long id, @Param("messages") String messages, @Param("status") String status);

    /**
     * 更新对话状态（同时写入结束时间）
     *
     * @param id     对话 ID
     * @param status 新状态
     * @return 影响行数
     */
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    /**
     * 更新对话状态与 metadata（同时写入结束时间）
     *
     * @param id       对话 ID
     * @param status   新状态
     * @param metadata 新 metadata JSON 字符串
     * @return 影响行数
     */
    int updateStatusAndMetadata(@Param("id") Long id, @Param("status") String status, @Param("metadata") String metadata);

    /**
     * 将全部运行中对话标记为失败（启动清理用）
     *
     * @return 影响行数
     */
    int markAllRunningAsFailed();
}
