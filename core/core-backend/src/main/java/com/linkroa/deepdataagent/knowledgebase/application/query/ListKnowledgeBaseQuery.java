package com.linkroa.deepdataagent.knowledgebase.application.query;

/**
 * 知识库列表查询。
 * <p>可见性口径：三态全集 ACTIVE / DELETING / DELETE_FAILED 一律可见，
 * 本查询对象不下传具体生命周期状态——过滤条件由仓储侧显式三态 IN 保证（已收口库行物理不存在、天然不可达）；
 * 关键字对名称与描述做模糊匹配；排序参数为原始字符串，白名单校验与缺省兜底由应用层校验器完成。</p>
 *
 * @param keyword   名称/描述关键字，可为空
 * @param page      页码，从 1 开始
 * @param size      每页条数
 * @param sortBy    排序字段（name/createdAt/updatedAt），可为空，空时按创建时间排序
 * @param sortOrder 排序方向（asc/desc），可为空，空时按倒序
 */
public record ListKnowledgeBaseQuery(
        String keyword,
        int page,
        int size,
        String sortBy,
        String sortOrder
) {

    /** 页码兜底值 */
    private static final int DEFAULT_PAGE = 1;

    /** 每页条数兜底值 */
    private static final int DEFAULT_SIZE = 20;

    /**
     * 紧凑构造器：分页参数兜底，避免非法分页穿透到仓储层。
     */
    public ListKnowledgeBaseQuery {
        if (page < DEFAULT_PAGE) {
            page = DEFAULT_PAGE;
        }
        if (size < 1) {
            size = DEFAULT_SIZE;
        }
    }
}
