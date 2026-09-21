package com.linkroa.deepdataagent.knowledgebase.application.query;

/**
 * 切片列表查询（限定知识库范围，可按文档、序号、内容关键字过滤）。
 *
 * @param kbId       知识库ID
 * @param documentId 文档ID，可为空
 * @param sequence   切片序号，可为空
 * @param keyword    切片内容关键字，可为空
 * @param page       页码，从 1 开始
 * @param size       每页条数
 */
public record ListChunkQuery(
        Long kbId,
        Long documentId,
        Integer sequence,
        String keyword,
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
    public ListChunkQuery {
        if (page < DEFAULT_PAGE) {
            page = DEFAULT_PAGE;
        }
        if (size < 1) {
            size = DEFAULT_SIZE;
        }
    }
}
