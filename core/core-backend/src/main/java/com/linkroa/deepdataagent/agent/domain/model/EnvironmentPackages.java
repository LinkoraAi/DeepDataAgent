package com.linkroa.deepdataagent.agent.domain.model;

import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 运行环境预装依赖（值对象）：{@code config.packages} 的六类包管理器数组。
 * <p>固定六类：{@code apt / cargo / gem / go / npm / pip}，响应按六类全量回显（无数据为空数组）。
 * 版本限定语义：{@code pip} 支持 {@code pandas==2.1.0} 形态（仅 {@code ==} 锁定），
 * {@code npm} 支持 {@code typescript@5.0.0} 形态（含 scoped 包名）；
 * 其余四类使用系统源默认版本（仅裸包名 / 模块名）。</p>
 * <p>紧凑构造器内做条目非空、长度与逐类格式校验，非法抛 {@link IllegalArgumentException}
 * （协议层收敛为 400 校验错误）。</p>
 *
 * @param apt   Debian/Ubuntu 系统包
 * @param cargo Rust crate
 * @param gem   Ruby gem
 * @param go    Go 模块
 * @param npm   Node.js 包（可带 {@code @版本}）
 * @param pip   Python 包（可带 {@code ==版本}）
 */
public record EnvironmentPackages(
        List<String> apt,
        List<String> cargo,
        List<String> gem,
        List<String> go,
        List<String> npm,
        List<String> pip
) {

    /** 单条目长度上限（防御性约束） */
    private static final int MAX_ENTRY_LENGTH = 255;

    /** apt 包名：小写字母数字起始，允许 {@code . + -} */
    private static final Pattern APT_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9+.-]*$");
    /** cargo crate 名：字母数字起始，允许 {@code _ -} */
    private static final Pattern CARGO_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]*$");
    /** gem 名：字母数字起始，允许 {@code . _ -} */
    private static final Pattern GEM_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]*$");
    /** go 模块路径：允许路径分隔与版本段字符（不含 {@code @}，本期取默认版本） */
    private static final Pattern GO_PATTERN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._/-]*$");
    /** npm 包名（含 scoped）+ 可选 {@code @版本} 段 */
    private static final Pattern NPM_PATTERN =
            Pattern.compile("^(@[A-Za-z0-9._-]+/)?[A-Za-z0-9._-]+(@[A-Za-z0-9^~.*>=<!|-]+)?$");
    /** pip 包名（PEP 508 归一字符集）+ 可选 {@code ==版本} 段（仅 == 锁定） */
    private static final Pattern PIP_PATTERN =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]*(==[A-Za-z0-9][A-Za-z0-9._+]*)?$");

    /**
     * 紧凑构造器：条目校验与列表归一（null 收敛为空列表、防御性复制）。
     */
    public EnvironmentPackages {
        apt = normalize(apt, APT_PATTERN, "apt");
        cargo = normalize(cargo, CARGO_PATTERN, "cargo");
        gem = normalize(gem, GEM_PATTERN, "gem");
        go = normalize(go, GO_PATTERN, "go");
        npm = normalize(npm, NPM_PATTERN, "npm");
        pip = normalize(pip, PIP_PATTERN, "pip");
    }

    /**
     * 六类全空的依赖集（缺省 / self_hosted 强制形态）。
     */
    public static EnvironmentPackages empty() {
        return new EnvironmentPackages(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /**
     * 六类均无预装条目。
     */
    public boolean isEmpty() {
        return apt.isEmpty() && cargo.isEmpty() && gem.isEmpty()
                && go.isEmpty() && npm.isEmpty() && pip.isEmpty();
    }

    /** 逐条校验并按不可变列表归一（null 收敛为空列表）。 */
    private static List<String> normalize(List<String> entries, Pattern pattern, String manager) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        return List.copyOf(entries.stream().peek(entry -> {
            if (StringUtils.isBlank(entry)) {
                throw new IllegalArgumentException("packages." + manager + " 条目不能为空白");
            }
            if (entry.length() > MAX_ENTRY_LENGTH) {
                throw new IllegalArgumentException("packages." + manager + " 条目长度不能超过255个字符");
            }
            if (!pattern.matcher(entry).matches()) {
                throw new IllegalArgumentException("packages." + manager + " 条目格式非法: " + entry);
            }
        }).toList());
    }
}
