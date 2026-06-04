package com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.LlmModelConfigEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户模型配置 Mapper
 */
@Mapper
public interface LlmModelConfigMapper extends BaseMapper<LlmModelConfigEntity> {

    LlmModelConfigEntity selectByIdAndNotDeleted(@Param("id") Long id);

    List<LlmModelConfigEntity> selectAllNotDeleted();

    LlmModelConfigEntity selectDefaultModel();

    int cancelDefaultForAll(@Param("updatedAt") String updatedAt);

    /**
     * 根据模板优先级选择下一个默认模型
     * 按模板 sort_order 升序，相同优先级按创建时间降序
     */
    LlmModelConfigEntity selectNextDefaultModel();
}
