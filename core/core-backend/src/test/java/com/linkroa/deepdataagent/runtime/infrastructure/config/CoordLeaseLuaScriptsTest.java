package com.linkroa.deepdataagent.runtime.infrastructure.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CoordLeaseLuaScripts} 单测：三段 turn 租约脚本随资源就位且语义关键字齐备
 * （owner-scoped CAS 的原子表达依赖脚本本体，缺失即启动失败）。
 */
class CoordLeaseLuaScriptsTest {

    @Test
    void should_loadAllTurnScripts_when_load_given_classpathResources() {
        // given
        CoordLeaseLuaScripts scripts = new CoordLeaseLuaScripts();

        // when
        scripts.load();

        // then（turn：SET NX PX + SADD / GET==owner PEXPIRE / GET==owner DEL+SREM）
        assertTrue(scripts.turnAcquire().contains("SET"));
        assertTrue(scripts.turnAcquire().contains("SADD"));
        assertTrue(scripts.turnRenew().contains("PEXPIRE"));
        assertTrue(scripts.turnReleaseOwned().contains("DEL"));
        assertTrue(scripts.turnReleaseOwned().contains("SREM"));
        // then（owner 索引读写只出现在 turn 脚本里）
        assertFalse(scripts.turnRenew().contains("redis.call('SADD'"));
        assertFalse(scripts.turnRenew().contains("redis.call('DEL'"));
    }

    @Test
    void should_throwIllegalState_when_read_given_missingScriptResource() {
        // given // when // then（脚本缺失启动期 fail-fast，不带病上线）
        assertThrows(IllegalStateException.class, () -> CoordLeaseLuaScripts.read("lua/runtime/nope.lua"));
    }

    @Test
    void should_buildStandaloneKeysWithoutHashTag_when_turnAndOwners_given_sessionAndInstance() {
        // given // when
        String turn = CoordLeaseKeys.turn("sess_1");
        String owners = CoordLeaseKeys.turnOwners("instance-x");

        // then（standalone 形态：不带 cluster hash tag，前缀规范见 javadoc）
        assertTrue(turn.startsWith("runtime:turn:") && !turn.contains("{"));
        assertTrue(owners.startsWith("runtime:turn-owners:"));
    }
}
