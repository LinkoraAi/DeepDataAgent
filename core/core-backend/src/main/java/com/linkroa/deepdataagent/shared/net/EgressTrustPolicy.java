package com.linkroa.deepdataagent.shared.net;

import org.apache.commons.lang3.StringUtils;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * 出网信任边界判定（共享技术能力，不承载业务逻辑，见 design D8）。
 *
 * <p>对平台主动发起的外部 HTTP 请求统一做目标地址判定，拦截指向平台自身或内网的服务端请求伪造
 * （SSRF）目标：回环、本机通配（{@code 0.0.0.0} / {@code ::}）、链路本地（含云元数据
 * {@code 169.254.169.254}）、组播、RFC1918 私有网段（{@code 10/8}、{@code 172.16/12}、
 * {@code 192.168/16}）与 IPv6 唯一本地地址（{@code fc00::/7}），以及容器宿主别名
 * {@code host.docker.internal}。</p>
 *
 * <p><b>用法约定（DNS 重绑定防护）</b>：解析入口只有一条——判定 MUST 以 {@link #resolveTrusted} 的
 * <b>单次解析结果</b>为据（该次解析的全部地址逐个校验，任一越界即拒绝），MUST NOT 出现「校验用一次解析、
 * 使用再解析一次」两套结果各自判断的局面；重定向逐跳复检时，每一跳目标 MUST 重新走本类判定（跳数另设上限）。
 * 判定结果<b>不参与建连寻址</b>——JDK HTTP 客户端不提供 DNS 注入缝，建连仍以原始主机名发起，该残余窗口
 * 与其兜底见 {@code TrustedEgressClient} 类 javadoc「残余竞态」。</p>
 *
 * <p><b>内网自建场景</b>：内网 MCP / 数据源通过显式白名单开关（{@code allowPrivateNetwork}，
 * 部署面配置见 {@code app.egress.allow-private-network}）放行，而非默认放开——默认拒绝内网地址。</p>
 *
 * <p><b>两条入口的语义差异</b>：{@link #resolveTrusted} 为严格口径（无法解析 / 任一地址越界即失败），
 * 用于平台自有出网请求（校验探测、OAuth discovery / 刷新）；{@link #isBlockedTarget} 为宽松口径
 * （无法解析按「无法判定」放行，fail-open），用于连接由框架持有的场景（MCP 建连）——此时解析失败
 * 意味着连接同样无法建立，交由既有降级语义（design D12）处置。</p>
 */
public final class EgressTrustPolicy {

    /** 容器宿主别名（compose 编排中指向宿主已发布端口，属平台侧内网面）。 */
    private static final String DOCKER_HOST_ALIAS = "host.docker.internal";

    private EgressTrustPolicy() {
    }

    /**
     * 域名解析缝（测试与定制解析入口；生产恒为 {@link InetAddress#getAllByName}）。
     */
    @FunctionalInterface
    public interface HostResolver {

        /**
         * 解析主机名。
         *
         * @param host 目标主机名或 IP 字面量
         * @return 解析得到的全部地址
         * @throws UnknownHostException 无法解析
         */
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /**
     * 单次解析域名并逐个校验地址（不做白名单放行）。
     *
     * @param host 目标主机名或 IP 字面量
     * @return 解析结果（原顺序，全部已通过信任边界校验）
     * @throws IllegalStateException 主机名为空、无法解析、解析结果为空，或任一地址超出信任边界
     */
    public static List<InetAddress> resolveTrusted(String host) {
        return resolveTrusted(host, false);
    }

    /**
     * 单次解析域名并逐个校验地址。
     *
     * @param host                目标主机名或 IP 字面量
     * @param allowPrivateNetwork 白名单开关：{@code true} 时放行内网 / 宿主别名（自建场景显式开启）
     * @return 解析结果（原顺序，全部已通过信任边界校验）
     * @throws IllegalStateException 主机名为空、无法解析、解析结果为空，或任一地址超出信任边界
     */
    public static List<InetAddress> resolveTrusted(String host, boolean allowPrivateNetwork) {
        return resolveTrusted(host, allowPrivateNetwork, InetAddress::getAllByName);
    }

    /**
     * 单次解析域名并逐个校验地址（自定义解析器入口，供离线断言单次解析与逐跳复检）。
     *
     * @param host                目标主机名或 IP 字面量
     * @param allowPrivateNetwork 白名单开关
     * @param resolver            域名解析器
     * @return 解析结果（原顺序，全部已通过信任边界校验）
     * @throws IllegalStateException 主机名为空、无法解析、解析结果为空，或任一地址超出信任边界
     */
    public static List<InetAddress> resolveTrusted(String host, boolean allowPrivateNetwork,
                                                   HostResolver resolver) {
        if (StringUtils.isBlank(host)) {
            throw new IllegalStateException("目标主机名为空");
        }
        String normalized = host.trim();
        if (!allowPrivateNetwork && isBlockedHostName(normalized)) {
            throw new IllegalStateException("目标主机名超出信任边界: " + normalized);
        }
        InetAddress[] resolved = resolveAddresses(normalized, resolver);
        if (!allowPrivateNetwork) {
            for (InetAddress address : resolved) {
                if (isBlocked(address)) {
                    throw new IllegalStateException("目标地址超出信任边界: " + address.getHostAddress());
                }
            }
        }
        return List.of(resolved);
    }

    /**
     * 目标是否超出信任边界（宽松口径：无法解析按「无法判定」放行）。
     *
     * @param host                目标主机名或 IP 字面量（空 → 交由调用方按配置错误处置，本方法返回 false）
     * @param allowPrivateNetwork 白名单开关
     * @return {@code true} = 确证超出信任边界（拒绝连接）
     */
    public static boolean isBlockedTarget(String host, boolean allowPrivateNetwork) {
        if (allowPrivateNetwork || StringUtils.isBlank(host)) {
            return false;
        }
        String normalized = host.trim();
        if (isBlockedHostName(normalized)) {
            return true;
        }
        InetAddress[] resolved;
        try {
            resolved = resolveAddresses(normalized);
        } catch (IllegalStateException e) {
            return false;
        }
        for (InetAddress address : resolved) {
            if (isBlocked(address)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析域名（唯一解析入口，失败与空结果统一收敛为 {@link IllegalStateException}）。
     *
     * @param host 目标主机名或 IP 字面量
     * @return 解析得到的全部地址（原顺序）
     * @throws IllegalStateException 无法解析或解析结果为空
     */
    public static InetAddress[] resolveAddresses(String host) {
        return resolveAddresses(host, InetAddress::getAllByName);
    }

    /**
     * 解析域名（自定义解析器入口）。
     *
     * @param host     目标主机名或 IP 字面量
     * @param resolver 域名解析器
     * @return 解析得到的全部地址（原顺序）
     * @throws IllegalStateException 无法解析或解析结果为空
     */
    public static InetAddress[] resolveAddresses(String host, HostResolver resolver) {
        InetAddress[] resolved;
        try {
            resolved = resolver.resolve(host);
        } catch (Exception e) {
            throw new IllegalStateException("目标主机名无法解析: " + host, e);
        }
        if (resolved == null || resolved.length == 0) {
            throw new IllegalStateException("目标主机名解析结果为空: " + host);
        }
        return resolved;
    }

    /**
     * 地址是否超出信任边界（含 RFC1918 与 IPv6 ULA）。
     *
     * @param address 待判定地址
     * @return {@code true} = 拒绝（回环 / 本机通配 / 链路本地 / 组播 / 私网）
     */
    public static boolean isBlocked(InetAddress address) {
        return address.isLoopbackAddress()
                || address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isMulticastAddress()
                || isPrivateNetwork(address);
    }

    /**
     * 主机名是否超出信任边界（容器宿主别名；IP 字面量的判定在 {@link #isBlocked}）。
     *
     * @param host 目标主机名
     * @return {@code true} = 拒绝
     */
    public static boolean isBlockedHostName(String host) {
        return DOCKER_HOST_ALIAS.equalsIgnoreCase(host);
    }

    /**
     * 是否私有网段（RFC1918 三段 + IPv6 唯一本地地址 {@code fc00::/7}）。
     *
     * @param address 待判定地址
     * @return {@code true} = 私网地址
     */
    public static boolean isPrivateNetwork(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            return first == 10
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168);
        }
        if (bytes.length == 16) {
            return (bytes[0] & 0xFE) == 0xFC;
        }
        return false;
    }
}