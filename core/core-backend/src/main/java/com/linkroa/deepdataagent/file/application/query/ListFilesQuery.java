package com.linkroa.deepdataagent.file.application.query;

/**
 * 文件列表查询（游标分页）。
 * <p>owner 隔离由应用服务从认证上下文解析，不进入查询对象；
 * {@code size} 归一到 [1, 100]（缺省 20）。</p>
 *
 * @param purposeCode 用途契约值过滤（可空）
 * @param scopeId     作用域资源 ID 过滤（可空，如 sess_...）
 * @param cursor      不透明游标（可空，空为首页）
 * @param size        本页条数（归一 [1,100]）
 */
public record ListFilesQuery(
        String purposeCode,
        String scopeId,
        String cursor,
        int size
) {

    /** 缺省页大小。 */
    public static final int DEFAULT_SIZE = 20;

    /** 最大页大小。 */
    public static final int MAX_SIZE = 100;

    /**
     * 紧凑构造器：页大小归一。
     */
    public ListFilesQuery {
        if (size <= 0) {
            size = DEFAULT_SIZE;
        }
        if (size > MAX_SIZE) {
            size = MAX_SIZE;
        }
    }
}
