package com.linkroa.deepdataagent.rag.domain.port;

import com.linkroa.deepdataagent.knowledgebase.domain.model.DocumentParseConfig;

/**
 * 文档解析器注册表端口：按知识库解析配置（provider）选择具体的 {@link DocumentParser} 实现。
 * <p>对应摄入链路「配置决定解析方式」的选型入口：LOCAL → 本地 Tika 解析，
 * MINERU → 远程 MinerU 解析。语义约束：</p>
 * <ul>
 *   <li>provider 为空（null）时按 LOCAL 兜底，与知识库配置的默认口径一致；</li>
 *   <li>配置整体无效（如解析配置为 null、MINERU 缺少必填参数）时抛
 *       {@link IllegalArgumentException} 快速失败；</li>
 *   <li><b>无失败自动回退</b>——本端口只在解析前选型，解析期失败由具体
 *       {@link DocumentParser} 实现抛异常交由调用方置 FAILED，不得自动切换另一种引擎。</li>
 * </ul>
 */
public interface DocumentParserRegistry {

    /**
     * 按解析配置选择文档解析器。
     *
     * @param parseConfig 知识库解析配置（provider + 引擎参数；provider 为空按 LOCAL 兜底）
     * @return 与配置匹配的文档解析器（LOCAL 为进程内单例；MINERU 按引擎参数每次新建）
     * @throws IllegalArgumentException 解析配置为 null、provider 未知或 MINERU 必填参数缺失
     */
    DocumentParser resolve(DocumentParseConfig parseConfig);
}
