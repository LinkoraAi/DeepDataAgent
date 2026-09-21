package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.knowledgebase.application.port.KbAssetStoragePort;
import com.linkroa.deepdataagent.rag.domain.port.LlmImage;
import com.linkroa.deepdataagent.rag.domain.port.MultimodalConstraints;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AnswerImageResolver} 单元测试。
 *
 * <p>对象资产存储端口 {@link KbAssetStoragePort} Mockito 模拟，不触碰真实存储；被测解析器构造器直接 new。
 * 口径说明：桶概念已退役，原图读取统一经 {@link KbAssetStoragePort#open(String)} 按对象键定位，
 * 「对象不存在」由 Optional.empty 承载（跳过该图），读取异常由端口上抛触发跳过。
 * 覆盖场景：① 空引用零对象存储交互；② 去重（同 objectKey 只读一次）；③ 按上下文序取前 N（超上限截断）；
 * ④ 悬空引用（读抛异常）跳过该图；⑤ 超单图字节上限跳过该图；⑥ 全部无效返回空表；
 * ⑦ 内容类型按后缀推断（含未知后缀回落）；⑧ objectKey 空白的引用直接跳过、不读取；
 * ⑨ 超限对象有界读取——至多消费「上限 + 1」字节即判超限跳过，不整堆缓冲。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class AnswerImageResolverTest {

    /** 对象键 A（png） */
    private static final String KEY_A = "rag/1/10/images/a.png";

    /** 对象键 B（gif） */
    private static final String KEY_B = "rag/1/10/images/b.gif";

    /** 对象键 C（jpg，用于验证超出上限被截断） */
    private static final String KEY_C = "rag/1/10/images/c.jpg";

    /** 对象键 D（无扩展名，验证内容类型回落） */
    private static final String KEY_D = "rag/1/10/images/noext";

    /** 图片 A 字节 */
    private static final byte[] BYTES_A = "IMAGE-A".getBytes(StandardCharsets.UTF_8);

    /** 图片 B 字节 */
    private static final byte[] BYTES_B = "IMAGE-B".getBytes(StandardCharsets.UTF_8);

    /** 对象资产存储端口 Mock */
    @Mock
    private KbAssetStoragePort kbAssetStoragePort;

    /**
     * ① 空引用列表 → 返回空表且零对象存储交互。
     */
    @Test
    void should_returnEmptyListAndNoStorageRead_when_resolve_given_emptyReferences() {
        // given
        AnswerImageResolver resolver = new AnswerImageResolver(kbAssetStoragePort);

        // when
        List<LlmImage> images = resolver.resolve(List.of());

        // then
        assertTrue(images.isEmpty());
        verifyNoInteractions(kbAssetStoragePort);
    }

    /**
     * ② 同一 objectKey 重复出现 → 去重后只读一次，结果按首次出现序。
     */
    @Test
    void should_dedupAndKeepOrder_when_resolve_given_duplicateObjectKeys() {
        // given
        AnswerImageResolver resolver = new AnswerImageResolver(kbAssetStoragePort);
        stubRead(KEY_A, BYTES_A);
        stubRead(KEY_B, BYTES_B);
        List<AnswerImageResolver.MediaReference> references = List.of(
                new AnswerImageResolver.MediaReference(1L, KEY_A),
                new AnswerImageResolver.MediaReference(2L, KEY_A),
                new AnswerImageResolver.MediaReference(3L, KEY_B));

        // when
        List<LlmImage> images = resolver.resolve(references);

        // then
        assertEquals(2, images.size());
        assertArrayEquals(BYTES_A, images.get(0).content());
        assertArrayEquals(BYTES_B, images.get(1).content());
        verify(kbAssetStoragePort, times(1)).open(KEY_A);
        verify(kbAssetStoragePort, times(1)).open(KEY_B);
    }

    /**
     * ③ 有效引用数超过单次直读上限（2）→ 仅按上下文序读前 2 张，第 3 张不触碰存储。
     */
    @Test
    void should_truncateToLimitAndKeepFirst_when_resolve_given_moreThanMaxValidReferences() {
        // given
        AnswerImageResolver resolver = new AnswerImageResolver(kbAssetStoragePort);
        stubRead(KEY_A, BYTES_A);
        stubRead(KEY_B, BYTES_B);
        List<AnswerImageResolver.MediaReference> references = List.of(
                new AnswerImageResolver.MediaReference(1L, KEY_A),
                new AnswerImageResolver.MediaReference(2L, KEY_B),
                new AnswerImageResolver.MediaReference(3L, KEY_C));

        // when
        List<LlmImage> images = resolver.resolve(references);

        // then：只装载前 2 张，KEY_C 因截断不读存储
        assertEquals(2, images.size());
        assertArrayEquals(BYTES_A, images.get(0).content());
        assertArrayEquals(BYTES_B, images.get(1).content());
        verify(kbAssetStoragePort).open(KEY_A);
        verify(kbAssetStoragePort).open(KEY_B);
        verify(kbAssetStoragePort, never()).open(eq(KEY_C));
    }

    /**
     * ④ 悬空引用（读取抛异常）→ 跳过该图、其余继续，返回成功读取的图片。
     */
    @Test
    void should_skipFailedReadAndContinue_when_resolve_given_danglingObject() {
        // given
        AnswerImageResolver resolver = new AnswerImageResolver(kbAssetStoragePort);
        when(kbAssetStoragePort.open(eq(KEY_A))).thenThrow(new RuntimeException("对象不存在"));
        stubRead(KEY_B, BYTES_B);
        List<AnswerImageResolver.MediaReference> references = List.of(
                new AnswerImageResolver.MediaReference(1L, KEY_A),
                new AnswerImageResolver.MediaReference(2L, KEY_B));

        // when
        List<LlmImage> images = resolver.resolve(references);

        // then
        assertEquals(1, images.size());
        assertArrayEquals(BYTES_B, images.get(0).content());
    }

    /**
     * ⑤ 单图字节超过上限 → 跳过该图，正常图片仍装载。
     */
    @Test
    void should_skipOverSizedImage_when_resolve_given_bytesExceedMax() {
        // given
        AnswerImageResolver resolver = new AnswerImageResolver(kbAssetStoragePort);
        byte[] oversized = new byte[4 * 1024 * 1024 + 1];
        stubRead(KEY_A, oversized);
        stubRead(KEY_B, BYTES_B);
        List<AnswerImageResolver.MediaReference> references = List.of(
                new AnswerImageResolver.MediaReference(1L, KEY_A),
                new AnswerImageResolver.MediaReference(2L, KEY_B));

        // when
        List<LlmImage> images = resolver.resolve(references);

        // then：KEY_A 超限被跳过，仅 KEY_B 装载
        assertEquals(1, images.size());
        assertArrayEquals(BYTES_B, images.get(0).content());
    }

    /**
     * ⑥ 全部引用读取失败（对象不存在 → open 返回 empty）→ 返回空表（触发调用方回落纯文本）。
     */
    @Test
    void should_returnEmptyList_when_resolve_given_allObjectsMissing() {
        // given
        AnswerImageResolver resolver = new AnswerImageResolver(kbAssetStoragePort);
        when(kbAssetStoragePort.open(anyString())).thenReturn(Optional.empty());
        List<AnswerImageResolver.MediaReference> references = List.of(
                new AnswerImageResolver.MediaReference(1L, KEY_A),
                new AnswerImageResolver.MediaReference(2L, KEY_B));

        // when
        List<LlmImage> images = resolver.resolve(references);

        // then
        assertTrue(images.isEmpty());
    }

    /**
     * ⑦ 内容类型按对象键后缀推断：已知后缀映射 MIME，未知/无后缀回落二进制流。
     */
    @Test
    void should_inferContentTypeByExtension_when_resolve_given_knownAndUnknownExtensions() {
        // given
        AnswerImageResolver resolver = new AnswerImageResolver(kbAssetStoragePort);
        stubRead(KEY_A, BYTES_A);
        stubRead(KEY_D, BYTES_B);
        List<AnswerImageResolver.MediaReference> references = List.of(
                new AnswerImageResolver.MediaReference(1L, KEY_A),
                new AnswerImageResolver.MediaReference(2L, KEY_D));

        // when
        List<LlmImage> images = resolver.resolve(references);

        // then
        assertEquals("image/png", images.get(0).contentType());
        assertEquals("application/octet-stream", images.get(1).contentType());
    }

    /**
     * ⑧ objectKey 空白的引用无效 → 直接跳过、不发起任何读取。
     */
    @Test
    void should_skipBlankObjectKeyReference_when_resolve_given_blankObjectKey() {
        // given
        AnswerImageResolver resolver = new AnswerImageResolver(kbAssetStoragePort);

        // when
        List<LlmImage> images = resolver.resolve(List.of(
                new AnswerImageResolver.MediaReference(1L, "  ")));

        // then
        assertTrue(images.isEmpty());
        verifyNoInteractions(kbAssetStoragePort);
    }

    /**
     * ⑨：对象声明总长超单图上限 →
     * 有界读取至多消费「上限 + 1」字节即判超限跳过该图，完整对象不再缓冲入内存；
     * 正常图片（②~⑧ 用例 assertArrayEquals）字节完整性口径不变。
     */
    @Test
    void should_boundedReadAndSkipOversizedObject_when_resolve_given_objectExceedsMaxBytes() {
        // given：声明总长「上限 + 1024」字节的计数流，实际消费应封顶「上限 + 1」
        AnswerImageResolver resolver = new AnswerImageResolver(kbAssetStoragePort);
        CountingInputStream countingStream = new CountingInputStream(
                MultimodalConstraints.MAX_IMAGE_BYTES + 1024L);
        when(kbAssetStoragePort.open(KEY_A)).thenReturn(Optional.of(
                new KbAssetStoragePort.OpenedObject(countingStream, MultimodalConstraints.MAX_IMAGE_BYTES + 1024L)));
        List<AnswerImageResolver.MediaReference> references = List.of(
                new AnswerImageResolver.MediaReference(1L, KEY_A));

        // when
        List<LlmImage> images = resolver.resolve(references);

        // then：超限图被跳过，且仅消费「上限 + 1」字节
        assertTrue(images.isEmpty());
        assertEquals(MultimodalConstraints.MAX_IMAGE_BYTES + 1, countingStream.getConsumed(),
                "超限对象至多读取上限 + 1 字节即判定，不整堆缓冲");
    }

    /**
     * 打桩指定对象键的打开成功返回（内容流 + 精确字节数）。
     *
     * @param objectKey 对象键
     * @param bytes     返回的图片字节
     */
    private void stubRead(String objectKey, byte[] bytes) {
        when(kbAssetStoragePort.open(eq(objectKey))).thenReturn(Optional.of(
                new KbAssetStoragePort.OpenedObject(new ByteArrayInputStream(bytes), bytes.length)));
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
}
