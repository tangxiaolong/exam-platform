package com.exam.platform.common.web;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * 客户端真实 IP 解析。
 *
 * <p>关键安全点：{@code X-Forwarded-For} 是客户端可任意伪造的请求头。
 * 若无条件采信，攻击者每次请求换一个头值即可让 IP 维度限流彻底失效。
 * 因此只有当直连对端本身是<b>可信代理</b>时才解析转发头，否则一律用 socket 对端地址。</p>
 *
 * <p>解析转发链时从右往左找第一个不可信地址：转发链由代理逐跳追加，
 * 右侧才是离我们最近、最可信的一段；左侧才是客户端可伪造的部分。</p>
 */
@Slf4j
public class ClientIpResolver {

    private static final String UNKNOWN = "unknown";

    private final List<CidrBlock> trusted;

    public ClientIpResolver(List<String> trustedCidrs) {
        List<CidrBlock> blocks = new ArrayList<>();
        if (trustedCidrs != null) {
            for (String spec : trustedCidrs) {
                CidrBlock block = CidrBlock.parse(spec);
                if (block == null) {
                    // 配置写错不能阻断启动，但必须留痕，否则会静默退化为「不信任任何代理」
                    log.warn("忽略无法解析的可信代理配置：{}", spec);
                    continue;
                }
                blocks.add(block);
            }
        }
        this.trusted = List.copyOf(blocks);
    }

    public String resolve(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (remote == null || remote.isBlank()) {
            return UNKNOWN;
        }
        String peer = remote.trim();
        if (!isTrusted(peer)) {
            // 直连场景：转发头不可信，直接用对端地址
            return peer;
        }
        List<String> hops = forwardedHops(request);
        if (hops.isEmpty()) {
            String realIp = trimToNull(request.getHeader("X-Real-IP"));
            return realIp == null ? peer : realIp;
        }
        for (int i = hops.size() - 1; i >= 0; i--) {
            String hop = hops.get(i);
            if (!isTrusted(hop)) {
                return hop;
            }
        }
        // 整条链路都是可信代理，退化取最左端
        return hops.get(0);
    }

    private List<String> forwardedHops(HttpServletRequest request) {
        List<String> hops = new ArrayList<>();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (trimToNull(forwarded) == null) {
            return hops;
        }
        for (String piece : forwarded.split(",")) {
            String hop = trimToNull(piece);
            if (hop != null && !"unknown".equalsIgnoreCase(hop)) {
                hops.add(hop);
            }
        }
        return hops;
    }

    private boolean isTrusted(String ip) {
        if (trusted.isEmpty()) {
            return false;
        }
        InetAddress address = parseAddress(ip);
        if (address == null) {
            return false;
        }
        for (CidrBlock block : trusted) {
            if (block.contains(address)) {
                return true;
            }
        }
        return false;
    }

    private InetAddress parseAddress(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        // 只接受字面量地址，避免 getByName 触发 DNS 解析
        String candidate = ip.trim();
        int zone = candidate.indexOf('%');
        if (zone > 0) {
            candidate = candidate.substring(0, zone);
        }
        if (!candidate.matches("[0-9.]+") && !candidate.contains(":")) {
            return null;
        }
        try {
            return InetAddress.getByName(candidate);
        } catch (Exception e) {
            return null;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** CIDR 网段。 */
    private record CidrBlock(byte[] network, int prefixBits) {

        static CidrBlock parse(String spec) {
            if (spec == null || spec.isBlank()) {
                return null;
            }
            String trimmed = spec.trim();
            int slash = trimmed.indexOf('/');
            String addressPart = slash < 0 ? trimmed : trimmed.substring(0, slash).trim();
            try {
                if (!addressPart.matches("[0-9.]+") && !addressPart.contains(":")) {
                    return null;
                }
                byte[] network = InetAddress.getByName(addressPart).getAddress();
                int maxBits = network.length * 8;
                int bits = maxBits;
                if (slash >= 0) {
                    bits = Integer.parseInt(trimmed.substring(slash + 1).trim());
                }
                if (bits < 0 || bits > maxBits) {
                    return null;
                }
                return new CidrBlock(network, bits);
            } catch (Exception e) {
                return null;
            }
        }

        boolean contains(InetAddress address) {
            byte[] candidate = address.getAddress();
            if (candidate.length != network.length) {
                return false;
            }
            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != network[i]) {
                    return false;
                }
            }
            int remainder = prefixBits % 8;
            if (remainder == 0) {
                return true;
            }
            int mask = (0xFF << (8 - remainder)) & 0xFF;
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
