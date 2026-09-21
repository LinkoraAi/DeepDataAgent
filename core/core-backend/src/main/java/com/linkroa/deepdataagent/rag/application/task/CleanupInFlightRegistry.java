package com.linkroa.deepdataagent.rag.application.task;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 删除清退在飞去重登记器。
 * <p>以内存 {@link ConcurrentHashMap#newKeySet()} 承载「正在清退中」的资源键：本集合承载<b>本进程内</b>
 * 「正在清退中」的完备在飞登记（进程内去重与停机清点用途）；跨进程的在飞归属由在飞任务注册表承载
 * （替代原队列的「排队 ∪ 在飞」去重集 + 有界队列 + 单消费者三件套），
 * 同一资源在飞期间的重复投递据此幂等去重，任务结束（含失败与异常）时移除登记。</p>
 *
 * <p>本类除承担进程内在飞去重外，还作为<b>停机收敛的在飞任务清点来源</b>：停机链首个动作清空
 * 跨进程注册表键后，仍需按本实例内存账本取「尚在飞的任务」清单（见
 * {@link #inFlightDocumentIds()} / {@link #inFlightKnowledgeBaseIds()}）逐一致失败收敛。</p>
 *
 * <p>用途双向：清退提交方（{@code KbCleanupTaskSubmitter} 实现、文档删除受理）以
 * {@link #tryRegister(String)} 作为投递闸门，任务体收尾统一 {@link #unregister(String)}；
 * 受理侧以 {@link #isInFlight(String)} 区分「在飞幂等返回删除中」与「崩溃遗留重新触发」。</p>
 *
 * <p>键空间隔离：知识库与文档两类资源以不同前缀区分（{@code kb:{id}} / {@code doc:{id}}），
 * 即便主键数值相同亦互不冲突，避免跨类型误去重。清退执行器与提交端口共用本登记器。</p>
 *
 * @author DeepDataAgent
 */
@Component
public class CleanupInFlightRegistry {

    private static final Logger log = LoggerFactory.getLogger(CleanupInFlightRegistry.class);

    /** 知识库清退在飞键前缀。 */
    private static final String KB_KEY_PREFIX = "kb:";

    /** 文档清退在飞键前缀。 */
    private static final String DOC_KEY_PREFIX = "doc:";

    /** 在飞资源键集合：提交前 CAS 占位、任务收尾统一移除。 */
    private final Set<String> inFlightKeys = ConcurrentHashMap.newKeySet();

    /**
     * 构造知识库清退在飞键。
     *
     * @param kbId 知识库主键
     * @return 形如 {@code kb:{kbId}} 的在飞键
     */
    public static String kbKey(Long kbId) {
        return KB_KEY_PREFIX + kbId;
    }

    /**
     * 构造文档清退在飞键。
     *
     * @param documentId 文档主键
     * @return 形如 {@code doc:{documentId}} 的在飞键
     */
    public static String docKey(Long documentId) {
        return DOC_KEY_PREFIX + documentId;
    }

    /**
     * 尝试登记在飞键（CAS 占位）。
     *
     * @param inFlightKey 在飞键
     * @return {@code true} 表示登记成功（此前不在飞）；{@code false} 表示该资源已在飞
     */
    public boolean tryRegister(String inFlightKey) {
        return inFlightKeys.add(inFlightKey);
    }

    /**
     * 移除在飞登记（任务收尾、含失败与异常时调用）。
     *
     * @param inFlightKey 在飞键
     */
    public void unregister(String inFlightKey) {
        inFlightKeys.remove(inFlightKey);
    }

    /**
     * 查询某资源是否处于清退在飞状态。
     *
     * @param inFlightKey 在飞键
     * @return {@code true} 表示在飞
     */
    public boolean isInFlight(String inFlightKey) {
        return inFlightKeys.contains(inFlightKey);
    }

    /**
     * 取当前在飞文档主键快照。
     * <p>仅供停机收敛取「仍在飞的任务」清单使用，<b>不是</b>跨实例查询——本注册表只服务本实例，
     * 快照仅覆盖本进程内已登记且尚未收尾的文档清退任务。</p>
     *
     * @return 在飞文档主键不可变快照（无在飞文档时为空集合）
     */
    public Set<Long> inFlightDocumentIds() {
        return inFlightIdsByPrefix(DOC_KEY_PREFIX);
    }

    /**
     * 取当前在飞知识库主键快照。
     * <p>仅供停机收敛取「仍在飞的任务」清单使用，<b>不是</b>跨实例查询——本注册表只服务本实例，
     * 快照仅覆盖本进程内已登记且尚未收尾的整库清退任务。</p>
     *
     * @return 在飞知识库主键不可变快照（无在飞知识库时为空集合）
     */
    public Set<Long> inFlightKnowledgeBaseIds() {
        return inFlightIdsByPrefix(KB_KEY_PREFIX);
    }

    /**
     * 按前缀收集在飞键解析出的主键快照。
     *
     * @param prefix 在飞键前缀（知识库或文档）
     * @return 主键不可变快照
     */
    private Set<Long> inFlightIdsByPrefix(String prefix) {
        Set<Long> ids = new HashSet<>();
        for (String inFlightKey : inFlightKeys) {
            if (!StringUtils.startsWith(inFlightKey, prefix)) {
                continue;
            }
            String rawId = inFlightKey.substring(prefix.length());
            try {
                ids.add(Long.valueOf(rawId));
            } catch (NumberFormatException e) {
                // 前缀匹配但后缀非数字为理论不可达路径，仅留痕跳过，不影响其余在飞键清点
                log.warn("删除清退在飞键后缀非数字主键，已跳过: inFlightKey={}", inFlightKey, e);
            }
        }
        return Set.copyOf(ids);
    }
}
