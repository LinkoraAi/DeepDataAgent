package com.linkroa.deepdataagent.shared.net;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EgressTrustPolicy} 出网信任边界判定单测（design D8）。
 *
 * <p>覆盖：回环 / 本机通配 / 链路本地（含云元数据）/ 组播 / RFC1918 三段 / IPv6 ULA 各自被拒、
 * 白名单开关放行、容器宿主别名被拒、主机名空白与解析失败收敛、宽松口径（{@code isBlockedTarget}）
 * 的 fail-open 与单次解析。全部用例离线可跑（IP 字面量与解析器缝，不发真实网络请求）。</p>
 */
class EgressTrustPolicyTest {

    // ==================== 严格口径（平台自有出网请求） ====================

    @Test
    void should_rejectTarget_when_resolveTrusted_given_loopbackLiteral() {
        // given & when & then
        assertTrue(blocked("127.0.0.1"));
    }

    @Test
    void should_rejectTarget_when_resolveTrusted_given_anyLocalLiteral() {
        // given & when & then（0.0.0.0 本机通配）
        assertTrue(blocked("0.0.0.0"));
    }

    @Test
    void should_rejectTarget_when_resolveTrusted_given_cloudMetadataLiteral() {
        // given & when & then（链路本地 169.254.169.254 = 云元数据地址）
        assertTrue(blocked("169.254.169.254"));
    }

    @Test
    void should_rejectTarget_when_resolveTrusted_given_multicastLiteral() {
        // given & when & then
        assertTrue(blocked("224.0.0.1"));
    }

    @Test
    void should_rejectTarget_when_resolveTrusted_given_rfc1918Literals() {
        // given（RFC1918 三段 + IPv6 唯一本地地址）
        // when & then
        assertTrue(blocked("10.0.0.5"));
        assertTrue(blocked("172.16.0.1"));
        assertTrue(blocked("172.31.255.254"));
        assertTrue(blocked("192.168.1.10"));
        assertTrue(blocked("fc00::1"));
        assertTrue(blocked("fd12:3456::1"));
    }

    @Test
    void should_rejectTarget_when_resolveTrusted_given_dockerHostAliasLiteral() {
        // given & when & then（容器宿主别名：主机名判定，先于解析即拒）
        assertTrue(blocked("host.docker.internal"));
        assertTrue(blocked("HOST.DOCKER.INTERNAL"));
    }

    @Test
    void should_allowTarget_when_resolveTrusted_given_privateLiteralAndWhitelistEnabled() {
        // given（内网自建场景显式白名单）
        // when
        List<InetAddress> resolved = EgressTrustPolicy.resolveTrusted("10.0.0.5", true);

        // then
        assertEquals(1, resolved.size());
        assertTrue(EgressTrustPolicy.isPrivateNetwork(resolved.get(0)));
    }

    @Test
    void should_throw_when_resolveTrusted_given_blankHost() {
        // given（主机名空白：配置错误，直接失败）
        // when & then
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> EgressTrustPolicy.resolveTrusted("   ", false));
        assertTrue(e.getMessage().contains("主机名为空"));
    }

    @Test
    void should_throw_when_resolveTrusted_given_unresolvableHost() {
        // given & when & then（.invalid 顶级域恒不解析；收敛为 IllegalStateException）
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> EgressTrustPolicy.resolveTrusted("no-such-host.invalid", false));
        assertTrue(e.getMessage().contains("无法解析"));
    }

    @Test
    void should_throw_when_resolveTrusted_given_resolverReturnsEmpty() {
        // given（解析结果为空：等价于无法解析）
        // when & then
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> EgressTrustPolicy.resolveTrusted("empty.test", false, host -> new InetAddress[0]));
        assertTrue(e.getMessage().contains("解析结果为空"));
    }

    @Test
    void should_resolveOnce_when_resolveTrusted_given_resolverSeam() throws UnknownHostException {
        // given（单次解析：判定与返回值共用同一次解析结果）
        AtomicInteger calls = new AtomicInteger();
        EgressTrustPolicy.HostResolver resolver = host -> {
            calls.incrementAndGet();
            return new InetAddress[]{InetAddress.getByName("93.184.216.34")};
        };

        // when
        List<InetAddress> resolved = EgressTrustPolicy.resolveTrusted("mcp.example.test", false, resolver);

        // then
        assertEquals(1, calls.get());
        assertEquals("93.184.216.34", resolved.get(0).getHostAddress());
    }

    // ==================== 宽松口径（连接由框架持有的场景） ====================

    @Test
    void should_block_when_isBlockedTarget_given_privateLiteralAndWhitelistOff() {
        // given & when & then
        assertTrue(EgressTrustPolicy.isBlockedTarget("192.168.1.10", false));
        assertTrue(EgressTrustPolicy.isBlockedTarget("host.docker.internal", false));
    }

    @Test
    void should_notBlock_when_isBlockedTarget_given_whitelistEnabled() {
        // given & when & then（白名单开启：不解析、不拦截）
        assertFalse(EgressTrustPolicy.isBlockedTarget("192.168.1.10", true));
        assertFalse(EgressTrustPolicy.isBlockedTarget("host.docker.internal", true));
    }

    @Test
    void should_notBlock_when_isBlockedTarget_given_unresolvableHost() {
        // given（无法解析 → 无法判定，fail-open：连接同样无法建立，交由 D12 降级处理）
        // when & then
        assertFalse(EgressTrustPolicy.isBlockedTarget("no-such-host.invalid", false));
        assertFalse(EgressTrustPolicy.isBlockedTarget("  ", false));
        assertFalse(EgressTrustPolicy.isBlockedTarget("93.184.216.34", false));
    }

    /** 目标字面量是否被严格口径拒绝。 */
    private static boolean blocked(String host) {
        return assertThrows(IllegalStateException.class,
                () -> EgressTrustPolicy.resolveTrusted(host, false)).getMessage().contains("超出信任边界");
    }
}