package com.linkroa.deepdataagent.agent.domain.service;

import com.linkroa.deepdataagent.agent.domain.model.SearchResult;

import java.util.List;

/**
 * 网络搜索服务接口（端口）
 * <p>定义网络搜索能力的领域服务接口，由基础设施层实现。</p>
 */
public interface WebSearchService {

    /**
     * 执行网络搜索
     *
     * @param query      搜索查询关键词
     * @param maxResults 最大返回结果数
     * @return 搜索结果列表
     */
    List<SearchResult> search(String query, int maxResults);
}
