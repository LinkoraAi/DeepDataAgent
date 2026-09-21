package com.linkroa.deepdataagent.rag.infrastructure;

import com.linkroa.deepdataagent.rag.api.RetrievalApi;
import com.linkroa.deepdataagent.rag.application.contract.RetrievalQuery;
import com.linkroa.deepdataagent.rag.application.contract.RetrievalResult;
import com.linkroa.deepdataagent.rag.application.service.RetrievalApplicationService;
import org.springframework.stereotype.Component;

/**
 * RAG 检索服务契约实现（{@link RetrievalApi}）。
 * <p>进程内薄委托检索编排应用服务 {@link RetrievalApplicationService#search(RetrievalQuery)}，
 * 本类只做契约适配、不含任何业务规则；入参校验、知识库可用性闸门与各阶段降级兜底口径
 * 均由被委托的应用服务保证，异常按原语义透传给消费方。</p>
 */
@Component
public class DefaultRetrievalApi implements RetrievalApi {

    /** 检索编排应用服务（Stage 0~5 全链路唯一入口） */
    private final RetrievalApplicationService retrievalApplicationService;

    /**
     * 构造检索服务契约实现。
     *
     * @param retrievalApplicationService 检索编排应用服务
     */
    public DefaultRetrievalApi(RetrievalApplicationService retrievalApplicationService) {
        this.retrievalApplicationService = retrievalApplicationService;
    }

    @Override
    public RetrievalResult search(RetrievalQuery query) {
        return retrievalApplicationService.search(query);
    }
}
