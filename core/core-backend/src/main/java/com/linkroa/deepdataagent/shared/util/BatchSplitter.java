package com.linkroa.deepdataagent.shared.util;

import org.apache.commons.lang3.ObjectUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 批量写入分片工具。
 * <p>将大集合按固定批次大小切分，配合数据库事务规范「批量插入/更新需控制批次大小
 * （建议 500-1000 条/批）」使用，避免单条 SQL 过长或单事务过大。</p>
 */
public final class BatchSplitter {

    /** 工具类禁止实例化 */
    private BatchSplitter() {
    }

    /**
     * 按指定批次大小切分集合。
     *
     * @param source    原始集合，为空时返回空的分片列表
     * @param batchSize 批次大小，必须为正数
     * @param <T>       元素类型
     * @return 分片后的列表，除最后一片外每片长度均为 batchSize
     * @throws IllegalArgumentException 批次大小非正数时抛出
     */
    public static <T> List<List<T>> split(List<T> source, int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("批次大小必须为正数");
        }
        if (ObjectUtils.isEmpty(source)) {
            return Collections.emptyList();
        }
        List<List<T>> batches = new ArrayList<>((source.size() + batchSize - 1) / batchSize);
        for (int start = 0; start < source.size(); start += batchSize) {
            int end = Math.min(start + batchSize, source.size());
            batches.add(new ArrayList<>(source.subList(start, end)));
        }
        return batches;
    }
}
