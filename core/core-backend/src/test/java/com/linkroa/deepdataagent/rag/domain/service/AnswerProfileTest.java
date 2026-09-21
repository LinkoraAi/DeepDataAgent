package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.rag.infrastructure.prompts.catalog.PromptTemplates;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AnswerProfile} 单元测试。
 *
 * <p>本值对象是「MIX/NAIVE 模板形态」的唯一供给点（Stage 4 上下文骨架 + Stage 5 回答系统模板
 * 与默认变量），故断言聚焦「形态 → 模板名/内容槽」映射不错位、默认变量取值与修正前逐字一致、
 * 变量表不可变。全程离线，不启动 Spring 容器。</p>
 *
 * <p>覆盖场景：① {@code kgMode=true} 映射图谱侧模板对；② {@code kgMode=false} 映射朴素侧模板对；
 * ③ 两形态回答模板名互不相同（换模式必换模板）；④ 默认变量表内容与取值（response_type /
 * user_prompt）；⑤ 内容槽变量名随形态切换且取值同源；⑥ 内容槽入参 null 归空串；
 * ⑦ 模板名/内容槽空白快速失败；⑧ 默认变量表防御性不可变拷贝。</p>
 *
 * @author DeepDataAgent
 */
class AnswerProfileTest {

    /** 测试上下文正文 */
    private static final String CONTEXT_DATA = "CTX";

    @Test
    void should_returnKgTemplates_when_forKgMode_given_true() {
        // given // when
        AnswerProfile profile = AnswerProfile.forKgMode(true);

        // then：MIX 形态 = kg_query_context 骨架 + rag_response 回答模板 + context_data 内容槽
        assertSame(AnswerProfile.KG, profile);
        assertEquals(PromptTemplates.KG_QUERY_CONTEXT, profile.contextTemplateName());
        assertEquals(PromptTemplates.RAG_RESPONSE, profile.responseTemplateName());
        assertEquals("context_data", profile.contentVarName());
    }

    @Test
    void should_returnNaiveTemplates_when_forKgMode_given_false() {
        // given // when
        AnswerProfile profile = AnswerProfile.forKgMode(false);

        // then：NAIVE 形态 = naive_query_context 骨架 + naive_rag_response 回答模板 + content_data 内容槽
        assertSame(AnswerProfile.NAIVE, profile);
        assertEquals(PromptTemplates.NAIVE_QUERY_CONTEXT, profile.contextTemplateName());
        assertEquals(PromptTemplates.NAIVE_RAG_RESPONSE, profile.responseTemplateName());
        assertEquals("content_data", profile.contentVarName());
    }

    @Test
    void should_switchBothTemplates_when_forKgMode_given_modeChanged() {
        // given // when
        AnswerProfile kg = AnswerProfile.forKgMode(true);
        AnswerProfile naive = AnswerProfile.forKgMode(false);

        // then：骨架与回答模板均随形态切换（Stage 4 预算扣减的模板与 Stage 5 实际送入模板不会错配）
        assertFalse(kg.contextTemplateName().equals(naive.contextTemplateName()));
        assertFalse(kg.responseTemplateName().equals(naive.responseTemplateName()));
        assertFalse(kg.contentVarName().equals(naive.contentVarName()));
    }

    @Test
    void should_carryHistoricalDefaultVars_when_bothProfiles_given_anyMode() {
        // given // when
        Map<String, String> kgVars = AnswerProfile.KG.defaultResponseVars();
        Map<String, String> naiveVars = AnswerProfile.NAIVE.defaultResponseVars();

        // then：默认变量两形态同值，且取值与收编前 DefaultAnswerGenerator 常量逐字一致
        assertEquals(AnswerProfile.DEFAULT_RESPONSE_TYPE, kgVars.get(AnswerProfile.VAR_RESPONSE_TYPE));
        assertEquals("Multiple Paragraphs", kgVars.get(AnswerProfile.VAR_RESPONSE_TYPE));
        assertEquals(AnswerProfile.DEFAULT_USER_PROMPT_ABSENT, kgVars.get(AnswerProfile.VAR_USER_PROMPT));
        assertEquals("n/a", kgVars.get(AnswerProfile.VAR_USER_PROMPT));
        assertEquals(kgVars, naiveVars);
        // then：默认变量表不含内容槽（内容由 responseVars 单独注入，避免预算空算误计正文）
        assertFalse(kgVars.containsKey(AnswerProfile.KG.contentVarName()));
    }

    @Test
    void should_placeContextIntoContentSlot_when_responseVars_given_eachMode() {
        // given // when
        Map<String, String> kgVars = AnswerProfile.KG.responseVars(CONTEXT_DATA);
        Map<String, String> naiveVars = AnswerProfile.NAIVE.responseVars(CONTEXT_DATA);

        // then：默认变量与内容槽齐备，槽名随形态切换、取值同源
        assertEquals(3, kgVars.size());
        assertEquals(CONTEXT_DATA, kgVars.get("context_data"));
        assertEquals(CONTEXT_DATA, naiveVars.get("content_data"));
    }

    @Test
    void should_normalizeNullContextToEmpty_when_responseVars_given_nullContent() {
        // given // when
        Map<String, String> vars = AnswerProfile.KG.responseVars(null);

        // then：内容槽 null 归空串（预算空算即取此形态）
        assertEquals("", vars.get("context_data"));
    }

    @Test
    void should_throwIllegalArgumentException_when_newAnswerProfile_given_blankNames() {
        // given
        Map<String, String> vars = Map.of(AnswerProfile.VAR_RESPONSE_TYPE, AnswerProfile.DEFAULT_RESPONSE_TYPE);

        // when & then：模板名与内容槽名均为渲染必需项，空白即拒绝
        assertThrows(IllegalArgumentException.class,
                () -> new AnswerProfile(" ", PromptTemplates.RAG_RESPONSE, "context_data", vars));
        assertThrows(IllegalArgumentException.class,
                () -> new AnswerProfile(PromptTemplates.KG_QUERY_CONTEXT, " ", "context_data", vars));
        assertThrows(IllegalArgumentException.class,
                () -> new AnswerProfile(PromptTemplates.KG_QUERY_CONTEXT, PromptTemplates.RAG_RESPONSE, " ", vars));
    }

    @Test
    void should_copyDefensivelyAndAllowEmptyVars_when_newAnswerProfile_given_mutableOrNullVars() {
        // given：可变来源表（构造后被外部修改）
        Map<String, String> mutable = new HashMap<>();
        mutable.put(AnswerProfile.VAR_RESPONSE_TYPE, AnswerProfile.DEFAULT_RESPONSE_TYPE);

        // when
        AnswerProfile profile = new AnswerProfile(PromptTemplates.KG_QUERY_CONTEXT,
                PromptTemplates.RAG_RESPONSE, "context_data", mutable);
        mutable.put("injected", "x");
        AnswerProfile emptyVars = new AnswerProfile(PromptTemplates.NAIVE_QUERY_CONTEXT,
                PromptTemplates.NAIVE_RAG_RESPONSE, "content_data", null);

        // then：值对象不随外部修改漂移，且为空变量表归一为空表
        assertEquals(1, profile.defaultResponseVars().size());
        assertTrue(profile.defaultResponseVars().containsKey(AnswerProfile.VAR_RESPONSE_TYPE));
        assertThrows(UnsupportedOperationException.class,
                () -> profile.defaultResponseVars().put("another", "y"));
        assertTrue(emptyVars.defaultResponseVars().isEmpty());
    }
}
