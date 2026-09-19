package com.linkroa.deepdataagent.runtime.infrastructure.client;

import com.linkroa.deepdataagent.runtime.domain.model.enums.CoordLeaseType;
import com.linkroa.deepdataagent.runtime.infrastructure.config.CoordLeaseKeys;
import org.junit.jupiter.api.Test;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * turn owner 二级索引集成测试（move-coordination-leases-to-redis tasks 2.3，真连 Redis）。
 *
 * <p>覆盖启动恢复所依赖的索引面：acquire 与 SADD 同脚本原子、release 与 SREM 同脚本原子、
 * SMEMBERS 恢复扫描（{@code listOwnedTurnKeys}）、过期残留成员惰性 SREM（design D6）。</p>
 */
class RedisCoordLeaseOwnerSetTest extends RedisRepositoryTestSupport {

    private static final String OWNER = "it-owner-a";
    private static final String OTHER_OWNER = "it-owner-b";

    /** 领域租约键（turn:session:*），与生产侧同一派生入口。 */
    private static String turnKey(String sessionId) {
        return CoordLeaseType.TURN.keyOf(sessionId);
    }

    @Test
    void should_addIndexMember_when_tryAcquire_given_newTurnLease() {
        // given
        String sessionId = "it-sess-index-add";

        // when
        assertTrue(leaseStore.tryAcquire(CoordLeaseType.TURN, turnKey(sessionId),
                OWNER, Duration.ofMinutes(10)));

        // then：单脚本内 SET NX + SADD 原子同写（不存在「有锁无索引」中间态）
        assertTrue(ownerSetMembers(OWNER).contains(sessionId),
                "acquire 应同写 owner 二级索引成员");
    }

    @Test
    void should_removeIndexMember_when_releaseOwned_given_ownerReleased() {
        // given
        String sessionId = "it-sess-index-remove";
        assertTrue(leaseStore.tryAcquire(CoordLeaseType.TURN, turnKey(sessionId),
                OWNER, Duration.ofMinutes(10)));

        // when
        assertTrue(leaseStore.releaseOwned(CoordLeaseType.TURN, turnKey(sessionId), OWNER));

        // then：DEL 与 SREM 同脚本原子
        assertFalse(ownerSetMembers(OWNER).contains(sessionId),
                "release 应同步摘除 owner 索引成员");
    }

    @Test
    void should_listOwnTurnKeys_when_listOwnedTurnKeys_given_multipleLeasesOwned() {
        // given：本实例持两条有效租约，他实例持一条
        assertTrue(leaseStore.tryAcquire(CoordLeaseType.TURN, turnKey("it-sess-own-1"),
                OWNER, Duration.ofMinutes(10)));
        assertTrue(leaseStore.tryAcquire(CoordLeaseType.TURN, turnKey("it-sess-own-2"),
                OWNER, Duration.ofMinutes(10)));
        assertTrue(leaseStore.tryAcquire(CoordLeaseType.TURN, turnKey("it-sess-other"),
                OTHER_OWNER, Duration.ofMinutes(10)));

        // when
        List<String> ownedKeys = leaseStore.listOwnedTurnKeys(OWNER);

        // then：仅本实例槽位（审查修复 F13：不触碰其他存活实例）
        assertTrue(ownedKeys.contains(turnKey("it-sess-own-1")));
        assertTrue(ownedKeys.contains(turnKey("it-sess-own-2")));
        assertFalse(ownedKeys.contains(turnKey("it-sess-other")),
                "他实例租约不得进入本实例恢复枚举");
    }

    @Test
    void should_lazyRemoveStaleMember_when_listOwnedTurnKeys_given_leaseExpiredButIndexRemains() {
        // given：短 TTL 租约到期（key 自过期），索引成员仍在（无 TTL）
        String sessionId = "it-sess-stale-member";
        assertTrue(leaseStore.tryAcquire(CoordLeaseType.TURN, turnKey(sessionId),
                OWNER, Duration.ofMillis(600)));
        sleepMillis(900);
        assertTrue(ownerSetMembers(OWNER).contains(sessionId), "前置：残留成员应存在");

        // when
        List<String> ownedKeys = leaseStore.listOwnedTurnKeys(OWNER);

        // then：恢复扫描惰性对账——不返回失效键，且顺手 SREM 残留成员
        assertFalse(ownedKeys.contains(turnKey(sessionId)),
                "已过期租约不应出现在恢复枚举");
        assertFalse(ownerSetMembers(OWNER).contains(sessionId),
                "残留成员应在扫描时被惰性清理");
    }

    @Test
    void should_notRemovePredecessorIndex_when_tryAcquire_given_leaseTakenOverByOtherOwner() {
        // given：A 持锁过期后 B 接管（A 的索引成员成为残留）
        String sessionId = "it-sess-takeover-index";
        assertTrue(leaseStore.tryAcquire(CoordLeaseType.TURN, turnKey(sessionId),
                OWNER, Duration.ofMillis(600)));
        sleepMillis(900);
        assertTrue(leaseStore.tryAcquire(CoordLeaseType.TURN, turnKey(sessionId),
                OTHER_OWNER, Duration.ofMinutes(10)));

        // then：接管只写新 owner 索引；A 扫描时该成员因当前 owner 不匹配被惰性摘除，
        // B 的恢复枚举正常包含该会话
        assertTrue(leaseStore.listOwnedTurnKeys(OTHER_OWNER).contains(turnKey(sessionId)));
        assertFalse(leaseStore.listOwnedTurnKeys(OWNER).contains(turnKey(sessionId)));
        assertFalse(ownerSetMembers(OWNER).contains(sessionId));
    }

    /** 读取 owner 二级索引成员集合。 */
    private List<String> ownerSetMembers(String owner) {
        return List.copyOf(redissonClient.<String>getSet(CoordLeaseKeys.turnOwners(owner),
                StringCodec.INSTANCE).readAll());
    }

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
