package com.linkroa.deepdataagent.rag.domain.port;

import com.linkroa.deepdataagent.rag.domain.enums.CacheType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LlmChatRequest} 单元测试。
 * <p>重点校验多模态组件引入后的向后兼容契约：六参/五参便捷构造器仍可用且图片归一为空表、
 * 图片列表不可变、既有不变量（kbId/modelProfileId/userPrompt）与缓存分区缺省归一不受影响。</p>
 * <p>另校验归属登记字段：显式传入时原样保留、既有便捷构造器形态下归一为空（不登记归属）。</p>
 *
 * @author DeepDataAgent
 */
class LlmChatRequestTest {

    /** 测试知识库ID */
    private static final Long KB_ID = 1001L;

    /** 测试模型 profileId */
    private static final String PROFILE_ID = "chat-profile-1";

    /** 测试用户提示词 */
    private static final String USER_PROMPT = "USER PROMPT";

    /** 测试图片载荷 */
    private static final LlmImage IMAGE = new LlmImage("image/png", new byte[]{1, 2, 3});

    /** 测试归因分块标识 */
    private static final Long CHUNK_ID = 501L;

    /**
     * 场景：显式传入归因字段（摄入期抽取调用）。
     * 预期：字段原样保留，且不影响其余分量（该字段仅用于缓存归属登记）。
     */
    @Test
    void should_carryAttributionChunkId_when_constructor_given_attributionChunkId() {
        // given / when
        LlmChatRequest request = new LlmChatRequest(KB_ID, PROFILE_ID, "SYS", USER_PROMPT, null,
                CacheType.EXTRACT, List.of(), CHUNK_ID);

        // then
        assertEquals(CHUNK_ID, request.attributionChunkId());
        assertEquals(CacheType.EXTRACT, request.cacheType());
        assertTrue(request.images().isEmpty());
    }

    /**
     * 场景：既有七参构造器（携带图片、未引入归因字段的调用点）。
     * 预期：归因字段归一为空（不登记归属），图片分量按入参保留。
     */
    @Test
    void should_keepAttributionEmpty_when_sevenArgConstructor_given_imagesOnly() {
        // given / when
        LlmChatRequest request = new LlmChatRequest(KB_ID, PROFILE_ID, "SYS", USER_PROMPT, null,
                CacheType.EXTRACT, List.of(IMAGE));

        // then
        assertNull(request.attributionChunkId(), "既有构造器形态不携带归因字段");
        assertEquals(1, request.images().size());
    }

    /**
     * 场景：既有六参便捷构造器（纯文本调用点）。
     * 预期：图片分量归一为空列表，其余分量原样保留（既有调用点零改动）。
     */
    @Test
    void should_defaultToEmptyImages_when_sixArgConstructor_given_textOnlyRequest() {
        // given / when
        LlmChatRequest request = new LlmChatRequest(KB_ID, PROFILE_ID, "SYS", USER_PROMPT, 0.3, CacheType.ENTITY_DESC);

        // then
        assertTrue(request.images().isEmpty(), "六参构造器的图片分量必须归一为空表");
        assertEquals("SYS", request.systemPrompt());
        assertEquals(USER_PROMPT, request.userPrompt());
        assertEquals(0.3, request.temperature());
        assertEquals(CacheType.ENTITY_DESC, request.cacheType());
    }

    /**
     * 场景：既有五参便捷构造器（不声明缓存分区）。
     * 预期：缓存分区回落 {@link CacheType#ANSWER}、图片分量为空表。
     */
    @Test
    void should_defaultToAnswerPartitionAndEmptyImages_when_fiveArgConstructor_given_textOnlyRequest() {
        // given / when
        LlmChatRequest request = new LlmChatRequest(KB_ID, PROFILE_ID, null, USER_PROMPT, null);

        // then
        assertEquals(CacheType.ANSWER, request.cacheType());
        assertTrue(request.images().isEmpty());
    }

    /**
     * 场景：紧凑构造器传入 null 图片列表。
     * 预期：归一为空列表而非 null（下游可无条件遍历）。
     */
    @Test
    void should_normalizeEmptyImages_when_constructor_given_nullImages() {
        // given / when
        LlmChatRequest request =
                new LlmChatRequest(KB_ID, PROFILE_ID, null, USER_PROMPT, null, CacheType.ANSWER, null);

        // then
        assertEquals(List.of(), request.images());
    }

    /**
     * 场景：构造后修改调用方原图片列表。
     * 预期：请求内的图片分量不受影响（不可变拷贝，保证缓存键摘要稳定）。
     */
    @Test
    void should_keepImmutableImagesSnapshot_when_constructor_given_mutableList() {
        // given
        List<LlmImage> mutable = new ArrayList<>();
        mutable.add(IMAGE);

        // when
        LlmChatRequest request =
                new LlmChatRequest(KB_ID, PROFILE_ID, null, USER_PROMPT, null, CacheType.ANSWER, mutable);
        mutable.clear();

        // then
        assertEquals(1, request.images().size(), "图片列表必须做不可变快照");
        assertSame(IMAGE, request.images().get(0), "图片顺序须与入参列表一致");
        assertThrows(UnsupportedOperationException.class, () -> request.images().add(IMAGE));
    }

    /**
     * 场景：缓存分区未显式声明（canonical 构造器传 null）。
     * 预期：归一为 {@link CacheType#ANSWER}。
     */
    @Test
    void should_defaultToAnswerPartition_when_constructor_given_nullCacheType() {
        // given / when
        LlmChatRequest request =
                new LlmChatRequest(KB_ID, PROFILE_ID, null, USER_PROMPT, null, null, List.of(IMAGE));

        // then
        assertEquals(CacheType.ANSWER, request.cacheType());
        assertEquals(1, request.images().size());
    }

    /**
     * 场景：kbId 为空。
     * 预期：抛 {@link IllegalArgumentException}（缓存键库级隔离维度必填）。
     */
    @Test
    void should_throwIllegalArgument_when_constructor_given_nullKbId() {
        // given / when / then
        assertThrows(IllegalArgumentException.class,
                () -> new LlmChatRequest(null, PROFILE_ID, null, USER_PROMPT, null, CacheType.ANSWER, List.of(IMAGE)));
    }

    /**
     * 场景：modelProfileId 为空白。
     * 预期：抛 {@link IllegalArgumentException}。
     */
    @Test
    void should_throwIllegalArgument_when_constructor_given_blankModelProfileId() {
        // given / when / then
        assertThrows(IllegalArgumentException.class,
                () -> new LlmChatRequest(KB_ID, " ", null, USER_PROMPT, null, CacheType.ANSWER, List.of(IMAGE)));
    }

    /**
     * 场景：userPrompt 为空白（多模态请求同样必须有文本指令）。
     * 预期：抛 {@link IllegalArgumentException}。
     */
    @Test
    void should_throwIllegalArgument_when_constructor_given_blankUserPrompt() {
        // given / when / then
        assertThrows(IllegalArgumentException.class,
                () -> new LlmChatRequest(KB_ID, PROFILE_ID, "SYS", "", null, CacheType.ANSWER, List.of(IMAGE)));
    }
}
