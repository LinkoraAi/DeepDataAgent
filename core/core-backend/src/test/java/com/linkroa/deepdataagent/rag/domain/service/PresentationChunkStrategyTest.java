package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.rag.domain.model.ChunkVO;
import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PresentationChunkStrategy} 演示稿分块页码透出单元测试。
 * <p>锁定语义：①每页 1 chunk 且不消费 token 预算（超长页同样整块透传）；②正文前置形如
 * {@code [第N页]} 的页码标记，页码优先块 meta 真实页码（{@code page} 与别名 {@code page_idx}）；
 * ③meta 缺失或非法（占位 0、非数字）时降级为块在文档内的页序，纯文本块与多模态块共用同一
 * 页序计数器，混排时标记连续且全局序号连续；④策略不变量——空正文不丢块、空文档返回空列表而非 null。</p>
 * <p>依赖 TokenCounter 以 Mockito 模拟并按「长度即 token」确定性打桩，便于逐字符断言正文与计数口径。</p>
 */
@ExtendWith(MockitoExtension.class)
class PresentationChunkStrategyTest {

    /** 模拟的真实 token 计数端口（打桩为长度口径）。 */
    @Mock
    private TokenCounter tokenCounter;

    /** 被测策略（无字段依赖，Mockito 直接实例化）。 */
    @InjectMocks
    private PresentationChunkStrategy presentationChunkStrategy;

    /**
     * 场景：MinerU 轨文本块 meta 含真实页码 page=7。
     * 预期：产出 1 chunk，正文以 {@code [第7页] } 前置且保留原页正文；token 按拼接后正文真实计数一次。
     */
    @Test
    void should_prefixMetaPageMarker_when_split_given_blockWithMetaPageSeven() {
        // given
        stubLengthBasedCounting();
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "季度营收概览",
                Map.of(MultimodalMetaKeys.META_KEY_PAGE, 7));

        // when
        List<ChunkVO> chunks = presentationChunkStrategy.split(block, ChunkParams.defaults(), tokenCounter);

        // then
        assertEquals(1, chunks.size(), "每页应恰好产出 1 chunk");
        assertEquals("[第7页] 季度营收概览", chunks.get(0).text(), "meta 真实页码应进正文且值为 7");
        assertEquals(1, chunks.get(0).sequence().intValue());
        assertSame(block, chunks.get(0).block(), "来源块引用应保留供回源");
        verify(tokenCounter).count("[第7页] 季度营收概览");
    }

    /**
     * 场景：块仅有别名键 page_idx=9（部分 MinerU 版本输出形态）。
     * 预期：别名回退生效，正文页码标记值为 9。
     */
    @Test
    void should_prefixMetaPageMarker_when_split_given_blockWithPageIdxAliasOnly() {
        // given
        stubLengthBasedCounting();
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "风险提示",
                Map.of(MultimodalMetaKeys.META_KEY_PAGE_IDX, 9));

        // when
        List<ChunkVO> chunks = presentationChunkStrategy.split(block, ChunkParams.defaults(), tokenCounter);

        // then
        assertEquals(1, chunks.size());
        assertEquals("[第9页] 风险提示", chunks.get(0).text(), "page_idx 别名应被识别为真实页码");
    }

    /**
     * 场景：单块入口无全局页序上下文，且块 meta 无任何页码键（meta 为 null）。
     * 预期：不硬造页码，按首页兜底标记为 {@code [第1页] }，仍恰好 1 chunk。
     */
    @Test
    void should_fallbackToFirstPageMarker_when_split_given_blockWithoutAnyPageMeta() {
        // given
        stubLengthBasedCounting();
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "封面页", null);

        // when
        List<ChunkVO> chunks = presentationChunkStrategy.split(block, ChunkParams.defaults(), tokenCounter);

        // then
        assertEquals(1, chunks.size());
        assertEquals("[第1页] 封面页", chunks.get(0).text(), "单块入口缺页码时应按首页兜底");
    }

    /**
     * 场景：块 meta 页码为非数字脏值 {@code "abc"}（参数非法）。
     * 预期：解析失败按缺页码处理，单块入口兜底首页标记，不抛异常也不丢块。
     */
    @Test
    void should_fallbackToFirstPageMarker_when_split_given_blockWithNonNumericPageMeta() {
        // given
        stubLengthBasedCounting();
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "脏数据页",
                Map.of(MultimodalMetaKeys.META_KEY_PAGE, "abc"));

        // when
        List<ChunkVO> chunks = presentationChunkStrategy.split(block, ChunkParams.defaults(), tokenCounter);

        // then
        assertEquals(1, chunks.size(), "非法页码不得导致丢块");
        assertEquals("[第1页] 脏数据页", chunks.get(0).text());
    }

    /**
     * 场景：Tika 轨三页文本块均无 page meta（meta 为空 Map）。
     * 预期：文档级页序兜底——第 1/2/3 块标记依次为 {@code [第1页]}、{@code [第2页]}、{@code [第3页]}，
     * 全局序号同步连续；每块真实计数一次。
     */
    @Test
    void should_fallbackToBlockPageOrder_when_splitDocument_given_textBlocksWithoutPageMeta() {
        // given
        stubLengthBasedCounting();
        List<ContentBlockVO> blocks = List.of(
                new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "第一页正文", Map.of()),
                new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "第二页正文", Map.of()),
                new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "第三页正文", Map.of()));

        // when
        List<ChunkVO> chunks = presentationChunkStrategy.splitDocument(blocks, ChunkParams.defaults(), tokenCounter);

        // then
        assertEquals(3, chunks.size(), "三页应产出 3 chunk");
        assertEquals("[第1页] 第一页正文", chunks.get(0).text());
        assertEquals("[第2页] 第二页正文", chunks.get(1).text(), "缺页码时页序应递增兜底");
        assertEquals("[第3页] 第三页正文", chunks.get(2).text());
        assertEquals(1, chunks.get(0).sequence().intValue());
        assertEquals(3, chunks.get(2).sequence().intValue(), "全局序号应连续递增");
        verify(tokenCounter, times(3)).count(anyString());
    }

    /**
     * 场景：Tika 轨文本块统一携带占位页码 {@code page=0}（解析层占位，非真实页码）。
     * 预期：占位 0 视为缺页码走页序兜底，两页标记为 {@code [第1页]}、{@code [第2页]}。
     */
    @Test
    void should_fallbackToBlockPageOrder_when_splitDocument_given_placeholderZeroPageMeta() {
        // given
        stubLengthBasedCounting();
        List<ContentBlockVO> blocks = List.of(
                new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "占位零页甲",
                        Map.of(MultimodalMetaKeys.META_KEY_PAGE, 0)),
                new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "占位零页乙",
                        Map.of(MultimodalMetaKeys.META_KEY_PAGE, 0)));

        // when
        List<ChunkVO> chunks = presentationChunkStrategy.splitDocument(blocks, ChunkParams.defaults(), tokenCounter);

        // then
        assertEquals(2, chunks.size());
        assertEquals("[第1页] 占位零页甲", chunks.get(0).text(), "占位 0 不得作为真实页码透出");
        assertEquals("[第2页] 占位零页乙", chunks.get(1).text());
    }

    /**
     * 场景：纯文本页与多模态页混排（TEXT 无 meta、IMAGE meta page=12、TABLE/EQUATION 无 meta）。
     * 预期：四类块共用同一页序计数器——标记为第1/12/3/4页（meta 优先、缺失页序兜底），
     * 全局序号 1..4 连续且每块正文均含页码标记，来源块类型与引用逐块保持。
     */
    @Test
    void should_keepContinuousPageMarkers_when_splitDocument_given_mixedTextAndMultimodalBlocks() {
        // given
        stubLengthBasedCounting();
        ContentBlockVO text = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "开场白", Map.of());
        ContentBlockVO image = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "<image>img1.jpeg</image>",
                Map.of(MultimodalMetaKeys.META_KEY_PAGE, 12));
        ContentBlockVO table = new ContentBlockVO(ContentBlockVO.TYPE_TABLE, "<table>营收表</table>", Map.of());
        ContentBlockVO equation = new ContentBlockVO(ContentBlockVO.TYPE_EQUATION, "$E=mc^2$", Map.of());

        // when
        List<ChunkVO> chunks = presentationChunkStrategy.splitDocument(List.of(text, image, table, equation),
                ChunkParams.defaults(), tokenCounter);

        // then
        assertEquals(4, chunks.size(), "混排文档应逐块 1 chunk");
        assertEquals("[第1页] 开场白", chunks.get(0).text());
        assertEquals("[第12页] <image>img1.jpeg</image>", chunks.get(1).text(), "多模态块 meta 页码优先");
        assertEquals("[第3页] <table>营收表</table>", chunks.get(2).text(), "混排序号应跨块类型连续");
        assertEquals("[第4页] $E=mc^2$", chunks.get(3).text());
        for (int i = 0; i < chunks.size(); i++) {
            ChunkVO chunk = chunks.get(i);
            assertEquals(i + 1, chunk.sequence().intValue(), "全局序号必须连续");
            assertTrue(chunk.text().contains("页] "), "每块正文都须含页码标记");
        }
        assertSame(image, chunks.get(1).block(), "多模态块引用应保留供后续多模态管线识别");
        assertEquals(ContentBlockVO.TYPE_TABLE, chunks.get(2).block().type());
    }

    /**
     * 场景：单页正文远超默认 token 预算（长度口径 1024 tokens > 512），两页成一篇文档。
     * 预期：策略不消费 token 预算——每页仍整块 1 chunk（不被切碎、不互相合并），token 按整块真实计数。
     */
    @Test
    void should_keepOneChunkPerPageIgnoringTokenBudget_when_splitDocument_given_overSizedPages() {
        // given
        stubLengthBasedCounting();
        int budget = ChunkParams.defaults().chunkTokenNum();
        String longPage = "页".repeat(budget * 2);
        List<ContentBlockVO> blocks = List.of(
                new ContentBlockVO(ContentBlockVO.TYPE_TEXT, longPage, Map.of()),
                new ContentBlockVO(ContentBlockVO.TYPE_TEXT, longPage, Map.of()));

        // when
        List<ChunkVO> chunks = presentationChunkStrategy.splitDocument(blocks, ChunkParams.defaults(), tokenCounter);

        // then
        assertEquals(2, chunks.size(), "超预算页不得被切碎或合并");
        assertTrue(chunks.get(0).tokens().intValue() > budget, "预算不参与切分，token 数允许超预算");
        assertEquals(longPage.length() + "[第1页] ".length(), chunks.get(0).tokens().intValue());
        assertTrue(chunks.get(1).text().startsWith("[第2页] "));
    }

    /**
     * 场景：块正文为空串（依赖 meta 页码定位的空页）。
     * 预期：不因空正文丢块（策略不变量），仍产出 1 chunk，正文仅含页码标记。
     */
    @Test
    void should_returnMarkedChunk_when_split_given_blankBlockText() {
        // given
        stubLengthBasedCounting();
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "",
                Map.of(MultimodalMetaKeys.META_KEY_PAGE, 3));

        // when
        List<ChunkVO> chunks = presentationChunkStrategy.split(block, ChunkParams.defaults(), tokenCounter);

        // then
        assertEquals(1, chunks.size(), "空正文页不得被丢弃");
        assertTrue(chunks.get(0).text().startsWith("[第3页]"), "正文应保留页码标记");
    }

    /**
     * 场景：文档级入口传入空块列表与 null 块列表（边界）。
     * 预期：两次均返回非 null 空列表（无页可切），不调用 token 计数端口。
     */
    @Test
    void should_returnEmptyList_when_splitDocument_given_emptyBlocks() {
        // when
        List<ChunkVO> emptyBlocks = presentationChunkStrategy.splitDocument(List.of(), ChunkParams.defaults(),
                tokenCounter);
        List<ChunkVO> nullBlocks = presentationChunkStrategy.splitDocument(null, ChunkParams.defaults(),
                tokenCounter);

        // then
        assertNotNull(emptyBlocks, "文档级入口不得返回 null");
        assertTrue(emptyBlocks.isEmpty());
        assertNotNull(nullBlocks, "文档级入口不得返回 null");
        assertTrue(nullBlocks.isEmpty());
    }

    /**
     * 场景：策略支持面判别。
     * 预期：TEXT 块受支持，IMAGE/TABLE 等多模态块不由本策略的 {@code supports} 认领
     *（文档级页码标记由 {@code splitDocument} 统一覆盖）。
     */
    @Test
    void should_supportOnlyTextBlock_when_supports_given_textAndImageBlocks() {
        // given
        ContentBlockVO text = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "文本页", Map.of());
        ContentBlockVO image = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "<image>a.png</image>", Map.of());

        // when & then
        assertTrue(presentationChunkStrategy.supports(text));
        assertFalse(presentationChunkStrategy.supports(image));
    }

    /**
     * 场景：注册表键。
     * 预期：{@code method()} 返回常量 {@code presentation}。
     */
    @Test
    void should_returnPresentationMethod_when_method_given_anyInvocation() {
        // when & then
        assertEquals("presentation", presentationChunkStrategy.method());
    }

    /**
     * 打桩 token 计数为「长度即 token」确定性口径。
     */
    private void stubLengthBasedCounting() {
        when(tokenCounter.count(anyString())).thenAnswer(invocation -> ((String) invocation.getArgument(0)).length());
    }
}
