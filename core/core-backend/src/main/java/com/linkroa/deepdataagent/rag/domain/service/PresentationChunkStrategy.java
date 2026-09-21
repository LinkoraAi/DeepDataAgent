package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.rag.domain.model.ChunkVO;
import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import com.linkroa.deepdataagent.rag.domain.service.DelimiterParser.Unit;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 演示稿分块策略（method={@code presentation}，/ 参考）。
 * <p>每页 1 chunk：解析阶段已按页切分内容块（页码随块 {@code meta} 透传），此处将块原样
 * 透传为独立 chunk 并做真实 token 计数；不消费 token 预算——单页语义原子性优先。
 * 每页小块直接产出单块结果（策略不变量：不得返回空列表）。</p>
 * <p>页码透出：每个 chunk 正文前置形如 {@code [第N页]} 的页码标记供引用定位。页码优先取
 * 解析层块元数据真实页码（MinerU 轨 {@code page} / {@code page_idx}），缺失或非法
 * （含 Tika 轨占位 {@code page=0}）时降级为块在文档内的页序——页序由 {@link #splitDocument}
 * 按块逐页递增计数提供（纯文本块与多模态块共用同一计数器，混排时页码标记连续）。</p>
 */
@Component
public class PresentationChunkStrategy implements ChunkStrategy {

    /** 策略方法名（注册表键，与 {@code ChunkStrategyRegistry} 映射一致）。 */
    public static final String METHOD = "presentation";

    /**
     * chunk 正文前置的页码标记格式（{@code %d} 为页码，尾部空格用于与正文分隔）。
     * <p>用途：引用定位——检索命中该 chunk 时可凭标记直接回溯到演示稿具体页；标记为正文的一部分，
     * 随 chunk 落库并进向量化文本。与坐标标签 {@code @@…##} 不冲突（后者在
     * {@link DelimiterParser#toChunkVOs} 阶段剥离，本标记不参与剥离）。</p>
     */
    private static final String PAGE_MARKER_TEMPLATE = "[第%d页] ";

    /**
     * 首页页序（三用途合一，避免重复魔法值）：文档级页序计数起点、meta 页码合法下限、
     * 单块入口（{@link #split} 拿不到全局页序）缺失页码时的兜底页序。
     * <p>meta 页码小于该值（如 Tika 轨统一占位 {@code page=0}）视为「无真实页码」，走页序兜底。</p>
     */
    private static final int FIRST_PAGE_ORDER = 1;

    @Override
    public String method() {
        return METHOD;
    }

    /**
     * 对单个内容块执行分块：整块透传为 1 chunk，正文前置页码标记。
     * <p>刻意不消费 {@code params} 的 token 预算与重叠（每页 1 块语义优先）。本入口无全局页序上下文，
     * 页码优先块 meta 真实页码，缺失/非法时兜底 {@value #FIRST_PAGE_ORDER}（第 1 页）；
     * 需要页序兜底的文档级场景请走 {@link #splitDocument}。</p>
     *
     * @param block   待切分内容块（纯文本页与多模态页均按整页透传）
     * @param params  分块参数（本实现不消费）
     * @param counter 真实 token 计数端口（标记拼接后重新计数，口径与正文一致）
     * @return 单元素 chunk 列表（策略不变量：不返回空列表）
     */
    @Override
    public List<ChunkVO> split(ContentBlockVO block, ChunkParams params, TokenCounter counter) {
        return splitWithPageMarker(block, resolvePage(block, FIRST_PAGE_ORDER), counter);
    }

    @Override
    public boolean supports(ContentBlockVO block) {
        return ContentBlockVO.TYPE_TEXT.equals(block.type());
    }

    /**
     * 文档级分块：按块序编排页序并逐块产出 1 chunk。
     * <p>解析层已按页切块，故「块序即页序」：页序从 {@value #FIRST_PAGE_ORDER} 起按文档顺序逐块递增，
     * 纯文本块与多模态块共用同一计数器，保证混排文档页码标记连续；页码优先块 meta 真实页码，
     * 缺失/非法时用该页序兜底。产出全局序号从 1 连续递增（与接口默认实现口径一致）。</p>
     *
     * @param blocks  文档全部内容块（按文档顺序，可为 null / 空）
     * @param params  分块参数（本实现不消费其预算与重叠）
     * @param counter 真实 token 计数端口
     * @return 全局序号连续、正文含页码标记的分块结果（不返回 null）
     */
    @Override
    public List<ChunkVO> splitDocument(List<ContentBlockVO> blocks, ChunkParams params, TokenCounter counter) {
        List<ChunkVO> out = new ArrayList<>();
        if (CollectionUtils.isEmpty(blocks)) {
            return out;
        }
        int globalSequence = FIRST_PAGE_ORDER;
        int pageOrder = FIRST_PAGE_ORDER;
        for (ContentBlockVO block : blocks) {
            for (ChunkVO chunk : splitWithPageMarker(block, resolvePage(block, pageOrder++), counter)) {
                out.add(new ChunkVO(globalSequence++, chunk.text(), chunk.tokens(), chunk.block()));
            }
        }
        return out;
    }

    /**
     * 解析块生效页码：meta 真实页码优先，缺失或非法时回退入参页序。
     *
     * @param block     内容块（可为 null）
     * @param pageOrder 兜底页序（文档级为块页序，单块入口为 {@value #FIRST_PAGE_ORDER}）
     * @return 生效页码，恒 ≥ {@value #FIRST_PAGE_ORDER}
     */
    private int resolvePage(ContentBlockVO block, int pageOrder) {
        String metaPage = MultimodalMetaKeys.metaString(block, MultimodalMetaKeys.META_KEY_PAGE,
                MultimodalMetaKeys.META_KEY_PAGE_IDX);
        if (StringUtils.isBlank(metaPage)) {
            return pageOrder;
        }
        int parsed = NumberUtils.toInt(StringUtils.trim(metaPage), 0);
        return parsed >= FIRST_PAGE_ORDER ? parsed : pageOrder;
    }

    /**
     * 构建页码标记进正文后的单块结果：标记前置 → 整块作为透传单元 → 统一剥坐标标签并计数。
     *
     * @param block     内容块
     * @param page      生效页码
     * @param counter   真实 token 计数端口
     * @return 单元素 chunk 列表（来源块引用原样保留，供回源与多模态管线使用）
     */
    private List<ChunkVO> splitWithPageMarker(ContentBlockVO block, int page, TokenCounter counter) {
        String text = String.format(PAGE_MARKER_TEMPLATE, page) + StringUtils.stripToEmpty(block.text());
        Unit unit = new Unit(text, counter.count(text), true, block);
        return DelimiterParser.toChunkVOs(List.of(unit), counter);
    }
}
