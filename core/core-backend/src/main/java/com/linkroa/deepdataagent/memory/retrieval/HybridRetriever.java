package com.linkroa.deepdataagent.memory.retrieval;

import com.linkroa.deepdataagent.memory.model.MemorySearchResult;
import com.linkroa.deepdataagent.memory.model.RetrieveOptions;
import java.util.List;

/**
 * 混合检索接口。
 *
 * <p>封装长期记忆的召回能力，调用方只需要提供查询和检索参数，不关心底层是
 * FTS、向量检索还是文本扫描回退。</p>
 */
public interface HybridRetriever {

    List<MemorySearchResult> hybridSearch(String query, RetrieveOptions options);
}
