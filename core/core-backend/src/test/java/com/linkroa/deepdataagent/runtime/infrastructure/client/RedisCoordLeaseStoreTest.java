package com.linkroa.deepdataagent.runtime.infrastructure.client;

import com.linkroa.deepdataagent.runtime.domain.model.enums.CoordLeaseType;
import com.linkroa.deepdataagent.runtime.infrastructure.config.CoordLeaseLuaScripts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link RedisCoordLeaseStore} 单测：七方法经 mock {@code RScript} 的 Lua 交互口径
 * （key 布局、ARGV 顺序、1/0 返回值映射、异常透传二分类、owner set 惰性对账）。
 * <p>Lua 脚本真实语义由 {@code @Tag("integration")} 的 Redis 集成测试覆盖（tasks 2.2~2.5）。</p>
 */
@ExtendWith(MockitoExtension.class)
class RedisCoordLeaseStoreTest {

    private static final String OWNER = "instance-x";
    private static final String SESSION_ID = "sess_1";
    private static final String TURN_KEY = "turn:session:" + SESSION_ID;
    private static final String TURN_REDIS_KEY = "runtime:turn:" + SESSION_ID;
    private static final String OWNERS_REDIS_KEY = "runtime:turn-owners:" + OWNER;
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final long FIRE_TTL_MS = Duration.ofSeconds(30).toMillis();

    @Mock private RedissonClient redissonClient;
    @Mock private RScript rScript;
    @Mock private RBucket<String> rBucket;
    @Mock private RSet<String> rSet;
    @Mock private CoordLeaseLuaScripts scripts;

    private RedisCoordLeaseStore store;

    @BeforeEach
    void setUp() {
        store = new RedisCoordLeaseStore();
        ReflectionTestUtils.setField(store, "redissonClient", redissonClient);
        ReflectionTestUtils.setField(store, "scripts", scripts);
    }

    /** 桩定脚本客户端（所有 Lua 执行路径共用同一 {@code getScript(StringCodec)} 入口）。 */
    private void stubScriptClient() {
        when(redissonClient.getScript(StringCodec.INSTANCE)).thenReturn(rScript);
    }

    @Test
    void should_runAcquireScriptWithTurnAndOwnerKeys_when_tryAcquire_given_turnLease() {
        // given（抢占脚本：turn key + owner set 双 key，ARGV = owner / TTL 毫秒 / sessionId）
        stubScriptClient();
        when(scripts.turnAcquire()).thenReturn("ACQUIRE_LUA");
        when(rScript.eval(RScript.Mode.READ_WRITE, "ACQUIRE_LUA", RScript.ReturnType.LONG,
                List.of(TURN_REDIS_KEY, OWNERS_REDIS_KEY), OWNER, TTL.toMillis(), SESSION_ID)).thenReturn(1L);

        // when
        boolean acquired = store.tryAcquire(CoordLeaseType.TURN, TURN_KEY, OWNER, TTL);

        // then
        assertTrue(acquired);
    }

    @Test
    void should_returnFalse_when_tryAcquire_given_scriptReturnsZero() {
        // given（并发单赢家败方：Lua 返回 0）
        stubScriptClient();
        when(scripts.turnAcquire()).thenReturn("ACQUIRE_LUA");
        when(rScript.eval(RScript.Mode.READ_WRITE, "ACQUIRE_LUA", RScript.ReturnType.LONG,
                List.of(TURN_REDIS_KEY, OWNERS_REDIS_KEY), OWNER, TTL.toMillis(), SESSION_ID)).thenReturn(0L);

        // when // then
        assertFalse(store.tryAcquire(CoordLeaseType.TURN, TURN_KEY, OWNER, TTL));
    }

    @Test
    void should_throwIllegalArgument_when_tryAcquire_given_fireLeaseType() {
        // given // when // then（fire 走专用通道，通用方法快速失败，两实现口径一致）
        assertThrows(IllegalArgumentException.class,
                () -> store.tryAcquire(CoordLeaseType.FIRE, "fire:scheduler:dep_1", OWNER, TTL));
        verifyNoInteractions(scripts);
    }

    @Test
    void should_runRenewScriptAndReportLoss_when_renew_given_scriptReturnsZero() {
        // given（GET==owner 不成立：确证失权，调用方立即 fail-closed）
        stubScriptClient();
        when(scripts.turnRenew()).thenReturn("RENEW_LUA");
        when(rScript.eval(RScript.Mode.READ_WRITE, "RENEW_LUA", RScript.ReturnType.LONG,
                List.of(TURN_REDIS_KEY), OWNER, TTL.toMillis())).thenReturn(0L);

        // when // then
        assertFalse(store.renew(CoordLeaseType.TURN, TURN_KEY, OWNER, TTL));
    }

    @Test
    void should_propagateException_when_renew_given_redisConnectionError() {
        // given（design D5①：连接 / 命令异常以异常表达，MUST NOT 被吞成 false 误判失权）
        stubScriptClient();
        when(scripts.turnRenew()).thenReturn("RENEW_LUA");
        when(rScript.eval(RScript.Mode.READ_WRITE, "RENEW_LUA", RScript.ReturnType.LONG,
                List.of(TURN_REDIS_KEY), OWNER, TTL.toMillis()))
                .thenThrow(new IllegalStateException("redis connection lost"));

        // when // then
        assertThrows(IllegalStateException.class, () -> store.renew(CoordLeaseType.TURN, TURN_KEY, OWNER, TTL));
    }

    @Test
    void should_runOwnerScopedRelease_when_releaseOwned_given_scriptReturnsOne() {
        // given（F11：GET==owner 才 DEL + SREM）
        stubScriptClient();
        when(scripts.turnReleaseOwned()).thenReturn("RELEASE_LUA");
        when(rScript.eval(RScript.Mode.READ_WRITE, "RELEASE_LUA", RScript.ReturnType.LONG,
                List.of(TURN_REDIS_KEY, OWNERS_REDIS_KEY), OWNER, SESSION_ID)).thenReturn(1L);

        // when // then
        assertTrue(store.releaseOwned(CoordLeaseType.TURN, TURN_KEY, OWNER));
    }

    @Test
    void should_reportActive_when_findActive_given_turnKeyExists() {
        // given（服务端 TTL 自过期：key 存在即有效）
        when(redissonClient.<String>getBucket(TURN_REDIS_KEY, StringCodec.INSTANCE)).thenReturn(rBucket);
        when(rBucket.isExists()).thenReturn(true);

        // when // then
        assertTrue(store.findActive(CoordLeaseType.TURN, TURN_KEY));
    }

    @Test
    void should_reportInactive_when_findActive_given_turnKeyExpired() {
        // given（租约已 TTL 过期 → key 不存在）
        when(redissonClient.<String>getBucket(TURN_REDIS_KEY, StringCodec.INSTANCE)).thenReturn(rBucket);
        when(rBucket.isExists()).thenReturn(false);

        // when // then
        assertFalse(store.findActive(CoordLeaseType.TURN, TURN_KEY));
    }

    @Test
    void should_listOwnedTurnKeys_when_listOwnedTurnKeys_given_ownerStillHoldsLease() {
        // given（owner set 成员仍由本实例持有 → 反解为 turn 租约键）
        when(redissonClient.<String>getSet(OWNERS_REDIS_KEY, StringCodec.INSTANCE)).thenReturn(rSet);
        when(rSet.readAll()).thenReturn(Set.of(SESSION_ID));
        when(redissonClient.<String>getBucket(TURN_REDIS_KEY, StringCodec.INSTANCE)).thenReturn(rBucket);
        when(rBucket.get()).thenReturn(OWNER);

        // when
        List<String> keys = store.listOwnedTurnKeys(OWNER);

        // then
        assertEquals(List.of(TURN_KEY), keys);
        verify(rSet, Mockito.never()).remove(Mockito.anyString());
    }

    @Test
    void should_lazySremStaleMember_when_listOwnedTurnKeys_given_leaseTakenOverOrExpired() {
        // given（索引残留成员：租约已过期或被接管 → 惰性 SREM 且不计入结果，design D6）
        when(redissonClient.<String>getSet(OWNERS_REDIS_KEY, StringCodec.INSTANCE)).thenReturn(rSet);
        when(rSet.readAll()).thenReturn(Set.of(SESSION_ID));
        when(redissonClient.<String>getBucket(TURN_REDIS_KEY, StringCodec.INSTANCE)).thenReturn(rBucket);
        when(rBucket.get()).thenReturn(null);

        // when
        List<String> keys = store.listOwnedTurnKeys(OWNER);

        // then
        assertTrue(keys.isEmpty());
        verify(rSet).remove(SESSION_ID);
    }

    @Test
    void should_runFireAcquireWithSingleKeyAndThirtySecondTtl_when_fireTryAcquire_given_freeWindow() {
        // given（fire 无 owner 二级索引，30s 窗口为语义常量）
        stubScriptClient();
        when(scripts.fireAcquire()).thenReturn("FIRE_ACQUIRE_LUA");
        when(rScript.eval(RScript.Mode.READ_WRITE, "FIRE_ACQUIRE_LUA", RScript.ReturnType.LONG,
                List.of("runtime:fire:dep_1"), OWNER, FIRE_TTL_MS)).thenReturn(1L);

        // when // then
        assertTrue(store.fireTryAcquire("dep_1", OWNER));
    }

    @Test
    void should_returnFalse_when_fireTryAcquire_given_windowOccupied() {
        // given
        stubScriptClient();
        when(scripts.fireAcquire()).thenReturn("FIRE_ACQUIRE_LUA");
        when(rScript.eval(RScript.Mode.READ_WRITE, "FIRE_ACQUIRE_LUA", RScript.ReturnType.LONG,
                List.of("runtime:fire:dep_1"), OWNER, FIRE_TTL_MS)).thenReturn(0L);

        // when // then
        assertFalse(store.fireTryAcquire("dep_1", OWNER));
    }

    @Test
    void should_runFireOwnerScopedRelease_when_fireReleaseOwned_given_scriptReturnsOne() {
        // given（F11：非持有者的迟到释放不误摘接管者）
        stubScriptClient();
        when(scripts.fireReleaseOwned()).thenReturn("FIRE_RELEASE_LUA");
        when(rScript.eval(RScript.Mode.READ_WRITE, "FIRE_RELEASE_LUA", RScript.ReturnType.LONG,
                List.of("runtime:fire:dep_1"), OWNER)).thenReturn(1L);

        // when // then
        assertTrue(store.fireReleaseOwned("dep_1", OWNER));
    }
}
