package com.linkroa.deepdataagent.knowledgebase.application.query;

/**
 * 文档列表查询（限定知识库范围）。
 *
 * @param kbId     知识库ID
 * @param fileName 文件名关键字，可为空
 * @param status   文档处理状态（DocumentStatus 枚举名），可为空
 * @param page     页码，从 1 开始
 * @param size     每页条数
 */
public record ListDocumentQuery(
        Long kbId,
        String fileName,
        String status,
        int page,
        int size
) {

    /** 页码兜底值 */
    private static final int DEFAULT_PAGE = 1;

    /** 每页条数兜底值 */
    private static final int DEFAULT_SIZE = 20;

    /**
     * 紧凑构造器：分页参数兜底，避免非法分页穿透到仓储层。
     */
    public ListDocumentQuery {
        if (page < DEFAULT_PAGE) {
            page = DEFAULT_PAGE;
        }
        if (size < 1) {
            size = DEFAULT_SIZE;
        }
    }
}
