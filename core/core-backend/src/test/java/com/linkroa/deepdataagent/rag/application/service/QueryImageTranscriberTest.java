package com.linkroa.deepdataagent.rag.application.service;

import com.linkroa.deepdataagent.knowledgebase.api.KnowledgeBaseApi;
import com.linkroa.deepdataagent.rag.domain.enums.CacheType;
import com.linkroa.deepdataagent.rag.domain.port.LlmChatRequest;
import com.linkroa.deepdataagent.rag.domain.port.LlmChatResult;
import com.linkroa.deepdataagent.rag.domain.port.LlmClient;
import com.linkroa.deepdataagent.rag.domain.port.LlmImage;
import com.linkroa.deepdataagent.rag.domain.service.RetrievalConstants;
import com.linkroa.deepdataagent.rag.infrastructure.prompts.catalog.PromptCatalog;
import com.linkroa.deepdataagent.rag.infrastructure.prompts.catalog.PromptTemplates;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link QueryImageTranscriber} 单元测试（通路 A）。
 * <p>渲染已切换为静态 {@link PromptCatalog}，本测试不再
 * mock 渲染端口：期望提示词常量在类加载期以目录真实模板（Chinese 语言、空变量表）渲染求得，
 * 对下游 chat 请求的 systemPrompt/userPrompt 做全文全等断言。
 * 覆盖：① 无附件零 LLM 调用原样返回；② 库未配置 VLM 回退原文；③ 单图成功产出增强查询
 * 与请求要素（缓存分区/图片载荷/提示词）；④ 转译抛异常回退原文；⑤ 全部返回空回退原文；
 * ⑥ 多图部分失败仅并入成功部分；⑦ 库语言缺省回落全局默认（回落失效将落入英文模板套、
 * 产物全文不等而被捕获）。缓存命中语义由 {@code CachingLlmClient} 自身单测保证，
 * 不在此重复覆盖。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class QueryImageTranscriberTest {

    /** 测试知识库ID */
    private static final Long KB_ID = 7L;

    /** 用户原始问题 */
    private static final String QUERY = "这个报错截图要怎么处理";

    /** 库级多模态模型 profileId */
    private static final String VLM_PROFILE = "vlm-profile-1";

    /** 知识库语言全名 */
    private static final String LANGUAGE = "Chinese";

    /** 渲染后的图片分析系统提示词（与被测类同口径：Chinese + 空变量表） */
    private static final String SYSTEM_PROMPT =
            PromptCatalog.render(PromptTemplates.QUERY_IMAGE_ANALYST_SYSTEM, LANGUAGE, Map.of());

    /** 渲染后的图片描述用户提示词（与被测类同口径：Chinese + 空变量表） */
    private static final String USER_PROMPT =
            PromptCatalog.render(PromptTemplates.QUERY_IMAGE_DESCRIPTION, LANGUAGE, Map.of());

    /** 渲染并裁剪后的尾部指导句（被测类对渲染产物做 trim，模板正文首尾无换行） */
    private static final String SUFFIX = StringUtils.trim(
            PromptCatalog.render(PromptTemplates.QUERY_ENHANCEMENT_SUFFIX, LANGUAGE, Map.of()));

    /** 增强查询前半段（原问题 + 段落空行 + 附图描述标签） */
    private static final String ENHANCED_PREFIX = QUERY + "\n\n附图描述：";

    /** 第一张附图（PNG 头魔数 + 载荷） */
    private static final LlmImage IMAGE_ONE =
            new LlmImage("image/png", new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x01});

    /** 第二张附图（JPEG 头魔数 + 载荷） */
    private static final LlmImage IMAGE_TWO =
            new LlmImage("image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x02});

    /** 单图描述产物（桩值） */
    private static final String DESCRIPTION_ONE = "图中是一个订单导出失败的弹窗提示";

    /** 多图描述产物（桩值） */
    private static final String DESCRIPTION_TWO = "图中是导出按钮所在的工具栏截图";

    @Mock
    private LlmClient llmClient;

    @Mock
    private KnowledgeBaseApi knowledgeBaseApi;

    @InjectMocks
    private QueryImageTranscriber queryImageTranscriber;

    /**
     * 打桩库级 VLM 配置与语言（转译前置读取项）。
     */
    private void stubKbVlmConfig() {
        when(knowledgeBaseApi.findMediaModelProfileIdByKbId(KB_ID)).thenReturn(VLM_PROFILE);
        when(knowledgeBaseApi.findLanguageByKbId(KB_ID)).thenReturn(LANGUAGE);
    }

    /**
     * 打桩图片转译 LLM 响应。
     *
     * @param description 描述文本
     */
    private void stubChatText(String description) {
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(description, 12));
    }

    @Test
    void should_returnOriginalQuery_when_transcribe_given_noAttachments() {
        // given // when
        String enhanced = queryImageTranscriber.transcribe(KB_ID, QUERY, List.of());

        // then：零 LLM 调用、零配置读取，原样返回
        assertEquals(QUERY, enhanced);
        verifyNoInteractions(llmClient, knowledgeBaseApi);
    }

    @Test
    void should_returnOriginalQuery_when_transcribe_given_nullAttachments() {
        // given // when
        String enhanced = queryImageTranscriber.transcribe(KB_ID, QUERY, null);

        // then
        assertEquals(QUERY, enhanced);
        verifyNoInteractions(llmClient, knowledgeBaseApi);
    }

    @Test
    void should_returnOriginalQuery_when_transcribe_given_vlmProfileNotConfigured() {
        // given：库未配置多模态模型（空白视同未配置）
        when(knowledgeBaseApi.findMediaModelProfileIdByKbId(KB_ID)).thenReturn("   ");

        // when
        String enhanced = queryImageTranscriber.transcribe(KB_ID, QUERY, List.of(IMAGE_ONE));

        // then：未配置回退分类，不调模型、不读取库语言
        assertEquals(QUERY, enhanced);
        verifyNoInteractions(llmClient);
        verify(knowledgeBaseApi, never()).findLanguageByKbId(KB_ID);
    }

    @Test
    void should_returnOriginalQuery_when_transcribe_given_mediaProfileReadFails() {
        // given：库配置读取抛异常属增强路径故障，不得中断检索主流程
        when(knowledgeBaseApi.findMediaModelProfileIdByKbId(KB_ID))
                .thenThrow(new IllegalStateException("多模态模型配置 JSON 损坏"));

        // when
        String enhanced = queryImageTranscriber.transcribe(KB_ID, QUERY, List.of(IMAGE_ONE));

        // then
        assertEquals(QUERY, enhanced);
        verifyNoInteractions(llmClient);
    }

    @Test
    void should_returnEnhancedQueryAndMultimodalRequest_when_transcribe_given_singleImageSuccess() {
        // given
        stubKbVlmConfig();
        stubChatText(DESCRIPTION_ONE);

        // when
        String enhanced = queryImageTranscriber.transcribe(KB_ID, QUERY, List.of(IMAGE_ONE));

        // then：增强查询 = 原问题 + 附图描述段 + 尾部指导句
        assertEquals(ENHANCED_PREFIX + DESCRIPTION_ONE + "\n\n" + SUFFIX, enhanced);
        ArgumentCaptor<LlmChatRequest> captor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(captor.capture());
        LlmChatRequest request = captor.getValue();
        assertEquals(KB_ID, request.kbId());
        assertEquals(VLM_PROFILE, request.modelProfileId());
        assertEquals(SYSTEM_PROMPT, request.systemPrompt());
        assertEquals(USER_PROMPT, request.userPrompt());
        assertEquals(CacheType.QUERY_IMAGE_TRANSCRIBE, request.cacheType());
        assertEquals(1, request.images().size());
        assertEquals(IMAGE_ONE, request.images().get(0));
    }

    @Test
    void should_trimSuffixAndKeepParagraphBreak_when_transcribe_given_suffixWithSurroundingBlankLines() {
        // given：模板正文首尾无换行，段落换行由拼接方显式给出
        stubKbVlmConfig();
        stubChatText(DESCRIPTION_ONE);

        // when
        String enhanced = queryImageTranscriber.transcribe(KB_ID, QUERY, List.of(IMAGE_ONE));

        // then：尾部仅保留一个段落分隔空行，且不含模板自带的首尾空白
        assertEquals(ENHANCED_PREFIX + DESCRIPTION_ONE + "\n\n" + SUFFIX, enhanced);
        assertTrue(enhanced.endsWith(SUFFIX));
    }

    @Test
    void should_returnOriginalQuery_when_transcribe_given_chatThrows() {
        // given：唯一一张图转译调用抛异常
        stubKbVlmConfig();
        when(llmClient.chat(any(LlmChatRequest.class))).thenThrow(new RuntimeException("模型网关 502"));

        // when
        String enhanced = queryImageTranscriber.transcribe(KB_ID, QUERY, List.of(IMAGE_ONE));

        // then：全部失败 → 回退原文（不追加附图描述段与尾部指导句）
        assertEquals(QUERY, enhanced);
    }

    @Test
    void should_returnOriginalQuery_when_transcribe_given_allImagesReturnBlankText() {
        // given：两张图均返回空白描述
        stubKbVlmConfig();
        stubChatText("   ");

        // when
        String enhanced = queryImageTranscriber.transcribe(KB_ID, QUERY, List.of(IMAGE_ONE, IMAGE_TWO));

        // then
        assertEquals(QUERY, enhanced);
        verify(llmClient, times(2)).chat(any(LlmChatRequest.class));
    }

    @Test
    void should_enhanceWithSucceededDescriptionsOnly_when_transcribe_given_partialImageFailure() {
        // given：第一张成功、第二张抛异常
        stubKbVlmConfig();
        when(llmClient.chat(any(LlmChatRequest.class)))
                .thenReturn(new LlmChatResult(DESCRIPTION_ONE, 10))
                .thenThrow(new RuntimeException("第二张图超时"));

        // when
        String enhanced = queryImageTranscriber.transcribe(KB_ID, QUERY, List.of(IMAGE_ONE, IMAGE_TWO));

        // then：成功部分并入，失败图不产生占位描述
        assertEquals(ENHANCED_PREFIX + DESCRIPTION_ONE + "\n\n" + SUFFIX, enhanced);
        verify(llmClient, times(2)).chat(any(LlmChatRequest.class));
    }

    @Test
    void should_joinDescriptionsWithSeparator_when_transcribe_given_multipleImagesSucceed() {
        // given
        stubKbVlmConfig();
        when(llmClient.chat(any(LlmChatRequest.class)))
                .thenReturn(new LlmChatResult(DESCRIPTION_ONE, 10))
                .thenReturn(new LlmChatResult(DESCRIPTION_TWO, 11));

        // when
        String enhanced = queryImageTranscriber.transcribe(KB_ID, QUERY, List.of(IMAGE_ONE, IMAGE_TWO));

        // then：多描述以中文分号连接，顺序与附图一致
        assertEquals(ENHANCED_PREFIX + DESCRIPTION_ONE + "；" + DESCRIPTION_TWO + "\n\n" + SUFFIX, enhanced);
    }

    @Test
    void should_renderWithDefaultLanguage_when_transcribe_given_kbLanguageNotConfigured() {
        // given：库未配置语言 → 回落全局兜底语言全名（回落失效将落入英文模板套、产物全文不等）
        when(knowledgeBaseApi.findMediaModelProfileIdByKbId(KB_ID)).thenReturn(VLM_PROFILE);
        when(knowledgeBaseApi.findLanguageByKbId(KB_ID)).thenReturn(null);
        stubChatText(DESCRIPTION_ONE);

        // when
        String enhanced = queryImageTranscriber.transcribe(KB_ID, QUERY, List.of(IMAGE_ONE));

        // then：与 RetrievalConstants.DEFAULT_LANGUAGE（Chinese）口径的期望渲染全文一致
        assertEquals(RetrievalConstants.DEFAULT_LANGUAGE, LANGUAGE);
        assertEquals(ENHANCED_PREFIX + DESCRIPTION_ONE + "\n\n" + SUFFIX, enhanced);
        ArgumentCaptor<LlmChatRequest> captor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(captor.capture());
        assertEquals(SYSTEM_PROMPT, captor.getValue().systemPrompt());
        assertEquals(USER_PROMPT, captor.getValue().userPrompt());
    }
}
