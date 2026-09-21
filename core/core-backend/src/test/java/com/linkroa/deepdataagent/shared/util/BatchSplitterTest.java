package com.linkroa.deepdataagent.shared.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BatchSplitter} 分片工具单测。
 */
class BatchSplitterTest {

    private static List<Integer> sequenceOf(int size) {
        List<Integer> source = new ArrayList<>(size);
        for (int index = 1; index <= size; index++) {
            source.add(index);
        }
        return source;
    }

    @Test
    void should_splitEvenly_when_split_given_sizeDivisibleByBatch() {
        // when
        List<List<Integer>> batches = BatchSplitter.split(sequenceOf(10), 5);

        // then
        assertEquals(2, batches.size());
        assertEquals(5, batches.get(0).size());
        assertEquals(5, batches.get(1).size());
        assertEquals(1, batches.get(0).get(0));
        assertEquals(6, batches.get(1).get(0));
    }

    @Test
    void should_keepTailBatch_when_split_given_sizeNotDivisibleByBatch() {
        // when
        List<List<Integer>> batches = BatchSplitter.split(sequenceOf(12), 5);

        // then
        assertEquals(3, batches.size());
        assertEquals(5, batches.get(1).size());
        assertEquals(2, batches.get(2).size());
    }

    @Test
    void should_returnSingleBatch_when_split_given_sizeSmallerThanBatch() {
        // when
        List<List<Integer>> batches = BatchSplitter.split(sequenceOf(3), 500);

        // then
        assertEquals(1, batches.size());
        assertEquals(3, batches.get(0).size());
    }

    @Test
    void should_returnEmpty_when_split_given_emptySource() {
        // when
        List<List<Integer>> batches = BatchSplitter.split(Collections.emptyList(), 500);

        // then
        assertTrue(batches.isEmpty());
    }

    @Test
    void should_returnEmpty_when_split_given_nullSource() {
        // when
        List<List<Integer>> batches = BatchSplitter.split(null, 500);

        // then
        assertTrue(batches.isEmpty());
    }

    @Test
    void should_throwIllegalArgument_when_split_given_nonPositiveBatchSize() {
        // when & then
        assertThrows(IllegalArgumentException.class, () -> BatchSplitter.split(sequenceOf(3), 0));
        assertThrows(IllegalArgumentException.class, () -> BatchSplitter.split(sequenceOf(3), -1));
    }

    @Test
    void should_notAffectOriginalList_when_split_given_sourceMutatedAfterwards() {
        // given
        List<Integer> source = sequenceOf(6);

        // when
        List<List<Integer>> batches = BatchSplitter.split(source, 4);
        source.add(7);

        // then：分片结果为独立副本，不受原集合后续变更影响
        assertEquals(4, batches.get(0).size());
        assertEquals(2, batches.get(1).size());
        assertEquals(7, source.size());
    }
}
