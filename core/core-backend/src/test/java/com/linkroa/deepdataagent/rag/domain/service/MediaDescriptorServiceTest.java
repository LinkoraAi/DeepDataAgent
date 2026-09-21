package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.knowledgebase.application.port.KbAssetStoragePort;
import com.linkroa.deepdataagent.rag.domain.enums.CacheType;
import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import com.linkroa.deepdataagent.rag.domain.model.MediaDescriptionVO;
import com.linkroa.deepdataagent.rag.domain.port.LlmCacheKeyProvider;
import com.linkroa.deepdataagent.rag.domain.port.LlmChatRequest;
import com.linkroa.deepdataagent.rag.domain.port.LlmChatResult;
import com.linkroa.deepdataagent.rag.domain.port.LlmClient;
import com.linkroa.deepdataagent.rag.domain.port.MultimodalConstraints;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link MediaDescriptorService} 单元测试（+ 组9「媒体描述携带原图」）。
 * <p>Mock LLM 对话端口与对象资产存储端口（Prompt 渲染经 {@code PromptCatalog} 静态门面实测，
 * 模板路由以发往 {@link LlmClient} 的最终提示词特征句断言），
 * 覆盖：四类块模板路由（含 with_context 变体与无图 fallback 系统模板）、
 * 成功响应字段映射与主实体类型固定小写、主实体名一次定形为「裸名 + 内容类型后缀」
 * （image/table/equation/generic，后缀取块类型而非模型自报类型，兜底命名链同样带后缀）、
 * LLM 异常/空响应确定性兜底、
 * thinking+围栏污染响应解析、纯文本响应保留、generic 块本地兜底不调 LLM、
 * 实体名派生链（派生裸名，产物统一带类型后缀；prompt 变量 entity_name 用裸名）
 * 与入参非法分支；以及真看图路径——有效引用携带单图多模态请求、
 * contentType 按对象键后缀推断、对象缺失/读取异常/空内容降级纯文本、超字节上限跳图、
 * 无引用零存储交互（行为与升级前基线一致）。全程离线。</p>
 * <p>口径说明：桶概念已退役，媒体引用仅 {@code mediaObjectKey} 一键，原图经
 * {@link KbAssetStoragePort#open(String)} 按对象键读取；旧「headObject 元数据 contentType 优先」
 * 能力已随存储元数据读取一并消失，contentType 纯按对象键扩展名推断，对应历史用例已按新语义收敛。</p>
 * <p>缓存命中回放不受图载荷影响由 {@code CachingLlmClient} 的键摘要契约保证（组8已覆盖），
 * 本测试类无缓存维度，仅断言发往 LLM 端口的请求构造形态；
 * 另覆盖缓存键出参（{@code cacheKeyRef}）——非空时写入 provider 计算值、
 * 为空时不发起任何键计算、provider 失败不改变描述的兜底语义，供落库后补登记媒体归属。</p>
 */
@ExtendWith(MockitoExtension.class)
class MediaDescriptorServiceTest {

    /** think 开标签（尖括号 unicode 转义） */
    private static final String THINK_OPEN = "\u003Cthinking\u003E";
    /** think 闭标签（尖括号 unicode 转义） */
    private static final String THINK_CLOSE = "\u003C/thinking\u003E";
    /** 测试知识库ID */
    private static final Long KB_ID = 100L;
    /** 测试模型引用 */
    private static final String PROFILE_ID = "vision-profile";
    /** 测试语言码 */
    private static final String LANG_ZH = "zh";
    /** 测试媒体引用对象键（.png 后缀，桶概念已退役） */
    private static final String MEDIA_OBJECT_KEY = "rag/100/9/images/sales.png";

    /** 测试缓存键（provider 桩返回值，断言键出参用） */
    private static final String CACHE_KEY = "0123456789abcdef0123456789abcdef";

    /** LLM 对话端口 Mock */
    @Mock
    private LlmClient llmClient;

    /** 对象资产存储端口 Mock（媒体原图读取端口，组9 新增依赖） */
    @Mock
    private KbAssetStoragePort kbAssetStoragePort;

    /** 缓存键只读计算端口 Mock（键出参断言用，返回固定键值） */
    @Mock
    private LlmCacheKeyProvider llmCacheKeyProvider;

    /** 被测服务（构造注入三个 Mock，Prompt 渲染走 {@code PromptCatalog} 静态门面实测） */
    @InjectMocks
    private MediaDescriptorService service;

    /**
     * 场景：图片块正常响应（entity_info 缺 entity_name、仅名义 img_path 无媒体引用）。
     * 预期：路由 vision_prompt； 模板跟随实际附图结果——无媒体引用即未真实附图，
     * 系统模板为 IMAGE_ANALYSIS_FALLBACK_SYSTEM；kbId/profile 透传，
     * 主实体类型固定小写 image，实体名为题注确定性派生裸名 + (image) 后缀定形，详描取模型返回值。
     */
    @Test
    void should_routeVisionTemplateAndFixType_when_describe_given_imageBlockSuccess() {
        // given
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(
                "{\"detailed_description\": \"柱状图显示销售额上升\", \"entity_info\": "
                        + "{\"entity_type\": \"Chart\", \"summary\": \"销售额上升\"}}", 5));
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "图占位", Map.of(
                MultimodalMetaKeys.META_KEY_IMG_PATH, "images/sales.png",
                MultimodalMetaKeys.META_KEY_IMAGE_CAPTION, "季度销售额柱状图"));

        // when
        MediaDescriptionVO desc = service.describe(KB_ID, PROFILE_ID, LANG_ZH, block);

        // then
        assertEquals("柱状图显示销售额上升", desc.detailedDescription());
        assertEquals("image", desc.entityType());
        assertEquals("季度销售额柱状图 (image)", desc.entityName());
        assertTrue(desc.llmGenerated());
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture());
        assertEquals(KB_ID, requestCaptor.getValue().kbId());
        assertEquals(PROFILE_ID, requestCaptor.getValue().modelProfileId());
        // 系统模板实测为无图 fallback 系（未真实附图）：以特征句断言路由
        assertTrue(requestCaptor.getValue().systemPrompt().contains("expert image analyst"),
                "图片块应路由图片分析系系统模板");
        assertTrue(requestCaptor.getValue().systemPrompt().contains("based on available information"),
                "无媒体引用未真实附图，应选 IMAGE_ANALYSIS_FALLBACK_SYSTEM 特征句");
        // 用户模板实测为 vision_prompt 无上下文变体
        assertTrue(requestCaptor.getValue().userPrompt().contains("Please analyze this image in detail"),
                "图片块应路由 vision_prompt 模板");
        assertFalse(requestCaptor.getValue().userPrompt().contains("Context from surrounding content"),
                "无邻近文本不应出现上下文注入段");
    }

    // ------------------------------------------------------------------ 缓存键出参（媒体归属延迟登记的前提）

    /**
     * 场景：媒体描述调用传入键出参引用（摄入管线采集描述缓存键的形态）。
     * 预期：出参被写入 provider 算出的键值，且 provider 收到的请求与发往 LLM 端口的请求同一实例；
     * 媒体路径的归因字段恒为空（描述期无分块主键）。
     */
    @Test
    void should_writeCacheKeyIntoRef_when_describe_given_cacheKeyRefProvided() {
        // given：provider 桩返回固定键；媒体块走纯文本形态
        when(llmCacheKeyProvider.cacheKeyOf(any(LlmChatRequest.class))).thenReturn(CACHE_KEY);
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(
                "{\"detailed_description\": \"d\", \"entity_info\": {\"summary\": \"s\"}}", 1));
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "占位", Map.of(
                MultimodalMetaKeys.META_KEY_IMG_PATH, "images/a.png"));
        AtomicReference<String> cacheKeyRef = new AtomicReference<>();

        // when
        service.describe(KB_ID, PROFILE_ID, LANG_ZH, block, cacheKeyRef, null);

        // then：出参即 provider 计算值；provider 与 LLM 端口消费同一请求实例
        assertEquals(CACHE_KEY, cacheKeyRef.get(), "键出参应被写入本次描述所用的缓存键");
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture());
        verify(llmCacheKeyProvider).cacheKeyOf(requestCaptor.getValue());
        assertNull(requestCaptor.getValue().attributionChunkId(), "媒体路径不携带归因字段");
        assertEquals(CacheType.EXTRACT, requestCaptor.getValue().cacheType());
    }

    /**
     * 场景：媒体描述携带原图（多模态形态）且传入键出参。
     * 预期：请求携带 1 图（图进键摘要）与出参键值采集互不干扰。
     */
    @Test
    void should_carryImageAndCacheKey_when_describe_given_mediaRefAndCacheKeyRef() {
        // given
        stubOpenMedia("png-bytes".getBytes(StandardCharsets.UTF_8));
        when(llmCacheKeyProvider.cacheKeyOf(any(LlmChatRequest.class))).thenReturn(CACHE_KEY);
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(
                "{\"detailed_description\": \"d\", \"entity_info\": {\"summary\": \"s\"}}", 1));
        AtomicReference<String> cacheKeyRef = new AtomicReference<>();

        // when
        service.describe(KB_ID, PROFILE_ID, LANG_ZH, imageBlockWithMediaRef(), cacheKeyRef, null);

        // then
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture());
        assertEquals(1, requestCaptor.getValue().images().size());
        assertEquals(CACHE_KEY, cacheKeyRef.get());
    }

    /**
     * 场景：媒体描述经不携带键出参的既有重载调用（调用点不采集键值）。
     * 预期：归因字段恒空、且不发起任何键计算（provider 零交互）。
     */
    @Test
    void should_notComputeCacheKey_when_describe_given_overloadWithoutCacheKeyRef() {
        // given
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(
                "{\"detailed_description\": \"d\", \"entity_info\": {\"summary\": \"s\"}}", 1));
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "占位", Map.of(
                MultimodalMetaKeys.META_KEY_IMG_PATH, "images/a.png"));

        // when
        service.describe(KB_ID, PROFILE_ID, LANG_ZH, block);

        // then
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture());
        assertNull(requestCaptor.getValue().attributionChunkId(),
                "媒体路径必须不携带归因字段");
        verifyNoInteractions(llmCacheKeyProvider);
    }

    /**
     * 场景：显式传入空键出参（调用点声明不采集键值，仅挂缓存命中计数器）。
     * 预期：不计算键、不抛异常，描述正常产出。
     */
    @Test
    void should_notComputeCacheKey_when_describe_given_nullCacheKeyRef() {
        // given
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(
                "{\"detailed_description\": \"d\", \"entity_info\": {\"summary\": \"s\"}}", 1));
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "占位", Map.of(
                MultimodalMetaKeys.META_KEY_IMG_PATH, "images/a.png"));
        AtomicInteger cacheHits = new AtomicInteger();

        // when
        MediaDescriptionVO desc = service.describe(KB_ID, PROFILE_ID, LANG_ZH, block, null, cacheHits);

        // then
        assertEquals("d", desc.detailedDescription());
        verifyNoInteractions(llmCacheKeyProvider);
    }

    /**
     * 场景：键计算依赖失败（模型引用不可解析等价形态：provider 抛运行时异常）。
     * 预期：不向上抛错——与 LLM 调用失败同一兜底口径（确定性兜底），出参保持未采集。
     */
    @Test
    void should_useDeterministicFallback_when_describe_given_cacheKeyProviderThrows() {
        // given
        when(llmCacheKeyProvider.cacheKeyOf(any(LlmChatRequest.class)))
                .thenThrow(new IllegalArgumentException("模型配置不存在: vision-profile"));
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "占位", Map.of(
                MultimodalMetaKeys.META_KEY_IMG_PATH, "images/a.png",
                MultimodalMetaKeys.META_KEY_IMAGE_CAPTION, "架构图"));
        AtomicReference<String> cacheKeyRef = new AtomicReference<>();

        // when
        MediaDescriptionVO desc = service.describe(KB_ID, PROFILE_ID, LANG_ZH, block, cacheKeyRef, null);

        // then：兜底产物照常返回，键出参保持未采集；LLM 端口未被触达（采集先于调用失败）
        assertFalse(desc.llmGenerated());
        assertEquals("架构图 (image)", desc.entityName());
        assertNull(cacheKeyRef.get(), "键计算失败时出参不得被写入");
        verifyNoInteractions(llmClient);
    }

    /**
     * 场景：图片块 meta 携带邻近文本注入键。
     * 预期：切换 with_context 变体并注入 context 变量，JSON 预置 entity_name 优先生效，
     * 定形名为识别裸名 + (image) 后缀。
     */
    @Test
    void should_useWithContextVariant_when_describe_given_neighborTextPresent() {
        // given
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(
                "{\"detailed_description\": \"d\", \"entity_info\": "
                        + "{\"entity_name\": \"预置实体名\", \"summary\": \"s\"}}", 1));
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "占位", Map.of(
                MultimodalMetaKeys.META_KEY_IMG_PATH, "images/a.png",
                MultimodalMetaKeys.META_KEY_NEIGHBOR_TEXT, "上文提到销售数据"));

        // when
        MediaDescriptionVO desc = service.describe(KB_ID, PROFILE_ID, LANG_ZH, block);

        // then
        assertEquals("预置实体名 (image)", desc.entityName());
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture());
        // 用户模板实测为 with_context 变体：上下文注入段存在且邻近文本已注入 {context}
        assertTrue(requestCaptor.getValue().userPrompt().contains("Context from surrounding content"),
                "邻近文本存在时应路由 vision_prompt_with_context 变体");
        assertTrue(requestCaptor.getValue().userPrompt().contains("上文提到销售数据"),
                "邻近文本应注入 {context} 占位符");
    }

    /**
     * 场景：表格块（含 table_body / 题注 / 截图路径 meta）。
     * 预期：路由 table_prompt + TABLE_ANALYSIS_SYSTEM，table_body 透传变量，类型固定 table。
     */
    @Test
    void should_routeTableTemplate_when_describe_given_tableBlock() {
        // given
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(
                "{\"detailed_description\": \"员工结构表\", \"entity_info\": {\"summary\": \"s\"}}", 2));
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_TABLE, "<table>html</table>", Map.of(
                MultimodalMetaKeys.META_KEY_TABLE_BODY, "<table>html</table>",
                MultimodalMetaKeys.META_KEY_TABLE_CAPTION, "员工统计表",
                MultimodalMetaKeys.META_KEY_IMG_PATH, "images/t1.png"));

        // when
        MediaDescriptionVO desc = service.describe(KB_ID, PROFILE_ID, LANG_ZH, block);

        // then
        assertEquals("table", desc.entityType());
        assertEquals("员工统计表 (table)", desc.entityName());
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture());
        // 用户模板实测为 table_prompt 且 table_body 透传；系统模板为表格分析系
        assertTrue(requestCaptor.getValue().userPrompt().contains("Body: <table>html</table>"),
                "table_body 应注入 {table_body} 占位符");
        assertTrue(requestCaptor.getValue().userPrompt().contains("Caption: 员工统计表"),
                "题注应注入 {table_caption} 占位符");
        assertTrue(requestCaptor.getValue().systemPrompt().contains("expert data analyst"),
                "表格块应路由 TABLE_ANALYSIS_SYSTEM 特征句");
    }

    /**
     * 场景：公式块 meta 未声明 format。
     * 预期：路由 equation_prompt + EQUATION_ANALYSIS_SYSTEM，equation_format 归一为默认 latex。
     */
    @Test
    void should_defaultLatexFormat_when_describe_given_equationWithoutFormat() {
        // given
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(
                "{\"detailed_description\": \"质能方程\", \"entity_info\": {\"summary\": \"s\"}}", 2));
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_EQUATION, "E=mc^2",
                Map.of(MultimodalMetaKeys.META_KEY_TEXT, "E=mc^2"));

        // when
        MediaDescriptionVO desc = service.describe(KB_ID, PROFILE_ID, LANG_ZH, block);

        // then
        assertEquals("equation", desc.entityType());
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture());
        // 用户模板实测为 equation_prompt：原文与默认格式 latex 均已注入；系统模板为公式分析系
        assertTrue(requestCaptor.getValue().userPrompt().contains("Equation: E=mc^2"),
                "equation_text 应注入 {equation_text} 占位符");
        assertTrue(requestCaptor.getValue().userPrompt().contains("Format: latex"),
                "未声明 format 时 equation_format 应归一为默认 latex");
        assertTrue(requestCaptor.getValue().systemPrompt().contains("expert mathematician"),
                "公式块应路由 EQUATION_ANALYSIS_SYSTEM 特征句");
    }

    /**
     * 场景：图片块 meta 无任何图片路径（纯文本端口无法真实看图）。
     * 预期：系统提示切换 IMAGE_ANALYSIS_FALLBACK_SYSTEM。
     */
    @Test
    void should_useImageFallbackSystem_when_describe_given_imageWithoutPath() {
        // given
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(
                "{\"detailed_description\": \"d\", \"entity_info\": {\"summary\": \"s\"}}", 1));
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "占位",
                Map.of(MultimodalMetaKeys.META_KEY_IMAGE_CAPTION, "架构示意图"));

        // when
        service.describe(KB_ID, PROFILE_ID, LANG_ZH, block);

        // then：系统提示词实测为无图 fallback 系特征句
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture());
        assertTrue(requestCaptor.getValue().systemPrompt().contains("based on available information"),
                "无图片路径应切换 IMAGE_ANALYSIS_FALLBACK_SYSTEM");
    }

    /**
     * 场景：LLM 调用抛出运行时异常。
     * 预期：不向上抛错，走确定性兜底（llmGenerated=false，详描取题注优先信息）。
     */
    @Test
    void should_useDeterministicFallback_when_describe_given_llmThrows() {
        // given
        when(llmClient.chat(any(LlmChatRequest.class))).thenThrow(new RuntimeException("连接超时"));
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "占位", Map.of(
                MultimodalMetaKeys.META_KEY_IMG_PATH, "images/a.png",
                MultimodalMetaKeys.META_KEY_IMAGE_CAPTION, "架构图"));

        // when
        MediaDescriptionVO desc = service.describe(KB_ID, PROFILE_ID, LANG_ZH, block);

        // then
        assertFalse(desc.llmGenerated());
        assertEquals("image", desc.entityType());
        // 异常兜底链命名同样带内容类型后缀
        assertEquals("架构图 (image)", desc.entityName());
        assertEquals("架构图", desc.detailedDescription());
    }

    /**
     * 场景：LLM 返回空文本。
     * 预期：走确定性兜底，详描退化为图片路径（无题注时），实体名取路径文件名。
     */
    @Test
    void should_useDeterministicFallback_when_describe_given_emptyResponse() {
        // given
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult("", 0));
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "占位",
                Map.of(MultimodalMetaKeys.META_KEY_IMG_PATH, "images/a.png"));

        // when
        MediaDescriptionVO desc = service.describe(KB_ID, PROFILE_ID, LANG_ZH, block);

        // then
        assertFalse(desc.llmGenerated());
        assertEquals("a.png (image)", desc.entityName());
        assertEquals("images/a.png", desc.detailedDescription());
        assertEquals("", desc.summary());
    }

    /**
     * 场景：LLM 响应同时含 thinking 块与 json 代码围栏（真实模型常见污染）。
     * 预期：容错解析链剥离污染后成功映射字段，标记 llmGenerated=true。
     */
    @Test
    void should_parseSuccessfully_when_describe_given_thinkingAndFenceResponse() {
        // given
        String dirty = THINK_OPEN + "\n思考：这是销售图 {草稿}\n" + THINK_CLOSE
                + "\n```json\n{\"detailed_description\": \"带围栏描述\", \"entity_info\": {\"summary\": \"带围栏摘要\"}}\n```";
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(dirty, 3));
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "占位",
                Map.of(MultimodalMetaKeys.META_KEY_IMG_PATH, "images/a.png"));

        // when
        MediaDescriptionVO desc = service.describe(KB_ID, PROFILE_ID, LANG_ZH, block);

        // then
        assertTrue(desc.llmGenerated());
        assertEquals("带围栏描述", desc.detailedDescription());
        assertEquals("带围栏摘要", desc.summary());
    }

    /**
     * 场景：LLM 返回无 JSON 结构但非空的纯文本描述（解析链全失败）。
     * 预期：整段文本保留为详描（llmGenerated=true），摘要为空串，兜底派生裸名仍带类型后缀。
     */
    @Test
    void should_keepRawTextAsDetail_when_describe_given_unparseableProse() {
        // given
        when(llmClient.chat(any(LlmChatRequest.class)))
                .thenReturn(new LlmChatResult("这是一段没有任何花括号的纯文本描述", 2));
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_TABLE, "占位",
                Map.of(MultimodalMetaKeys.META_KEY_TABLE_CAPTION, "月度报表"));

        // when
        MediaDescriptionVO desc = service.describe(KB_ID, PROFILE_ID, LANG_ZH, block);

        // then
        assertTrue(desc.llmGenerated());
        assertEquals("这是一段没有任何花括号的纯文本描述", desc.detailedDescription());
        assertEquals("", desc.summary());
        assertEquals("月度报表 (table)", desc.entityName());
    }

    /**
     * 场景：泛型（GENERIC）内容块。
     * 预期：完全不调用 LLM，本地兜底描述（详描即块原文，llmGenerated=false）。
     */
    @Test
    void should_skipLlm_when_describe_given_genericBlock() {
        // given
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_GENERIC, "泛型块原文", Map.of());

        // when
        MediaDescriptionVO desc = service.describe(KB_ID, PROFILE_ID, LANG_ZH, block);

        // then
        verifyNoInteractions(llmClient);
        assertFalse(desc.llmGenerated());
        assertEquals("generic", desc.entityType());
        assertEquals("泛型块原文", desc.detailedDescription());
    }

    /**
     * 场景：泛型块 meta 无任何可派生信息、仅带页码。
     * 预期：实体名裸名为「类型_p页码」格式（page 为整数 3），产物定形后再拼 (generic) 后缀。
     */
    @Test
    void should_deriveTypePageName_when_describe_given_genericWithPageOnly() {
        // given
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_GENERIC, "内容",
                Map.of(MultimodalMetaKeys.META_KEY_PAGE, 3));

        // when
        MediaDescriptionVO desc = service.describe(KB_ID, PROFILE_ID, LANG_ZH, block);

        // then
        assertEquals("generic_p3 (generic)", desc.entityName());
    }

    /**
     * 场景：泛型块仅带超长题注（80 字）。
     * 预期：名称截断在后缀拼接之前完成并为后缀留位——裸名截断至 60 - 后缀长度（50，含省略号），
     * 再拼接 (generic) 后缀定形，最终名恰为 60 且类型后缀不因截断丢失。
     */
    @Test
    void should_abbreviateCaption_when_describe_given_longCaptionOnly() {
        // given
        String longCaption = "标".repeat(80);
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_GENERIC, "内容",
                Map.of(MultimodalMetaKeys.META_KEY_IMAGE_CAPTION, longCaption));

        // when
        MediaDescriptionVO desc = service.describe(KB_ID, PROFILE_ID, LANG_ZH, block);

        // then：最终名不超 60（裸名截到 50 含省略号 + 10 字符后缀），类型后缀完整保留
        assertEquals(60, desc.entityName().length());
        assertTrue(desc.entityName().startsWith("标".repeat(47)));
        assertTrue(desc.entityName().endsWith("... (generic)"));
    }

    /**
     * 场景：图片块模型识别出主实体名（模型自报 entity_type 为 Chart）。
     * 预期：主实体名定形为「识别裸名 + (image) 后缀」——后缀取块类型而非模型自报类型；
     * 主实体类型固定小写 image。
     */
    @Test
    void should_appendImageSuffix_when_describe_given_imageBlockRecognizedName() {
        // given
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(
                "{\"detailed_description\": \"d\", \"entity_info\": {\"entity_name\": \"核心实体A\","
                        + " \"entity_type\": \"Chart\", \"summary\": \"s\"}}", 1));
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "占位",
                Map.of(MultimodalMetaKeys.META_KEY_IMG_PATH, "images/a.png"));

        // when
        MediaDescriptionVO desc = service.describe(KB_ID, PROFILE_ID, LANG_ZH, block);

        // then：后缀为 (image) 而非模型自报的 Chart
        assertEquals("核心实体A (image)", desc.entityName());
        assertEquals("image", desc.entityType());
    }

    /**
     * 场景：表格块模型识别出主实体名。
     * 预期：主实体名定形为「识别裸名 + (table) 后缀」，主实体类型固定小写 table。
     */
    @Test
    void should_appendTableSuffix_when_describe_given_tableBlockRecognizedName() {
        // given
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(
                "{\"detailed_description\": \"d\", \"entity_info\": {\"entity_name\": \"核心实体A\","
                        + " \"entity_type\": \"Chart\", \"summary\": \"s\"}}", 1));
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_TABLE, "<table>html</table>",
                Map.of(MultimodalMetaKeys.META_KEY_TABLE_BODY, "<table>html</table>"));

        // when
        MediaDescriptionVO desc = service.describe(KB_ID, PROFILE_ID, LANG_ZH, block);

        // then
        assertEquals("核心实体A (table)", desc.entityName());
        assertEquals("table", desc.entityType());
    }

    /**
     * 场景：公式块模型识别出主实体名。
     * 预期：主实体名定形为「识别裸名 + (equation) 后缀」，主实体类型固定小写 equation。
     */
    @Test
    void should_appendEquationSuffix_when_describe_given_equationBlockRecognizedName() {
        // given
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(
                "{\"detailed_description\": \"d\", \"entity_info\": {\"entity_name\": \"核心实体A\","
                        + " \"entity_type\": \"Chart\", \"summary\": \"s\"}}", 1));
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_EQUATION, "E=mc^2",
                Map.of(MultimodalMetaKeys.META_KEY_TEXT, "E=mc^2"));

        // when
        MediaDescriptionVO desc = service.describe(KB_ID, PROFILE_ID, LANG_ZH, block);

        // then
        assertEquals("核心实体A (equation)", desc.entityName());
        assertEquals("equation", desc.entityType());
    }

    /**
     * 场景：泛型块 meta 预置主实体裸名（不调 LLM 的本地兜底路径）。
     * 预期：兜底产物命名同样定形为「裸名 + (generic) 后缀」。
     */
    @Test
    void should_appendGenericSuffix_when_describe_given_genericBlock() {
        // given
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_GENERIC, "内容",
                Map.of(MultimodalMetaKeys.META_KEY_ENTITY_NAME, "核心实体A"));

        // when
        MediaDescriptionVO desc = service.describe(KB_ID, PROFILE_ID, LANG_ZH, block);

        // then
        verifyNoInteractions(llmClient);
        assertEquals("核心实体A (generic)", desc.entityName());
        assertEquals("generic", desc.entityType());
    }

    /**
     * 场景：模型响应无 JSON 结构（容错解析链全失败），命名走解析失败兜底链。
     * 预期：整段文本保留为详描的同时，兜底裸名仍带内容类型后缀定形。
     */
    @Test
    void should_keepSuffix_when_describe_given_modelResponseUnparsable() {
        // given
        when(llmClient.chat(any(LlmChatRequest.class)))
                .thenReturn(new LlmChatResult("纯文本响应没有任何 JSON 结构", 2));
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_TABLE, "占位",
                Map.of(MultimodalMetaKeys.META_KEY_ENTITY_NAME, "预算明细表"));

        // when
        MediaDescriptionVO desc = service.describe(KB_ID, PROFILE_ID, LANG_ZH, block);

        // then
        assertTrue(desc.llmGenerated());
        assertEquals("纯文本响应没有任何 JSON 结构", desc.detailedDescription());
        assertEquals("预算明细表 (table)", desc.entityName());
    }

    /**
     * 场景：模型识别的裸名超长（80 字符，超过 ENTITY_NAME_MAX_LENGTH=60）。
     * 预期：截断先为后缀留位——裸名截断至 60 - 后缀长度后拼接完整 " (image)"，
     * 最终名长度不超 60 且后缀不因截断丢失。
     */
    @Test
    void should_reserveSuffixSpace_when_describe_given_overlongName() {
        // given
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(
                "{\"detailed_description\": \"d\", \"entity_info\": {\"entity_name\": "
                        + "\"" + "N".repeat(80) + "\", \"summary\": \"s\"}}", 1));
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "占位",
                Map.of(MultimodalMetaKeys.META_KEY_IMG_PATH, "images/a.png"));

        // when
        MediaDescriptionVO desc = service.describe(KB_ID, PROFILE_ID, LANG_ZH, block);

        // then：裸名截断为 49 个 N + 省略号（共 52），拼 8 字符后缀恰为 60，后缀完整
        assertEquals("N".repeat(49) + "..." + " (image)", desc.entityName());
        assertEquals(60, desc.entityName().length());
        assertTrue(desc.entityName().endsWith(" (image)"));
    }

    /**
     * 场景：图片块 meta 仅有图片路径，其余模板变量来源缺失。
     * 预期：所有渲染变量归一为非空白值（缺失项为 N/A），实测进入最终用户提示词，
     * 满足 {@code PromptCatalog.render} fail-fast 契约。
     */
    @Test
    void should_normalizeMissingVars_when_describe_given_sparseMeta() {
        // given
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(
                "{\"detailed_description\": \"d\", \"entity_info\": {\"summary\": \"s\"}}", 1));
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "占位",
                Map.of(MultimodalMetaKeys.META_KEY_IMG_PATH, "images/a.png"));

        // when
        service.describe(KB_ID, PROFILE_ID, LANG_ZH, block);

        // then：缺失变量的 N/A 归一值与派生实体名/图片路径均已渲染进用户提示词
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture());
        String userPrompt = requestCaptor.getValue().userPrompt();
        assertTrue(userPrompt.contains("Section Path: N/A"), "缺失 section_path 应归一为 N/A");
        assertTrue(userPrompt.contains("Captions: N/A"), "缺失 captions 应归一为 N/A");
        assertTrue(userPrompt.contains("Footnotes: N/A"), "缺失 footnotes 应归一为 N/A");
        assertTrue(userPrompt.contains("\"entity_name\": \"a.png\""), "实体名应派生为图片文件名");
        assertFalse(userPrompt.contains("a.png (image)"), "prompt 变量 entity_name 应为裸名，不带类型后缀");
        assertTrue(userPrompt.contains("Image Path: images/a.png"), "图片路径应原样注入");
    }

    /**
     * 场景：文本块与非多模态未知类型块。
     * 预期：拒绝描述生成，抛 IllegalArgumentException。
     */
    @Test
    void should_throwIae_when_describe_given_nonMultimodalType() {
        // given
        ContentBlockVO text = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "正文", Map.of());
        ContentBlockVO unknown = new ContentBlockVO("CHART", "未知类型", Map.of());

        // when / then
        assertThrows(IllegalArgumentException.class, () -> service.describe(KB_ID, PROFILE_ID, LANG_ZH, text));
        assertThrows(IllegalArgumentException.class, () -> service.describe(KB_ID, PROFILE_ID, LANG_ZH, unknown));
    }

    /**
     * 场景：入参非法（kbId 空 / profile 空白 / 语言空白 / 块空）。
     * 预期：均抛 IllegalArgumentException 且不触达任何依赖。
     */
    @Test
    void should_throwIae_when_describe_given_invalidArguments() {
        // given
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "占位", Map.of());

        // when / then
        assertThrows(IllegalArgumentException.class, () -> service.describe(null, PROFILE_ID, LANG_ZH, block));
        assertThrows(IllegalArgumentException.class, () -> service.describe(KB_ID, " ", LANG_ZH, block));
        assertThrows(IllegalArgumentException.class, () -> service.describe(KB_ID, PROFILE_ID, "", block));
        assertThrows(IllegalArgumentException.class, () -> service.describe(KB_ID, PROFILE_ID, LANG_ZH, null));
        verifyNoInteractions(llmClient);
        verifyNoInteractions(kbAssetStoragePort);
    }

    /**
     * 场景：图片块 meta 携带可解析原图引用（mediaObjectKey，桶概念已退役），对象存储读取成功。
     * 预期：发往 LlmClient 的请求为多模态形态——images 恰含 1 图、字节与原图一致、
     * contentType 按对象键后缀推断（.png → image/png）。
     */
    @Test
    void should_attachSingleImageWithExtensionContentType_when_describe_given_validMediaRef() {
        // given
        byte[] imageBytes = "fake-png-bytes".getBytes(StandardCharsets.UTF_8);
        stubOpenMedia(imageBytes);
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(
                "{\"detailed_description\": \"图内转写文字：Q3 营收 1.2 亿\", \"entity_info\": {\"summary\": \"s\"}}", 1));
        ContentBlockVO block = imageBlockWithMediaRef();

        // when
        MediaDescriptionVO desc = service.describe(KB_ID, PROFILE_ID, LANG_ZH, block);

        // then
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture());
        LlmChatRequest request = requestCaptor.getValue();
        assertEquals(1, request.images().size());
        assertEquals("image/png", request.images().get(0).contentType());
        assertArrayEquals(imageBytes, request.images().get(0).content());
        assertEquals("图内转写文字：Q3 营收 1.2 亿", desc.detailedDescription());
        assertTrue(desc.llmGenerated());
        verify(kbAssetStoragePort).open(MEDIA_OBJECT_KEY);
    }

    /**
     * 场景：有效引用但对象存储中对象缺失（open 返回 empty）。
     * 预期：按「读取失败」降级为纯文本请求（images 为空），不抛错、描述正常产出。
     */
    @Test
    void should_sendTextOnlyRequest_when_describe_given_mediaObjectMissing() {
        // given
        when(kbAssetStoragePort.open(MEDIA_OBJECT_KEY)).thenReturn(Optional.empty());
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(
                "{\"detailed_description\": \"d\", \"entity_info\": {\"summary\": \"s\"}}", 1));
        ContentBlockVO block = imageBlockWithMediaRef();

        // when
        MediaDescriptionVO desc = service.describe(KB_ID, PROFILE_ID, LANG_ZH, block);

        // then
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture());
        assertTrue(requestCaptor.getValue().images().isEmpty());
        assertTrue(desc.llmGenerated());
        verify(kbAssetStoragePort).open(MEDIA_OBJECT_KEY);
    }

    /**
     * 场景：图片块携带引用但对象读取抛异常（存储故障）。
     * 预期：降级分类「读取失败」——回落纯文本请求（images 为空），LLM 仍收到文本请求并正常产出描述。
     */
    @Test
    void should_sendTextOnlyRequest_when_describe_given_mediaRefReadThrows() {
        // given
        when(kbAssetStoragePort.open(MEDIA_OBJECT_KEY))
                .thenThrow(new RuntimeException("存储故障: 503"));
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(
                "{\"detailed_description\": \"题注级描述\", \"entity_info\": {\"summary\": \"s\"}}", 1));
        ContentBlockVO block = imageBlockWithMediaRef();

        // when
        MediaDescriptionVO desc = service.describe(KB_ID, PROFILE_ID, LANG_ZH, block);

        // then
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture());
        assertTrue(requestCaptor.getValue().images().isEmpty());
        assertTrue(desc.llmGenerated());
        assertEquals("题注级描述", desc.detailedDescription());
        verify(kbAssetStoragePort).open(MEDIA_OBJECT_KEY);
    }

    /**
     * 场景：图片块携带引用但对象内容为空（读到 0 字节）。
     * 预期：按「读取失败」降级为纯文本请求，不抛错、描述正常产出。
     */
    @Test
    void should_sendTextOnlyRequest_when_describe_given_mediaRefEmptyContent() {
        // given
        stubOpenMedia(new byte[0]);
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(
                "{\"detailed_description\": \"d\", \"entity_info\": {\"summary\": \"s\"}}", 1));
        ContentBlockVO block = imageBlockWithMediaRef();

        // when
        MediaDescriptionVO desc = service.describe(KB_ID, PROFILE_ID, LANG_ZH, block);

        // then
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture());
        assertTrue(requestCaptor.getValue().images().isEmpty());
        assertTrue(desc.llmGenerated());
    }

    /**
     * 场景：引用对象读取成功但字节超单图上限（MultimodalConstraints.MAX_IMAGE_BYTES）。
     * 预期：降级分类「超限」——跳过该图回落纯文本请求（实现内输出 WARN；本套件无日志 appender，
     * 以请求形态断言降级生效）。
     */
    @Test
    void should_skipOverLimitImageAndStayTextOnly_when_describe_given_imageExceedsByteLimit() {
        // given
        byte[] oversized = new byte[(int) MultimodalConstraints.MAX_IMAGE_BYTES + 1];
        stubOpenMedia(oversized);
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(
                "{\"detailed_description\": \"d\", \"entity_info\": {\"summary\": \"s\"}}", 1));
        ContentBlockVO block = imageBlockWithMediaRef();

        // when
        service.describe(KB_ID, PROFILE_ID, LANG_ZH, block);

        // then
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture());
        assertTrue(requestCaptor.getValue().images().isEmpty());
    }

    /**
     * 场景：图片块无媒体引用键（存量数据或 Stage 1a 持久化失败），仅有 img_path。
     * 预期：零存储交互（不读对象），纯文本请求，行为与升级前基线完全一致。
     */
    @Test
    void should_notTouchStorageAndSendTextOnly_when_describe_given_noMediaRef() {
        // given
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(
                "{\"detailed_description\": \"d\", \"entity_info\": {\"summary\": \"s\"}}", 1));
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "占位", Map.of(
                MultimodalMetaKeys.META_KEY_IMG_PATH, "images/sales.png",
                MultimodalMetaKeys.META_KEY_IMAGE_CAPTION, "季度销售额柱状图"));

        // when
        service.describe(KB_ID, PROFILE_ID, LANG_ZH, block);

        // then
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture());
        assertTrue(requestCaptor.getValue().images().isEmpty());
        verifyNoInteractions(kbAssetStoragePort);
    }

    /**
     * 场景：图片块媒体引用可解析且读取成功。
     * 预期：系统模板选「原图解读」系 IMAGE_ANALYSIS_SYSTEM，且请求为携带 1 图的多模态形态
     * （模板选择与实际附图结果一致）。
     */
    @Test
    void should_useImageAnalysisTemplate_when_describe_given_imageResolved() {
        // given
        byte[] imageBytes = "png-bytes".getBytes(StandardCharsets.UTF_8);
        stubOpenMedia(imageBytes);
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(
                "{\"detailed_description\": \"d\", \"entity_info\": {\"summary\": \"s\"}}", 1));

        // when
        service.describe(KB_ID, PROFILE_ID, LANG_ZH, imageBlockWithMediaRef());

        // then：真实附图 → 系统提示词实测为「原图解读」系特征句（非 fallback 系）
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture());
        assertEquals(1, requestCaptor.getValue().images().size());
        assertTrue(requestCaptor.getValue().systemPrompt().contains("Provide detailed, accurate descriptions"),
                "有效引用走真看图应选 IMAGE_ANALYSIS_SYSTEM 特征句");
        assertFalse(requestCaptor.getValue().systemPrompt().contains("based on available information"),
                "真看图形态不应出现无图 fallback 系措辞");
    }

    /**
     * 场景：名义 img_path 存在、
     * 媒体引用对象读取抛异常。
     * 预期：回落纯文本请求且系统模板同步为无图降级系（不出现「有图可看」承诺与无图载荷错配）。
     */
    @Test
    void should_useFallbackTemplate_when_describe_given_referenceReadFailed() {
        // given
        when(kbAssetStoragePort.open(MEDIA_OBJECT_KEY))
                .thenThrow(new RuntimeException("存储故障: 503"));
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(
                "{\"detailed_description\": \"d\", \"entity_info\": {\"summary\": \"s\"}}", 1));

        // when
        service.describe(KB_ID, PROFILE_ID, LANG_ZH, imageBlockWithMediaRef());

        // then：读取失败降级 → 纯文本请求且系统提示词同步为无图 fallback 系特征句
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture());
        assertTrue(requestCaptor.getValue().images().isEmpty());
        assertTrue(requestCaptor.getValue().systemPrompt().contains("based on available information"),
                "降级后应同步选 IMAGE_ANALYSIS_FALLBACK_SYSTEM 特征句");
    }

    /**
     * 场景：引用对象字节超单图上限。
     * 预期：降级为纯文本请求 + 无图 fallback 系模板；且超限图仅消费「上限 + 1」字节
     * （有界读取判超限即停，不再全量缓冲入堆）。
     */
    @Test
    void should_useFallbackTemplate_when_describe_given_oversizedImage() {
        // given：声明总长「上限 + 1024」字节的计数流，实际消费应封顶「上限 + 1」
        long totalBytes = MultimodalConstraints.MAX_IMAGE_BYTES + 1024L;
        CountingInputStream countingStream = new CountingInputStream(totalBytes);
        when(kbAssetStoragePort.open(MEDIA_OBJECT_KEY)).thenReturn(Optional.of(
                new KbAssetStoragePort.OpenedObject(countingStream, totalBytes)));
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(
                "{\"detailed_description\": \"d\", \"entity_info\": {\"summary\": \"s\"}}", 1));

        // when
        service.describe(KB_ID, PROFILE_ID, LANG_ZH, imageBlockWithMediaRef());

        // then
        assertEquals(MultimodalConstraints.MAX_IMAGE_BYTES + 1, countingStream.getConsumed(),
                "超限图至多读取上限 + 1 字节即判定，不整堆缓冲");
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture());
        assertTrue(requestCaptor.getValue().images().isEmpty());
        assertTrue(requestCaptor.getValue().systemPrompt().contains("based on available information"),
                "超限降级应同步选无图 fallback 系特征句");
    }

    /**
     * 字节消费计数输入流（有界读取断言专用）：不物化真实大数组，仅按声明总长返回 0 字节，
     * 记录 read()/read(byte[],int,int) 实际消费数，用于断言读取封顶口径。
     */
    private static final class CountingInputStream extends InputStream {

        /** 流的声明总长 */
        private final long totalBytes;

        /** 已消费字节数 */
        private long consumed;

        private CountingInputStream(long totalBytes) {
            this.totalBytes = totalBytes;
        }

        /**
         * @return 已消费字节数
         */
        private long getConsumed() {
            return consumed;
        }

        @Override
        public int read() {
            if (consumed >= totalBytes) {
                return -1;
            }
            consumed++;
            return 0;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (len == 0) {
                return 0;
            }
            if (consumed >= totalBytes) {
                return -1;
            }
            int available = (int) Math.min(len, totalBytes - consumed);
            Arrays.fill(b, off, off + available, (byte) 0);
            consumed += available;
            return available;
        }
    }

    /**
     * stub 媒体原图对象读取成功（内容流 + 精确字节数）。
     *
     * @param imageBytes 图片字节
     */
    private void stubOpenMedia(byte[] imageBytes) {
        when(kbAssetStoragePort.open(MEDIA_OBJECT_KEY)).thenReturn(Optional.of(
                new KbAssetStoragePort.OpenedObject(new ByteArrayInputStream(imageBytes), imageBytes.length)));
    }

    /**
     * 构造携带媒体引用键的图片块（img_path + mediaObjectKey，桶概念已退役）。
     *
     * @return 图片内容块
     */
    private ContentBlockVO imageBlockWithMediaRef() {
        return new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "图占位", Map.of(
                MultimodalMetaKeys.META_KEY_IMG_PATH, "images/sales.png",
                MultimodalMetaKeys.META_KEY_IMAGE_CAPTION, "季度销售额柱状图",
                MultimodalMetaKeys.META_KEY_MEDIA_OBJECT_KEY, MEDIA_OBJECT_KEY));
    }

}
