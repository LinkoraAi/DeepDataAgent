package com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.ConversationMsgEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 会话消息 Mapper
 */
@Mapper
public interface ConversationMsgMapper extends BaseMapper<ConversationMsgEntity> {

    /**
     * 按会话 ID 分页查询消息（按 ID 升序）
     */
    List<ConversationMsgEntity> selectBySessionIdPaged(@Param("sessionId") String sessionId,
                                                        @Param("limit") int limit,
                                                        @Param("offset") int offset);

    /**
     * 按会话 ID 查询最近 N 条消息（按 ID 降序）
     */
    List<ConversationMsgEntity> selectRecentBySessionId(@Param("sessionId") String sessionId,
                                                         @Param("limit") int limit);

    /**
     * 统计会话消息数量
     */
    int countBySessionId(@Param("sessionId") String sessionId);
}
