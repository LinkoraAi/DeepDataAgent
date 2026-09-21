package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.rag.domain.model.ChunkVO;
import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 通用（general）分块策略。
 * <p>注册表键为 {@code general}，对应分块模式 {@code GENERAL}，同时是所有未知（含空）
 * 模式/方法名的防御兜底（兜底命中会输出 WARN，见 {@link ChunkStrategyRegistry}）。
 * 本类只承载注册、支持判定与<b>块内</b>切分；
 * 切分算法底座（delimiter 切界、非原子切分、OVER_CAP 贪心合并、重叠前缀与坐标剥离）复用
 * {@link DelimiterParser} 静态工具，与文档级主管线 {@link GeneralChunkingService} 共享同一实现，
 * 保证算法逐字节一致。</p>
 */
@Component
public class GeneralChunkStrategy implements ChunkStrategy {

    /** 注册表键（映射 GENERAL→general 及未知模式兜底）。 */
    public static final String METHOD = "general";

    @Override
    public String method() {
        return METHOD;
    }

    @Override
    public List<ChunkVO> split(ContentBlockVO block, ChunkParams params, TokenCounter counter) {
        List<String> delims = DelimiterParser.parseDelimiterField(params.delimiter());
        boolean custom = DelimiterParser.hasCustomDelimiter(params.delimiter());
        Pattern pattern = DelimiterParser.buildPattern(delims);
        List<DelimiterParser.Unit> units = DelimiterParser.buildUnitsForBlock(block, pattern, custom,
                params.chunkTokenNum(), counter);
        List<DelimiterParser.Unit> merged = custom
                ? units
                : DelimiterParser.mergeUnits(units, params.chunkTokenNum(),
                        params.overlappedPercent(), counter);
        List<ChunkVO> chunks = DelimiterParser.toChunkVOs(merged, counter);
        if (chunks.isEmpty()) {
            // 兜底：剥标签后无可见字符的块输出单块（ChunkStrategy 不变量：不得返回空列表）
            String content = DelimiterParser.stripTags(block.text());
            return List.of(new ChunkVO(1, content, counter.count(content), block));
        }
        return chunks;
    }

    @Override
    public boolean supports(ContentBlockVO block) {
        return ContentBlockVO.TYPE_TEXT.equals(block.type());
    }
}
