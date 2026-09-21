package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.rag.domain.model.ChunkVO;
import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import com.linkroa.deepdataagent.rag.domain.service.DelimiterParser.Unit;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 整篇分块策略（method={@code one}，/ 参考）。
 * <p>整篇 1 chunk：文档级主管线（{@code GeneralChunkingService}）在 one 模式下会把文档内全部
 * 文本块拼接为单块后调用本策略切分一次；本类块级实现为「单块原样透传成 1 chunk」，同时
 * 作为拼接后的容器职责与单块兜底。多模态原子块不做拼接，由主管线透传独立（多模态
 * 7-Stage 管线的按对象模板化路线，参考备注）。策略不变量：不得返回空列表。</p>
 */
@Component
public class OneChunkStrategy implements ChunkStrategy {

    /** 策略方法名（注册表键，与 {@code ChunkStrategyRegistry} 映射一致）。 */
    public static final String METHOD = "one";

    @Override
    public String method() {
        return METHOD;
    }

    @Override
    public List<ChunkVO> split(ContentBlockVO block, ChunkParams params, TokenCounter counter) {
        Unit unit = DelimiterParser.passthroughUnit(block, counter);
        return DelimiterParser.toChunkVOs(List.of(unit), counter);
    }

    @Override
    public boolean supports(ContentBlockVO block) {
        return ContentBlockVO.TYPE_TEXT.equals(block.type());
    }
}