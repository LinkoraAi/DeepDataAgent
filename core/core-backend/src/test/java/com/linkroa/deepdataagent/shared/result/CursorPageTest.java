package com.linkroa.deepdataagent.shared.result;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CursorPage} 游标分页响应装配与序列化形状单测（6.1 公共约定）。
 * <p>含 {@code slice} 内存切片单点：探针余量、恰好满页、末页、before 方向翻回展示序、空页。</p>
 */
class CursorPageTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Test
    void should_extractFirstAndLastId_when_of_given_nonEmptyData() {
        // given
        List<String> data = List.of("sess_3", "sess_2", "sess_1");

        // when
        CursorPage<String> page = CursorPage.of(data, true, item -> item);

        // then（首尾即游标）
        assertEquals(data, page.data());
        assertEquals("sess_3", page.firstId());
        assertEquals("sess_1", page.lastId());
        assertTrue(page.hasMore());
    }

    @Test
    void should_nullCursorIds_when_of_given_emptyData() {
        // given & when
        CursorPage<String> page = CursorPage.of(List.of(), false, item -> item);

        // then（空页游标为 null、has_more false）
        assertNull(page.firstId());
        assertNull(page.lastId());
        assertTrue(page.data().isEmpty());
        assertFalse(page.hasMore());
    }

    @Test
    void should_serializeSnakeCaseWithoutNextPage_when_writeValueAsString_given_defaultShape() {
        // given
        CursorPage<String> page = CursorPage.of(List.of("env_1"), true, item -> item);

        // when
        String json = MAPPER.writeValueAsString(page);

        // then（{data,first_id,last_id,has_more} 形状；next_page 缺省省略）
        assertTrue(json.contains("\"first_id\":\"env_1\""));
        assertTrue(json.contains("\"last_id\":\"env_1\""));
        assertTrue(json.contains("\"has_more\":true"));
        assertFalse(json.contains("next_page"));
        assertFalse(json.contains("firstId"));
    }

    @Test
    void should_normalizeEmptyList_when_construct_given_nullData() {
        // given & when（null data → 空列表）
        CursorPage<String> page = new CursorPage<>(null, null, null, false, "cursor-token");

        // when
        String json = MAPPER.writeValueAsString(page);

        // then（next_page 存在时序列化输出）
        assertTrue(page.data().isEmpty());
        assertTrue(json.contains("\"next_page\":\"cursor-token\""));
    }

    @Test
    void should_fillNextPageWithLastId_when_withNextCursorFromLastId_given_moreRows() {
        // given（契约：有下一页时 next_page 等于 last_id）
        CursorPage<String> page = CursorPage.of(List.of("3", "2"), true, item -> item);

        // when
        CursorPage<String> filled = page.withNextCursorFromLastId();

        // then（last_id 即下页游标）
        assertEquals("2", filled.nextPage());
        assertEquals(page.firstId(), filled.firstId());
        assertEquals(page.lastId(), filled.lastId());
        assertTrue(filled.hasMore());
    }

    @Test
    void should_keepNullNextPage_when_withNextCursorFromLastId_given_noMoreRows() {
        // given（末页无余量）
        CursorPage<String> page = CursorPage.of(List.of("1"), false, item -> item);

        // when
        CursorPage<String> filled = page.withNextCursorFromLastId();

        // then（next_page 仍缺省省略）
        assertNull(filled.nextPage());
    }

    // ==================== slice：内存切片单点（列表端点两处切片收敛） ====================

    @Test
    void should_dropProbeRow_when_slice_given_limitPlusOneRows() {
        // given（DB 侧 limit+1 探针读取：3 行 + limit=2）
        List<String> rows = List.of("sess_3", "sess_2", "sess_1");

        // when
        CursorPage<String> page = CursorPage.slice(rows, new CursorPageParams(2, null, null), false, item -> item);

        // then（当页取头部 limit 条，余量即 has_more；游标取当页首尾）
        assertEquals(List.of("sess_3", "sess_2"), page.data());
        assertEquals("sess_3", page.firstId());
        assertEquals("sess_2", page.lastId());
        assertTrue(page.hasMore());
    }

    @Test
    void should_reportNoMore_when_slice_given_rowsExactlyAtLimit() {
        // given（无探针余量）
        List<String> rows = List.of("sess_2", "sess_1");

        // when
        CursorPage<String> page = CursorPage.slice(rows, new CursorPageParams(2, null, null), false, item -> item);

        // then
        assertEquals(rows, page.data());
        assertFalse(page.hasMore());
    }

    @Test
    void should_keepAllRows_when_slice_given_rowsFewerThanLimit() {
        // given
        List<String> rows = List.of("sess_1");

        // when
        CursorPage<String> page = CursorPage.slice(rows, new CursorPageParams(20, null, null), false, item -> item);

        // then（末页原样返回、has_more false）
        assertEquals(rows, page.data());
        assertFalse(page.hasMore());
        assertNull(page.nextPage());
    }

    @Test
    void should_flipBackDisplayOrder_when_slice_given_reverseReadDirection() {
        // given（before 方向以升序读取，展示需翻回降序：读取头 = 展示尾）
        List<String> rows = List.of("sess_2", "sess_1");

        // when
        CursorPage<String> page = CursorPage.slice(rows, new CursorPageParams(2, null, null), true, item -> item);

        // then（翻转后首尾游标随展示方向变化）
        assertEquals(List.of("sess_1", "sess_2"), page.data());
        assertEquals("sess_1", page.firstId());
        assertEquals("sess_2", page.lastId());
        assertFalse(page.hasMore());
    }

    @Test
    void should_takeHeadOfTailFirstWindow_when_slice_given_reverseWithProbeRemainder() {
        // given（向前翻页：尾部为头部读取，取头部 limit 后翻回，余量即「更早还有」）
        List<String> rows = List.of("sess_5", "sess_4", "sess_3");

        // when
        CursorPage<String> page = CursorPage.slice(rows, new CursorPageParams(2, null, null), true, item -> item);

        // then（读取窗口头部 sess_5 / sess_4 为最近的两条，翻回展示序后 sess_3 成为余量）
        assertEquals(List.of("sess_4", "sess_5"), page.data());
        assertEquals("sess_4", page.firstId());
        assertEquals("sess_5", page.lastId());
        assertTrue(page.hasMore());
    }

    @Test
    void should_returnEmptyPage_when_slice_given_nullOrEmptyRows() {
        // given & when
        CursorPage<String> nullPage = CursorPage.slice(null, new CursorPageParams(20, null, null), false, item -> item);
        CursorPage<String> emptyPage = CursorPage.slice(List.of(), new CursorPageParams(20, null, null), false, item -> item);

        // then（空页游标为 null 且无余量）
        assertTrue(nullPage.data().isEmpty());
        assertNull(nullPage.firstId());
        assertFalse(nullPage.hasMore());
        assertTrue(emptyPage.data().isEmpty());
        assertFalse(emptyPage.hasMore());
    }
}
