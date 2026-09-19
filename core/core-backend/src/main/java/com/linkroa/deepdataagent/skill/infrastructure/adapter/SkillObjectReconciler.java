package com.linkroa.deepdataagent.skill.infrastructure.adapter;

import com.linkroa.deepdataagent.shared.storage.ObjectMetadata;
import com.linkroa.deepdataagent.shared.storage.ObjectStorage;
import com.linkroa.deepdataagent.skill.infrastructure.persistence.mapper.SkillContentVersionMapper;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 技能孤儿对象启动对账器（skill BC 自有命名空间，不跨 BC）。
 * <p>以低 phase {@link SmartLifecycle} 在 Web 容器放行流量前执行一次：列举 {@code skills/}
 * 前缀，归并出 {@code skills/<skill_id>/<epoch>/} 版本前缀，凡在 skill_content_version
 * <b>无物理行（含 is_deleted=1）</b>的前缀（即崩溃于事务回滚 / insert 前的半成品）才删除；
 * 已逻辑删除版本的内容法定保留，<b>绝不回收</b>。逐项容错，无法解析的 key 跳过 + WARN。</p>
 */
@Component
public class SkillObjectReconciler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SkillObjectReconciler.class);

    /** 与启动恢复同级，早于 Web 服务器放行流量。 */
    private static final int RECONCILE_PHASE = Integer.MIN_VALUE;

    /** 技能对象 key 前缀根。 */
    private static final String SKILLS_PREFIX = "skills/";

    /** 捕获版本前缀 {@code skills/<skill_id>/<epoch>/}（skill_ + UUID 字符集，版本为 epoch 微秒十进制串）。 */
    private static final Pattern VERSION_PREFIX =
            Pattern.compile("^skills/(skill_[A-Za-z0-9-]{1,64})/(\\d{1,32})/");

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Resource
    private ObjectStorage objectStorage;
    @Resource
    private SkillContentVersionMapper versionMapper;

    @Override
    public void start() {
        reconcileOnce();
        running.set(true);
    }

    @Override
    public void stop() {
        running.set(false);
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return RECONCILE_PHASE;
    }

    /**
     * 一次性对账入口（幂等；可由生命周期触发，也可测试显式调用）。
     */
    public void reconcileOnce() {
        List<ObjectMetadata> objects;
        try {
            objects = objectStorage.list(SKILLS_PREFIX);
        } catch (RuntimeException ex) {
            log.error("技能孤儿对象对账列举失败，本次跳过（不阻断启动）", ex);
            return;
        }
        // 归并出全部可精确解析的版本前缀（去重、保持顺序）
        Set<VersionRef> versions = new LinkedHashSet<>();
        for (ObjectMetadata metadata : objects) {
            VersionRef ref = parseVersionRef(metadata.key());
            if (ref == null) {
                log.warn("技能孤儿对账跳过无法解析的对象 key: {}", metadata.key());
                continue;
            }
            versions.add(ref);
        }
        int removed = 0;
        for (VersionRef ref : versions) {
            try {
                // 物理行口径：包含已逻辑删除行；只要物理存在即保留内容
                if (versionMapper.countPhysicalBySkillIdAndVersion(ref.skillId(), ref.version()) == 0) {
                    objectStorage.deletePrefix(ref.prefix());
                    removed++;
                }
            } catch (RuntimeException ex) {
                log.warn("技能孤儿对象对账单项失败（继续处理其余）: prefix={}", ref.prefix(), ex);
            }
        }
        if (removed > 0) {
            log.info("技能孤儿对象对账完成，回收无物理台账行的版本前缀: count={}", removed);
        }
    }

    /**
     * 从对象 key 解析版本引用（前缀 / skillId / version）；无法匹配精确形态返回 null。
     *
     * @param key 对象 key
     * @return 版本引用；无法解析返回 null
     */
    private static VersionRef parseVersionRef(String key) {
        if (key == null) {
            return null;
        }
        Matcher matcher = VERSION_PREFIX.matcher(key);
        if (!matcher.find()) {
            return null;
        }
        String version = matcher.group(2);
        return new VersionRef(matcher.group(1), version,
                SKILLS_PREFIX + matcher.group(1) + "/" + version + "/");
    }

    /**
     * 版本前缀解析结果（内部值对象）。
     *
     * @param skillId 技能业务 ID
     * @param version 版本键（epoch 微秒字符串）
     * @param prefix  对象 key 前缀
     */
    private record VersionRef(String skillId, String version, String prefix) {
    }
}
