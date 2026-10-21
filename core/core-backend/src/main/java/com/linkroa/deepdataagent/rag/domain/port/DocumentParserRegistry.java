package com.linkroa.deepdataagent.rag.domain.port;

import com.linkroa.deepdataagent.knowledgebase.domain.model.DocumentParseConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FileType;

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
 * <p><b>类型路由（按引擎分治）</b>：带文档类型的重载按「引擎能力」选型——文档类型落在所选引擎
 * {@link DocumentParser#supportedTypes()} 集合内才返回该引擎；否则 <b>LOCAL</b> 回落本地直读兜底解析器
 * （图片视觉直通 / 纯文本直读），<b>MINERU</b> 抛出可读的 {@link IllegalArgumentException} 并列出支持类型
 * （严格模式，无兜底）。上传入口经 {@code ParserCapabilityApi} 以同一口径提前拦截。</p>
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

    /**
     * 按解析配置 + 文档类型选择文档解析器（类型路由）。
     * <p>文档类型落在所选引擎支持集合内 → 返回该引擎解析器；否则 LOCAL 返回本地直读兜底解析器，
     * MINERU 抛出 {@link IllegalArgumentException}（含支持类型清单）。</p>
     *
     * @param parseConfig 知识库解析配置（provider + 引擎参数；provider 为空按 LOCAL 兜底）
     * @param fileType    文档类型，可为 null（未知类型；LOCAL 走兜底，MINERU 拒绝）
     * @return 引擎解析器，或 LOCAL 引擎下的本地直读兜底解析器
     * @throws IllegalArgumentException 解析配置为 null、provider 未知、MINERU 必填参数缺失，
     *                                  或 MINERU 遇不受支持的文档类型
     */
    DocumentParser resolve(DocumentParseConfig parseConfig, FileType fileType);
}
