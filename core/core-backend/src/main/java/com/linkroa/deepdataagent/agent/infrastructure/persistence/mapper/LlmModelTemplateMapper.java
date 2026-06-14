package com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.LlmModelTemplateEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 预置模型模板 Mapper
 */
@Mapper
public interface LlmModelTemplateMapper extends BaseMapper<LlmModelTemplateEntity> {

    List<LlmModelTemplateEntity> selectEnabledTemplates();

    LlmModelTemplateEntity selectById(@Param("id") Long id);
}
