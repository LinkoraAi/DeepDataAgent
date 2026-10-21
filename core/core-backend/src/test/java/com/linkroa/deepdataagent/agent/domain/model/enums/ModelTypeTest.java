package com.linkroa.deepdataagent.agent.domain.model.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModelType} 业务码表单元测试：四值解析与非法码值拒绝。
 */
class ModelTypeTest {

    @Test
    void should_returnChatLlm_when_fromCode_given_code1() {
        // given // when
        ModelType type = ModelType.fromCode(1);

        // then
        assertEquals(ModelType.CHAT_LLM, type);
        assertEquals("对话LLM", type.getLabel());
    }

    @Test
    void should_returnChatVlm_when_fromCode_given_code2() {
        // given // when
        ModelType type = ModelType.fromCode(2);

        // then
        assertEquals(ModelType.CHAT_VLM, type);
        assertEquals("视觉对话", type.getLabel());
    }

    @Test
    void should_returnEmbedding_when_fromCode_given_code3() {
        // given // when
        ModelType type = ModelType.fromCode(3);

        // then
        assertEquals(ModelType.EMBEDDING, type);
        assertEquals("嵌入", type.getLabel());
    }

    @Test
    void should_returnRerank_when_fromCode_given_code4() {
        // given // when
        ModelType type = ModelType.fromCode(4);

        // then
        assertEquals(ModelType.RERANK, type);
        assertEquals("重排", type.getLabel());
    }

    @Test
    void should_throwIllegalArgument_when_fromCode_given_code0() {
        // given // when
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> ModelType.fromCode(0));

        // then
        assertTrue(ex.getMessage().contains("未知模型类型码值"));
        assertTrue(ex.getMessage().contains("0"));
    }

    @Test
    void should_throwIllegalArgument_when_fromCode_given_code5() {
        // given // when
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> ModelType.fromCode(5));

        // then
        assertTrue(ex.getMessage().contains("未知模型类型码值"));
        assertTrue(ex.getMessage().contains("5"));
    }

    @Test
    void should_keepCodeStable_when_readGetCode() {
        // given // when // then（码值为持久化与线格式口径，不得漂移）
        assertEquals(1, ModelType.CHAT_LLM.getCode());
        assertEquals(2, ModelType.CHAT_VLM.getCode());
        assertEquals(3, ModelType.EMBEDDING.getCode());
        assertEquals(4, ModelType.RERANK.getCode());
    }
}
