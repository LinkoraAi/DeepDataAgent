package com.linkroa.deepdataagent.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 出网信任边界配置（{@code app.egress}，见 design D8）。
 *
 * <p>MCP 探测 / 刷新调用与 MCP 建连共用同一份目标地址判定，本类承载其部署面开关：
 * {@link #allowPrivateNetwork} 为内网自建场景的显式白名单（默认关闭 = 拒绝回环、
 * 本机通配、链路本地含云元数据、组播与 RFC1918 私网目标）。</p>
 */
@Configuration
@ConfigurationProperties(prefix = "app.egress")
public class EgressProperties {

    /** 出网白名单开关：true = 放行内网 / 容器宿主别名目标（内网自建 MCP / 数据源场景显式开启）。 */
    private boolean allowPrivateNetwork = false;

    /** 重定向最大跳数（逐跳复检，超出即失败）。 */
    private int maxRedirects = 5;

    public boolean isAllowPrivateNetwork() {
        return allowPrivateNetwork;
    }

    public void setAllowPrivateNetwork(boolean allowPrivateNetwork) {
        this.allowPrivateNetwork = allowPrivateNetwork;
    }

    public int getMaxRedirects() {
        return maxRedirects;
    }

    public void setMaxRedirects(int maxRedirects) {
        this.maxRedirects = maxRedirects;
    }
}