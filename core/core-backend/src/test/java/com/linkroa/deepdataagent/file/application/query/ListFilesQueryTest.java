package com.linkroa.deepdataagent.file.application.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ListFilesQuery} 查询对象单测（页大小归一到 [1,100]）。
 */
class ListFilesQueryTest {

    @Test
    void should_useDefaultSize_when_constructor_given_nonPositiveSize() {
        // given // when
        ListFilesQuery query = new ListFilesQuery(null, null, null, 0);
        ListFilesQuery negative = new ListFilesQuery(null, null, null, -5);

        // then
        assertEquals(ListFilesQuery.DEFAULT_SIZE, query.size());
        assertEquals(ListFilesQuery.DEFAULT_SIZE, negative.size());
    }

    @Test
    void should_capAtMaxSize_when_constructor_given_oversized() {
        // given // when
        ListFilesQuery query = new ListFilesQuery("user_upload", "sess_1", "c", 500);

        // then
        assertEquals(ListFilesQuery.MAX_SIZE, query.size());
        assertEquals("user_upload", query.purposeCode());
        assertEquals("sess_1", query.scopeId());
        assertEquals("c", query.cursor());
    }

    @Test
    void should_keepSize_when_constructor_given_inRangeSize() {
        // given // when
        ListFilesQuery query = new ListFilesQuery(null, null, null, 50);

        // then
        assertEquals(50, query.size());
    }
}
