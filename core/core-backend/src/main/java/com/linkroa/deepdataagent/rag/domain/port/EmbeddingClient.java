package com.linkroa.deepdataagent.rag.domain.port;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本向量化端口（实体/关系向量与 chunk 向量回写统一出口）。
 * <p>与 {@link LlmClient} 不同，embedding 调用不进 {@code llm_cache}（结果体积大且
 * 重跑窗口内文本变化概率低，缓存收益不成立）；实现方同样必须保证调用发生在事务之外。</p>
 *
 * @author DeepDataAgent
 */
public interface EmbeddingClient {

    /**
     * 将单段文本向量化。
     *
     * @param modelProfileId 嵌入模型配置 profileId（agent BC EMBEDDING 类型）
     * @param text           待向量化文本（调用方已按 embedding_token_limit 截断）
     * @return 向量分量数组（维度与模型 vectorDimension 配置一致）
     * @throws IllegalArgumentException 参数非法
     * @throws RuntimeException         模型解析失败或远程调用失败
     */
    float[] embed(String modelProfileId, String text);

    /**
     * 批量将多段文本向量化（检索侧一次批量预计算，避免逐条远程调用放大延迟）。
     * <p>返回列表与入参列表<b>按固定下标一一对应</b>，调用方可安全地按下标解包。
     * 默认实现逐条调用 {@link #embed(String, String)}；实现方可覆写为真批量接口调用。</p>
     *
     * @param modelProfileId 嵌入模型配置 profileId（agent BC EMBEDDING 类型）
     * @param texts          待向量化文本列表（元素顺序即返回顺序）
     * @return 向量分量数组列表（size 与入参一致，下标一一对应）
     * @throws IllegalArgumentException 参数非法（profileId 为空或列表为空）
     * @throws RuntimeException         模型解析失败或远程调用失败
     */
    default List<float[]> embedBatch(String modelProfileId, List<String> texts) {
        if (StringUtils.isBlank(modelProfileId)) {
            throw new IllegalArgumentException("嵌入模型 profileId 不能为空");
        }
        if (texts == null || texts.isEmpty()) {
            throw new IllegalArgumentException("向量化文本列表不能为空");
        }
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(embed(modelProfileId, text));
        }
        return vectors;
    }
}
