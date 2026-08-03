package com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.DialogueEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 对话轮次 Mapper
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

    @Select("SELECT messages FROM dialogue WHERE id = #{id}")
    String selectMessages(@Param("id") Long id);

    @Update("UPDATE dialogue SET messages = #{messages} WHERE id = #{id}")
    int updateMessages(@Param("id") Long id, @Param("messages") String messages);

    @Update("UPDATE dialogue SET status = #{status}, messages = #{messages}, end_time = datetime('now') WHERE id = #{id}")
    int updateMessagesAndStatus(@Param("id") Long id, @Param("messages") String messages, @Param("status") String status);

    @Update("UPDATE dialogue SET status = #{status}, end_time = datetime('now') WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    @Update("UPDATE dialogue SET status = #{status}, metadata = #{metadata}, end_time = datetime('now') WHERE id = #{id}")
    int updateStatusAndMetadata(@Param("id") Long id, @Param("status") String status, @Param("metadata") String metadata);

    @Update("UPDATE dialogue SET status = 'FAILED', end_time = datetime('now') WHERE status = 'RUNNING' AND is_deleted = 0")
    int markAllRunningAsFailed();
}
