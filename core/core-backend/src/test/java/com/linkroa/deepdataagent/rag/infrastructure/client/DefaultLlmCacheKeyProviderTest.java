package com.linkroa.deepdataagent.rag.infrastructure.client;

import com.linkroa.deepdataagent.rag.domain.enums.CacheType;
import com.linkroa.deepdataagent.rag.domain.port.LlmChatRequest;
import com.linkroa.deepdataagent.rag.domain.port.LlmImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultLlmCacheKeyProvider} 单元测试：证明键算法与引入本实现前（缓存客户端私有实现）
 * <b>逐字节一致</b>——期望值全部由测试侧按四要素手工复算 MD5 得到，不复用被测实现的任何代码。
 * <p>覆盖：无图键（温度缺省占位 default）、显式温度参与键、含图键（要素串尾追加
 * {@code sha256:<摘要>}）、多图按列表顺序追加、归因字段不进入键要素串、请求为空快速失败。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class DefaultLlmCacheKeyProviderTest {

    /** 测试知识库ID */
    private static final Long KB_ID = 1001L;

    /** 测试模型 profileId */
    private static final String PROFILE_ID = "chat-profile-1";

    /** 解析后的模型名称（参与缓存键） */
    private static final String MODEL_NAME = "gpt-4o-mini";

    /** 温度缺省占位（与实现同字面量，用于手工复算基线键） */
    private static final String TEMPERATURE_ABSENT = "default";

    /** 缓存键要素连接符（与实现同字面量，用于手工复算基线键） */
    private static final String KEY_PART_SEPARATOR = "\n\u0001";

    /** 图片摘要要素前缀（与实现同字面量） */
    private static final String IMAGE_DIGEST_PREFIX = "sha256:";

    /** 模型配置解析端口 Mock */
    @Mock
    private ModelProfileAccess modelProfileAccess;

    /** 被测键计算器（构造注入 Mock 解析端口） */
    @InjectMocks
    private DefaultLlmCacheKeyProvider provider;

    /**
     * 场景：纯文本请求且温度为空（引入图片维度与归因字段前的既有形态）。
     * 预期：键等于「模型 + 系统 + 用户 + 温度缺省占位」四要素的手工复算 MD5——键不漂移。
     */
    @Test
    void should_returnLegacyKey_when_cacheKeyOf_given_textRequestWithoutTemperature() {
        // given：模型解析桩 + 五参便捷构造器请求（温度缺省）
        stubModelResolve();
        LlmChatRequest request = new LlmChatRequest(KB_ID, PROFILE_ID, "SYS", "LEGACY PROMPT", null,
                CacheType.EXTRACT);

        // when
        String cacheKey = provider.cacheKeyOf(request);

        // then：与测试侧手工复算的四要素基线键逐字节一致
        assertEquals(expectedCacheKey(MODEL_NAME, "SYS", "LEGACY PROMPT", TEMPERATURE_ABSENT), cacheKey);
    }

    /**
     * 场景：显式声明采样温度。
     * 预期：温度以字符串形态进入要素串，键等于手工复算值与温度缺省形态不同。
     */
    @Test
    void should_includeTemperature_when_cacheKeyOf_given_explicitTemperature() {
        // given
        stubModelResolve();
        LlmChatRequest request = new LlmChatRequest(KB_ID, PROFILE_ID, "SYS", "TEMP PROMPT", 0.3,
                CacheType.EXTRACT);

        // when
        String cacheKey = provider.cacheKeyOf(request);

        // then
        assertEquals(expectedCacheKey(MODEL_NAME, "SYS", "TEMP PROMPT", "0.3"), cacheKey);
        assertNotEquals(expectedCacheKey(MODEL_NAME, "SYS", "TEMP PROMPT", TEMPERATURE_ABSENT), cacheKey,
                "显式温度与缺省温度必须产生不同缓存键");
    }

    /**
     * 场景：请求携带单张图片（多模态媒体描述形态）。
     * 预期：要素串尾追加 {@code sha256:<内容摘要>}，键等于「四要素 + 图片摘要」的手工复算值。
     */
    @Test
    void should_appendImageDigest_when_cacheKeyOf_given_imageRequest() {
        // given
        byte[] image = new byte[]{1, 2, 3};
        stubModelResolve();
        LlmChatRequest request = new LlmChatRequest(KB_ID, PROFILE_ID, "SYS", "IMAGE PROMPT", null,
                CacheType.EXTRACT, List.of(new LlmImage("image/png", image)));

        // when
        String cacheKey = provider.cacheKeyOf(request);

        // then
        assertEquals(expectedCacheKey(MODEL_NAME, "SYS", "IMAGE PROMPT", TEMPERATURE_ABSENT,
                imageDigestPart(image)), cacheKey);
    }

    /**
     * 场景：请求携带两张图片。
     * 预期：摘要按图片列表顺序追加到要素串尾部；顺序交换即产生不同键（不做集合归一）。
     */
    @Test
    void should_appendDigestsInListOrder_when_cacheKeyOf_given_multipleImages() {
        // given
        byte[] first = new byte[]{(byte) 0xAA};
        byte[] second = new byte[]{(byte) 0xBB};
        stubModelResolve();
        LlmChatRequest ordered = new LlmChatRequest(KB_ID, PROFILE_ID, "SYS", "MULTI PROMPT", null,
                CacheType.EXTRACT, List.of(new LlmImage("image/png", first), new LlmImage("image/jpeg", second)));
        LlmChatRequest swapped = new LlmChatRequest(KB_ID, PROFILE_ID, "SYS", "MULTI PROMPT", null,
                CacheType.EXTRACT, List.of(new LlmImage("image/png", second), new LlmImage("image/jpeg", first)));

        // when
        String orderedKey = provider.cacheKeyOf(ordered);
        String swappedKey = provider.cacheKeyOf(swapped);

        // then
        assertEquals(expectedCacheKey(MODEL_NAME, "SYS", "MULTI PROMPT", TEMPERATURE_ABSENT,
                imageDigestPart(first), imageDigestPart(second)), orderedKey);
        assertNotEquals(orderedKey, swappedKey, "图片顺序不同不得归一为同一缓存键");
    }

    /**
     * 场景：请求携带归因分块标识（抽取期调用）。
     * 预期：键与不携带归因时的基线键逐字节一致——归因字段不参与键计算。
     */
    @Test
    void should_ignoreAttributionChunkId_when_cacheKeyOf_given_attributionRequest() {
        // given：八参规范构造器携带归因字段
        stubModelResolve();
        LlmChatRequest request = new LlmChatRequest(KB_ID, PROFILE_ID, "SYS", "ATTR PROMPT", null,
                CacheType.EXTRACT, List.of(), 501L);

        // when
        String cacheKey = provider.cacheKeyOf(request);

        // then
        assertEquals(expectedCacheKey(MODEL_NAME, "SYS", "ATTR PROMPT", TEMPERATURE_ABSENT), cacheKey);
    }

    /**
     * 场景：入参请求为 null。
     * 预期：快速失败抛 {@link IllegalArgumentException}，不触达模型配置解析（只读计算的入参边界）。
     */
    @Test
    void should_throwIllegalArgument_when_cacheKeyOf_given_nullRequest() {
        // given / when / then
        assertThrows(IllegalArgumentException.class, () -> provider.cacheKeyOf(null));
        verifyNoInteractions(modelProfileAccess);
    }

    /**
     * 手工复算缓存键：要素串按固定连接符拼接后取 MD5 十六进制。
     *
     * @param parts 键要素（模型 + 系统 + 用户 + 温度 [+ 图片摘要...]）
     * @return 32 位 MD5 十六进制键
     */
    private String expectedCacheKey(String... parts) {
        return DigestUtils.md5DigestAsHex(String.join(KEY_PART_SEPARATOR, parts).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 手工复算图片键要素：{@code sha256:<内容 SHA-256 小写十六进制>}。
     *
     * @param content 图片字节
     * @return 图片键要素
     */
    private String imageDigestPart(byte[] content) {
        try {
            return IMAGE_DIGEST_PREFIX + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }

    /**
     * 桩化模型端口解析：任意 profileId 解析出固定模型名的端点三元组。
     */
    private void stubModelResolve() {
        when(modelProfileAccess.resolve(anyString()))
                .thenReturn(new ModelProfileAccess.ResolvedEndpoint("https://api.example/v1", "sk-x", MODEL_NAME, null));
    }
}