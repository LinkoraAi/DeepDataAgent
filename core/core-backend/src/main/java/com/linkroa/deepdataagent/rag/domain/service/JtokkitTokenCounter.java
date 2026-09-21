package com.linkroa.deepdataagent.rag.domain.service;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * {@link TokenCounter} 的 jtokkit 真实 encode 实现（唯一口径，主路径）。
 * <p>基于 {@code com.knuddels:jtokkit} 内置的 cl100k_base 编码（词表编译进 jar、纯 Java、
 * 不依赖外网与任何外置资源文件）对每次输入执行真实 encode 并返回 token 数量——无启发式、
 * 无估算、无缓存，保证分块窗口边界与 {@code MAX_SECTION_CONTEXT_TOKENS} 预算口径一致。</p>
 * <p>{@link #truncate(String, int)} 覆写接口默认实现：直接以真实编码的 token 序列为边界
 * 精确切片后解码，避免「二分前缀 + 重编码」的近似与多次编码开销。</p>
 * <p>线程安全：jtokkit 的 {@link Encoding} 实例无可变状态、内部不可变，可安全多线程共享
 * （g7 并发闸门会并行触发计数），故作为无状态字段在构造期一次性取得并复用。</p>
 */
@Component
public class JtokkitTokenCounter implements TokenCounter {

    /** cl100k_base 编码实例（无状态、线程安全，构造期取得后作为不可变字段复用）。 */
    private final Encoding encoding;

    /**
     * 默认构造器：从 jtokkit 默认编码注册表取得内置 cl100k_base 编码（词表自包含、无外部 I/O）。
     */
    public JtokkitTokenCounter() {
        this.encoding = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
    }

    @Override
    public int count(String text) {
        if (StringUtils.isBlank(text)) {
            return 0;
        }
        // 真实 encode：直接统计编码出的 token 数量
        return encoding.countTokens(text);
    }

    @Override
    public String truncate(String text, int maxTokens) {
        if (StringUtils.isBlank(text) || maxTokens <= 0) {
            return StringUtils.EMPTY;
        }
        IntArrayList tokens = encoding.encode(text);
        // 未超预算原样返回，避免无谓的重新解码
        if (tokens.size() <= maxTokens) {
            return text;
        }
        // 按真实 token 序列取前 maxTokens 项精确切片后解码
        IntArrayList prefix = new IntArrayList();
        for (int i = 0; i < maxTokens; i++) {
            prefix.add(tokens.get(i));
        }
        return encoding.decode(prefix);
    }
}
