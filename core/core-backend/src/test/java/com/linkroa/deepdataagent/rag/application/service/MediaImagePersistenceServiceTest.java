package com.linkroa.deepdataagent.rag.application.service;

import com.linkroa.deepdataagent.knowledgebase.application.port.KbAssetStoragePort;
import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import com.linkroa.deepdataagent.rag.domain.model.MediaFileRef;
import com.linkroa.deepdataagent.rag.domain.model.ParsedDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link MediaImagePersistenceService} 单元测试。
 * <p>Mock 对象资产存储端口 {@link KbAssetStoragePort}，验证：含图产物按约定对象键写入（putMedia）
 * 且返回仅对象键引用、单图写入失败仅跳过该图（其余成功、不上抛）、解析产物无图片时零对象存储交互，
 * 以及图片名净化失败与内容类型推断分支。</p>
 * <p>口径说明：桶概念已退役，写入统一经 {@link KbAssetStoragePort#putMedia(String, byte[], String)}
 * 按对象键落配置固定桶，入参不再携带桶名 / 流长度 / force 覆盖标记；
 * {@code MediaFileRef} 返回值仅含对象键单分量。</p>
 */
@ExtendWith(MockitoExtension.class)
class MediaImagePersistenceServiceTest {

    /** 测试知识库主键 */
    private static final Long KB_ID = 7L;

    /** 测试文档主键 */
    private static final Long DOCUMENT_ID = 42L;

    /** 图片一名称 */
    private static final String IMG_A = "img_a.png";

    /** 图片二名称 */
    private static final String IMG_B = "img_b.jpeg";

    /** 对象资产存储端口 Mock */
    @Mock
    private KbAssetStoragePort kbAssetStoragePort;

    /** 被测对象 */
    private MediaImagePersistenceService service;

    @BeforeEach
    void setUp() {
        // given 段统一注入 Mock 依赖，被测对象每个用例前新建
        service = new MediaImagePersistenceService(kbAssetStoragePort);
    }

    @Test
    void should_putByConventionKeyAndReturnRefs_when_persistImages_given_parsedWithImages() {
        // given：两张图（其一含相对目录，落库前只取基名）
        byte[] bytesA = bytes("png-a");
        byte[] bytesB = bytes("jpeg-b");
        ParsedDocument parsed = parsedWithImages(Map.of(IMG_A, bytesA, "images/" + IMG_B, bytesB));

        // when
        Map<String, MediaFileRef> refs = service.persistImages(KB_ID, DOCUMENT_ID, parsed);

        // then：按 rag/{kbId}/{documentId}/images/{name} 约定各写一次，返回引用仅对象键单分量
        assertEquals(2, refs.size());
        assertEquals("rag/7/42/images/" + IMG_A, refs.get(IMG_A).objectKey());
        assertEquals("rag/7/42/images/" + IMG_B, refs.get(IMG_B).objectKey());
        verify(kbAssetStoragePort).putMedia("rag/7/42/images/" + IMG_A, bytesA, "image/png");
        verify(kbAssetStoragePort).putMedia("rag/7/42/images/" + IMG_B, bytesB, "image/jpeg");
    }

    @Test
    void should_skipFailedImageAndContinue_when_persistImages_given_putThrowsOnOneImage() {
        // given：图片一写入抛异常（依赖失败），图片二正常。
        // 失败判定放在桩内按「实际对象键」分支，MUST NOT 用 eq(单键) 打桩：ParsedDocument 以
        // Map.copyOf 归一图片映射，迭代序随 JVM 启动种子随机，两图先后不定；当未打桩的图片二
        // 先被调用时，STRICT_STUBS 会按「同方法但参数与未使用桩不符」抛 PotentialStubbingProblem，
        // 再被被测类的兜底 catch 吞成「对象存储失败」，本用例即退化为顺序敏感的假失败。
        Map<String, byte[]> images = new LinkedHashMap<>();
        images.put(IMG_A, bytes("png-a"));
        images.put(IMG_B, bytes("jpeg-b"));
        ParsedDocument parsed = parsedWithImages(images);
        String failingKey = "rag/7/42/images/" + IMG_A;
        doAnswer(invocation -> {
            if (failingKey.equals(invocation.getArgument(0))) {
                throw new IllegalStateException("对象存储不可用");
            }
            return null;
        }).when(kbAssetStoragePort).putMedia(anyString(), any(byte[].class), anyString());

        // when：不阻断整篇摄入（不上抛异常）
        Map<String, MediaFileRef> refs = service.persistImages(KB_ID, DOCUMENT_ID, parsed);

        // then：失败图无引用，其余图正常落库
        assertEquals(1, refs.size());
        assertNull(refs.get(IMG_A));
        assertEquals("rag/7/42/images/" + IMG_B, refs.get(IMG_B).objectKey());
        verify(kbAssetStoragePort, times(2)).putMedia(anyString(), any(byte[].class), anyString());
    }

    @Test
    void should_touchStorageNever_when_persistImages_given_parsedWithoutImages() {
        // given：本地 Tika 解析器路径（解析产物无图片载荷）
        ParsedDocument parsed = new ParsedDocument("hash-1",
                List.of(new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "body", Map.of())));

        // when
        Map<String, MediaFileRef> refs = service.persistImages(KB_ID, DOCUMENT_ID, parsed);

        // then：零对象存储交互，行为与升级前一致
        assertTrue(refs.isEmpty());
        verifyNoInteractions(kbAssetStoragePort);
    }

    @Test
    void should_skipUnsafeImageName_when_persistImages_given_pathTraversalName() {
        // given：图片名含路径穿越（净化失败）
        ParsedDocument parsed = parsedWithImages(Map.of("../evil.png", bytes("bad")));

        // when
        Map<String, MediaFileRef> refs = service.persistImages(KB_ID, DOCUMENT_ID, parsed);

        // then：跳过该图且不写入对象存储
        assertTrue(refs.isEmpty());
        verify(kbAssetStoragePort, never()).putMedia(anyString(), any(byte[].class), anyString());
    }

    @Test
    void should_resolveContentTypeByExtension_when_persistImages_given_knownAndUnknownExtensions() {
        // given：webp 命中映射，bmp 未收录回落二进制流
        ParsedDocument parsed = parsedWithImages(Map.of("pic.webp", bytes("w"), "pic.bmp", bytes("b")));

        // when
        service.persistImages(KB_ID, DOCUMENT_ID, parsed);

        // then
        ArgumentCaptor<String> contentTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(kbAssetStoragePort, times(2)).putMedia(anyString(), any(byte[].class), contentTypeCaptor.capture());
        List<String> contentTypes = contentTypeCaptor.getAllValues();
        assertTrue(contentTypes.contains("image/webp"));
        assertTrue(contentTypes.contains("application/octet-stream"));
    }

    // ==================== 同名图片防线 ====================

    @Test
    void should_removeAllRefsOfConflictedNameAndNotOverwriteObject_when_persistImages_given_sameNameDifferentBytes() {
        // given：两个不同原始图片名净化后同为 001.png，且字节不同；另有未冲突图片一张
        Map<String, byte[]> images = new LinkedHashMap<>();
        images.put("images/001.png", bytes("first"));
        images.put("images/frag/001.png", bytes("second-longer"));
        images.put(IMG_B, bytes("jpeg-b"));
        ParsedDocument parsed = parsedWithImages(images);

        // when
        Map<String, MediaFileRef> refs = service.persistImages(KB_ID, DOCUMENT_ID, parsed);

        // then：冲突基名的引用全部摘除（首写亦摘除），未冲突图片引用不受影响
        assertEquals(1, refs.size());
        assertTrue(refs.containsKey(IMG_B));
        assertNull(refs.get("001.png"));
        // 首写落一次对象，冲突后不再覆盖写该键（该键对象只剩首写字节，留给前缀清理消化）
        verify(kbAssetStoragePort, times(1)).putMedia(eq("rag/7/42/images/001.png"), any(byte[].class), anyString());
        verify(kbAssetStoragePort).putMedia(eq("rag/7/42/images/" + IMG_B), any(byte[].class), anyString());
    }

    @Test
    void should_keepSingleRefWithoutExtraWrite_when_persistImages_given_sameNameSameBytes() {
        // given：两个不同原始图片名净化后同名且字节完全相同（同一张图的重复出现）
        Map<String, byte[]> images = new LinkedHashMap<>();
        images.put("images/001.png", bytes("same-bytes"));
        images.put("images/frag/001.png", bytes("same-bytes"));
        ParsedDocument parsed = parsedWithImages(images);

        // when
        Map<String, MediaFileRef> refs = service.persistImages(KB_ID, DOCUMENT_ID, parsed);

        // then：维持覆盖写与单一引用，不视为冲突、不降级
        assertEquals(1, refs.size());
        assertEquals("rag/7/42/images/001.png", refs.get("001.png").objectKey());
        verify(kbAssetStoragePort, times(2)).putMedia(eq("rag/7/42/images/001.png"), any(byte[].class), anyString());
    }

    @Test
    void should_notGiveRefForLaterOccurrences_when_persistImages_given_conflictedNameAppearsAgain() {
        // given：冲突之后再出现同一基名的第三种字节
        Map<String, byte[]> images = new LinkedHashMap<>();
        images.put("images/001.png", bytes("first"));
        images.put("images/frag/001.png", bytes("second"));
        images.put("images/deep/001.png", bytes("third"));
        ParsedDocument parsed = parsedWithImages(images);

        // when
        Map<String, MediaFileRef> refs = service.persistImages(KB_ID, DOCUMENT_ID, parsed);

        // then：冲突基名终态无任何引用，且冲突后不再发起该键的写入
        assertTrue(refs.isEmpty());
        verify(kbAssetStoragePort, times(1)).putMedia(eq("rag/7/42/images/001.png"), any(byte[].class), anyString());
    }

    @Test
    void should_continueIngestionWithoutRefForFailedImage_when_persistImages_given_putFailureMixedWithConflict() {
        // given：首写图片落库失败，其后同名不同字节的图片不应被判为冲突（前一本就未落地）
        Map<String, byte[]> images = new LinkedHashMap<>();
        images.put("images/001.png", bytes("first"));
        images.put("images/frag/001.png", bytes("second"));
        ParsedDocument parsed = parsedWithImages(images);
        doThrow(new IllegalStateException("对象存储不可用")).when(kbAssetStoragePort)
                .putMedia(eq("rag/7/42/images/001.png"), any(byte[].class), anyString());

        // when：单图失败不阻断摄入
        Map<String, MediaFileRef> refs = service.persistImages(KB_ID, DOCUMENT_ID, parsed);

        // then：首写失败无引用可摘除，返回映射仍为空，摄入正常结束
        assertTrue(refs.isEmpty());
        verify(kbAssetStoragePort, times(2)).putMedia(eq("rag/7/42/images/001.png"), any(byte[].class), anyString());
    }

    /**
     * 构建携带媒体图片载荷的解析产物。
     *
     * @param images 图片名 → 图片字节
     * @return 解析产物
     */
    private ParsedDocument parsedWithImages(Map<String, byte[]> images) {
        ContentBlockVO imageBlock = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "caption",
                Map.of("img_path", "images/" + IMG_A));
        return ParsedDocument.withImages("hash-1", List.of(imageBlock), images);
    }

    /**
     * 以 UTF-8 文本构造图片字节桩数据。
     *
     * @param text 桩内容
     * @return 字节数组
     */
    private byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
