package com.linkroa.deepdataagent.rag.infrastructure;

import com.linkroa.deepdataagent.knowledgebase.api.InFlightTaskRegistry;
import com.linkroa.deepdataagent.knowledgebase.api.InFlightTaskType;
import com.linkroa.deepdataagent.rag.infrastructure.config.AppInstanceProperties;
import jakarta.annotation.PostConstruct;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link InFlightTaskRegistry} 契约的缓存实现（跨重启在飞任务归属凭据）。
 * <p>键结构与值形态：<strong>三段式键名、单层集合</strong>——键为
 * {@code <实例标识>:knowledgebase-rag:<任务类型>}（中间段为固定字面量常量，承担与同一缓存实例上
 * 其他业务的隔离），值为<strong>任务 ID 集合</strong>。示例：
 * {@code node-a:knowledgebase-rag:docIngestionTask} → {@code {1001, 1002}}，
 * 表示该实例上仍有 1001、1002 两篇文档未完成解析。三类任务各持一键，
 * 故键总数恒为「实例数 × 3」，与在飞任务数无关，读取无需任何扫描。</p>
 *
 * <p>选型说明：值用集合而非列表——集合的成员删除与存在性判断为 O(1)，列表为 O(N)；
 * 且集合天然去重（重复登记不堆积）；集合为空时键由缓存自动消失，不存在空键残留。
 * 键不设 TTL：任务为分钟级，短 TTL 会把处理中的任务误判为残留。</p>
 *
 * <p>故障语义：登记（{@link #register}）写入失败时异常上抛，由调用方走各自链的既有失败兜底
 * （解析置 FAILED / 清退置 DELETE_FAILED），MUST NOT 静默放行——登记成功是「任务可被收敛」
 * 的前提。移除与读取失败仅 ERROR 留痕并降级（移除失败留下的成员可由下次启动的一致性校验清理；
 * 读取失败时启动恢复跳过本轮残留，属已登记的已知风险）。</p>
 *
 * @author DeepDataAgent
 */
@Component
public class RedisInFlightTaskRegistry implements InFlightTaskRegistry {

    private static final Logger log = LoggerFactory.getLogger(RedisInFlightTaskRegistry.class);

    /**
     * 键的中间段（业务域标识）：固定字面量常量，MUST NOT 按限界上下文取值、MUST NOT 写成可配置项。
     * <p>作用是与同一缓存实例上的其他业务隔离，运维无需再加前缀。</p>
     */
    static final String DOMAIN_SEGMENT = "knowledgebase-rag";

    /** 键分段分隔符。 */
    private static final String KEY_SEPARATOR = ":";

    private final StringRedisTemplate stringRedisTemplate;

    private final AppInstanceProperties appInstanceProperties;

    /**
     * 构造缓存在飞任务注册表。
     *
     * @param stringRedisTemplate  字符串模板（注册表键与成员均为字符串形态）
     * @param appInstanceProperties 实例标识配置（键的实例维度，启动期已 fail-fast 校验）
     */
    public RedisInFlightTaskRegistry(StringRedisTemplate stringRedisTemplate,
                                     AppInstanceProperties appInstanceProperties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.appInstanceProperties = appInstanceProperties;
    }

    /**
     * 启动日志：输出实例标识与其所辖的全部注册表键名，供运维排查实例标识重复等问题。
     */
    @PostConstruct
    public void logInstanceScope() {
        log.info("[在飞任务注册表] 实例标识={}，所辖键名=[{}]",
                appInstanceProperties.getInstanceId(), String.join(", ", allKeys()));
    }

    @Override
    public void register(InFlightTaskType taskType, Long taskId) {
        String key = requireKey(taskType, taskId);
        // 写入失败（缓存不可用）异常上抛：调用方按各自链的既有失败兜底收敛，不得静默放行
        stringRedisTemplate.opsForSet().add(key, String.valueOf(taskId));
        log.debug("[在飞任务注册表] 已登记在飞任务: key={}, taskId={}", key, taskId);
    }

    @Override
    public void unregister(InFlightTaskType taskType, Long taskId) {
        String key = requireKey(taskType, taskId);
        try {
            stringRedisTemplate.opsForSet().remove(key, String.valueOf(taskId));
            log.debug("[在飞任务注册表] 已移除在飞任务成员: key={}, taskId={}", key, taskId);
        } catch (RuntimeException e) {
            // 残留成员无业务影响：下次启动按本实例键读取时会经一致性校验后移除
            log.error("[在飞任务注册表] 移除在飞任务成员失败（残留成员由下次启动收敛）, key={}, taskId={}",
                    key, taskId, e);
        }
    }

    @Override
    public Set<Long> findInFlightTaskIds(InFlightTaskType taskType) {
        if (ObjectUtils.isEmpty(taskType)) {
            throw new IllegalArgumentException("在飞任务类型不能为空");
        }
        Set<Long> taskIds = new LinkedHashSet<>();
        try {
            Set<String> members = stringRedisTemplate.opsForSet().members(keyOf(taskType));
            if (CollectionUtils.isEmpty(members)) {
                return Set.of();
            }
            for (String member : members) {
                Long taskId = parseTaskId(member);
                if (ObjectUtils.isNotEmpty(taskId)) {
                    taskIds.add(taskId);
                }
            }
        } catch (RuntimeException e) {
            log.error("[在飞任务注册表] 读取本实例在飞任务失败，本轮按无残留处理（已登记的已知风险）, key={}",
                    keyOf(taskType), e);
            return Set.of();
        }
        return Set.copyOf(taskIds);
    }

    @Override
    public void clearInstanceRegistry() {
        stringRedisTemplate.delete(allKeys());
    }

    /**
     * 构造指定任务类型的注册表键：{@code <实例标识>:knowledgebase-rag:<任务类型>}。
     *
     * @param taskType 任务类型，必填
     * @return 注册表键名
     * @throws IllegalArgumentException 任务类型为空
     */
    String keyOf(InFlightTaskType taskType) {
        if (ObjectUtils.isEmpty(taskType)) {
            throw new IllegalArgumentException("在飞任务类型不能为空");
        }
        return appInstanceProperties.getInstanceId() + KEY_SEPARATOR + DOMAIN_SEGMENT
                + KEY_SEPARATOR + taskType.keySegment();
    }

    /**
     * 构造本实例的全部注册表键（停机清空与启动日志共用）。
     *
     * @return 三类任务各自的键名列表
     */
    private List<String> allKeys() {
        return Arrays.stream(InFlightTaskType.values()).map(this::keyOf).toList();
    }

    /**
     * 校验入参并构造键。
     *
     * @param taskType 任务类型
     * @param taskId   任务主体主键
     * @return 注册表键名
     * @throws IllegalArgumentException 任务类型或任务ID为空
     */
    private String requireKey(InFlightTaskType taskType, Long taskId) {
        if (ObjectUtils.isEmpty(taskType)) {
            throw new IllegalArgumentException("在飞任务类型不能为空");
        }
        if (ObjectUtils.isEmpty(taskId)) {
            throw new IllegalArgumentException("在飞任务ID不能为空: taskType=" + taskType);
        }
        return keyOf(taskType);
    }

    /**
     * 解析集合成员为主键；非法形态（非数字）跳过并 WARN 留痕（理论不可达：成员仅由本类写入）。
     *
     * @param member 集合成员原文
     * @return 任务主键；非法形态返回 {@code null}
     */
    private Long parseTaskId(String member) {
        if (StringUtils.isBlank(member)) {
            return null;
        }
        try {
            return Long.valueOf(StringUtils.trim(member));
        } catch (NumberFormatException e) {
            log.warn("[在飞任务注册表] 集合成员非法，已跳过: member={}", member);
            return null;
        }
    }
}