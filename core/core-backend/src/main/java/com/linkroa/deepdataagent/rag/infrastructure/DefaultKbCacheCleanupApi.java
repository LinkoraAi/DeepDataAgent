package com.linkroa.deepdataagent.rag.infrastructure;

import com.linkroa.deepdataagent.knowledgebase.api.KbCacheCleanupApi;
import com.linkroa.deepdataagent.rag.domain.repository.LlmCacheRepository;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.stereotype.Component;

/**
 * {@link KbCacheCleanupApi} 契约的默认实现（防腐层 / 契约适配器）。
 * <p>本类只做契约适配，不含业务规则：空值防御在此完成，
 * 按库整清（条件物理删除、幂等）委托 rag BC 领域端口 {@link LlmCacheRepository#deleteByKbId(Long)}。
 * 清退失败异常原样上抛，由消费方（knowledgebase 删除收敛链）决定留痕与重试策略。</p>
 */
@Component
public class DefaultKbCacheCleanupApi implements KbCacheCleanupApi {

    private final LlmCacheRepository llmCacheRepository;

    public DefaultKbCacheCleanupApi(LlmCacheRepository llmCacheRepository) {
        this.llmCacheRepository = llmCacheRepository;
    }

    @Override
    public void deleteCachesByKnowledgeBase(Long kbId) {
        if (ObjectUtils.isEmpty(kbId)) {
            return;
        }
        // 条件物理删除天然幂等：无条目时 0 行受影响静默成功
        llmCacheRepository.deleteByKbId(kbId);
    }
}
