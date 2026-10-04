package com.linkroa.deepdataagent.agent.controller.convert;

import com.linkroa.deepdataagent.agent.controller.response.ModelProfileResponse;
import com.linkroa.deepdataagent.agent.domain.model.ModelProfile;
import com.linkroa.deepdataagent.agent.domain.model.enums.ApiFormat;
import com.linkroa.deepdataagent.agent.domain.model.enums.ModelType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ModelProfileResponseConvert} 模型类型业务码映射单测（集成测试 D5）。
 * <p>校验 MapStruct 默认的 enum→Integer（ordinal）映射被显式业务码映射覆盖：
 * {@code CHAT→1} / {@code EMBEDDING→2}。嵌入型配置若按 ordinal 取值为 1，会与请求契约
 * 「1=对话 2=嵌入」错位，导致嵌入型配置回显错误。</p>
 * <p>使用 MapStruct 生成的 {@code ModelProfileResponseConvert.INSTANCE} 实例，断言真实生成的映射行为。</p>
 */
class ModelProfileResponseConvertTest {

    /**
     * 契约：1=对话（CHAT）/ 2=向量嵌入（EMBEDDING）；入参为空时返回 null。
     */
    @Test
    void should_returnCode_when_toModelTypeCode_given_embeddingAndChat() {
        // given
        ModelProfileResponseConvert convert = ModelProfileResponseConvert.INSTANCE;

        // when & then
        assertEquals(Integer.valueOf(2), convert.toModelTypeCode(ModelType.EMBEDDING));
        assertEquals(Integer.valueOf(1), convert.toModelTypeCode(ModelType.CHAT));
        assertNull(convert.toModelTypeCode(null));
    }

    /**
     * 嵌入型配置经 {@code toResponse} 转换后 {@code modelType} 必须为业务码 2，而非 ordinal 1。
     */
    @Test
    void should_mapModelTypeCode_when_toResponse_given_embeddingProfile() {
        // given（EMBEDDING 需配置向量维度，其余字段满足领域不变量）
        ModelProfile profile = ModelProfile.create(
                "mp-embed", "嵌入模型", null, ApiFormat.OPENAI,
                "https://api.example.com/v1", "bge-m3", null, null,
                0, 0, 1, ModelType.EMBEDDING, 1024, 1L);

        // when
        ModelProfileResponse response = ModelProfileResponseConvert.INSTANCE.toResponse(profile);

        // then
        assertEquals(Integer.valueOf(2), response.modelType());
        assertEquals(Integer.valueOf(1024), response.vectorDimension());
    }
}