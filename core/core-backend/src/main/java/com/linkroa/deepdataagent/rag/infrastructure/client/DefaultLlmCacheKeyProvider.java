package com.linkroa.deepdataagent.rag.infrastructure.client;

import com.linkroa.deepdataagent.rag.domain.port.LlmCacheKeyProvider;
import com.linkroa.deepdataagent.rag.domain.port.LlmChatRequest;
import com.linkroa.deepdataagent.rag.domain.port.LlmImage;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * {@link LlmCacheKeyProvider} 的默认实现：缓存键算法的<b>唯一计算处</b>。
 * <p>键 = {@code md5(model + system + user + temperature [+ sha256(image)...])}：
 * 四要素（模型名 + 系统提示词 + 用户提示词 + 温度归一值）以固定连接符拼接，
 * 多模态请求在要素串尾按图片顺序追加每张图的 {@code sha256:<内容十六进制摘要>}，
 * 无图请求的键计算与引入图片维度前逐字节一致（既有缓存条目继续可命中）。</p>
 * <p>本实现的算法自缓存客户端原样迁出（字面量常量一并迁出），
 * 缓存客户端改为委托本实现计算键——单一实现保证「归属登记用的键」与「实际读写的键」
 * 恒等，两处重复实现导致的键漂移在结构上不可能发生。</p>
 * <p>模型名经 {@link ModelProfileAccess} 按请求的 {@code modelProfileId} 解析
 * （与缓存客户端同源同值）；本实现只读，不做任何缓存读写。</p>
 *
 * @author DeepDataAgent
 */
@Component
public class DefaultLlmCacheKeyProvider implements LlmCacheKeyProvider {

    /** 缓存键组成部分连接符（避免拼接歧义导致的键碰撞） */
    private static final String KEY_PART_SEPARATOR = "\n\u0001";

    /** 温度缺省占位（null 与显式默认值归一为同一缓存键） */
    private static final String TEMPERATURE_ABSENT = "default";

    /** 图片摘要要素前缀（标明摘要算法，避免与其他要素字面值歧义） */
    private static final String IMAGE_DIGEST_PREFIX = "sha256:";

    /** 图片摘要算法名称 */
    private static final String IMAGE_DIGEST_ALGORITHM = "SHA-256";

    /** 无图请求的键要素个数（模型 + 系统提示词 + 用户提示词 + 温度） */
    private static final int TEXT_ONLY_KEY_PART_COUNT = 4;

    /** 模型配置解析端口（解析参与缓存键的模型名） */
    private final ModelProfileAccess modelProfileAccess;

    /**
     * 构造缓存键计算器。
     *
     * @param modelProfileAccess 模型配置解析端口（解析参与键计算的模型名）
     */
    public DefaultLlmCacheKeyProvider(ModelProfileAccess modelProfileAccess) {
        this.modelProfileAccess = modelProfileAccess;
    }

    @Override
    public String cacheKeyOf(LlmChatRequest request) {
        if (ObjectUtils.isEmpty(request)) {
            throw new IllegalArgumentException("LLM 请求不能为空");
        }
        String modelName = modelProfileAccess.resolve(request.modelProfileId()).modelName();
        return buildCacheKey(modelName, request);
    }

    /**
     * 构建缓存键：{@code md5(model + system + user + temperature [+ sha256(image)...])}。
     * <p>图片要素按 {@link LlmChatRequest#images()} 顺序追加，同一图片内容必产生同一摘要；
     * 无图时要素串与引入多模态前完全一致（既有键不漂移）。</p>
     *
     * @param modelName 模型名（解析结果，可空）
     * @param request   本次 LLM 请求
     * @return 32 位 MD5 十六进制缓存键
     */
    private String buildCacheKey(String modelName, LlmChatRequest request) {
        List<LlmImage> images = CollectionUtils.isEmpty(request.images())
                ? List.of() : request.images();
        List<String> parts = new ArrayList<>(TEXT_ONLY_KEY_PART_COUNT + images.size());
        parts.add(StringUtils.defaultString(modelName));
        parts.add(StringUtils.defaultString(request.systemPrompt()));
        parts.add(request.userPrompt());
        parts.add(ObjectUtils.isEmpty(request.temperature())
                ? TEMPERATURE_ABSENT : String.valueOf(request.temperature()));
        for (LlmImage image : images) {
            parts.add(IMAGE_DIGEST_PREFIX + sha256Hex(image.content()));
        }
        return DigestUtils.md5DigestAsHex(String.join(KEY_PART_SEPARATOR, parts).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 图片内容 SHA-256 小写十六进制摘要。
     *
     * @param content 图片字节
     * @return 十六进制摘要
     * @throws IllegalStateException 摘要算法不可用
     */
    private String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance(IMAGE_DIGEST_ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("图片摘要算法不可用", e);
        }
    }
}