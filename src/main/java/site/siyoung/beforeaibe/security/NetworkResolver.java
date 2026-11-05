package site.siyoung.beforeaibe.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import site.siyoung.beforeaibe.entity.enums.Network;

import java.net.InetAddress;
import java.util.List;

// 출발지로 접속 환경(INTERNAL/EXTERNAL)을 판별 (기능 명세서 5.6).
//
// 두 가지 입구를 지원한다:
//  1) 신뢰된 리버스 프록시 뒤: 프록시가 찍어 준 헤더(network.trusted-header, 기본 X-Access-Network)로 판별.
//     - 외부(Nginx/Cloudflare)는 이 헤더를 항상 external로 덮어쓰고,
//     - 내부(tailscale serve 앞단 프록시)는 internal로 덮어쓴다. 외부에서 internal 위조 불가.
//  2) 프록시가 없거나 헤더가 없으면: 소켓 원격 주소를 설정된 내부망 CIDR과 비교(기존 방식, 직접 접속 하위호환).
//
// network.trust-proxy-header=false(기본)면 헤더를 무시하고 항상 소켓 IP만 쓴다.
@Component
public class NetworkResolver {

    private final List<Cidr> internalCidrs;
    private final boolean trustProxyHeader;
    private final String trustedHeader;

    @Autowired
    public NetworkResolver(@Value("${network.internal-cidrs}") List<String> internalCidrs,
                           @Value("${network.trust-proxy-header:false}") boolean trustProxyHeader,
                           @Value("${network.trusted-header:X-Access-Network}") String trustedHeader) {
        this.internalCidrs = internalCidrs.stream()
                .map(String::trim)
                .filter(cidr -> !cidr.isEmpty())
                .map(Cidr::parse)
                .toList();
        this.trustProxyHeader = trustProxyHeader;
        this.trustedHeader = trustedHeader;
    }

    // 소켓 IP 전용(프록시 헤더 불신뢰) 생성자. 테스트·직접 접속 구성용.
    public NetworkResolver(List<String> internalCidrs) {
        this(internalCidrs, false, "X-Access-Network");
    }

    public Network resolve(HttpServletRequest request) {
        if (trustProxyHeader) {
            String value = request.getHeader(trustedHeader);
            if (value != null && !value.isBlank()) {
                return "internal".equalsIgnoreCase(value.trim()) ? Network.INTERNAL : Network.EXTERNAL;
            }
            // 헤더가 없으면(신뢰 프록시를 거치지 않은 비정상 경로) 보수적으로 EXTERNAL
            return Network.EXTERNAL;
        }
        return resolve(request.getRemoteAddr());
    }

    // 감사 로그 등에 남길 실제 클라이언트 IP.
    // 프록시 뒤(trustProxyHeader=true)면 X-Forwarded-For 의 첫 IP(최초 클라이언트)를 쓰고,
    // 없거나 프록시가 없으면 소켓 원격 주소를 쓴다. 프록시가 없을 때 XFF 는 위조 가능하므로 믿지 않는다.
    public String clientIp(HttpServletRequest request) {
        if (trustProxyHeader) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                // "client, proxy1, proxy2" 중 첫 번째가 최초 클라이언트
                String first = forwarded.split(",", 2)[0].trim();
                if (!first.isEmpty()) {
                    return first;
                }
            }
        }
        return request.getRemoteAddr();
    }

    public Network resolve(String remoteAddress) {
        InetAddress address;
        try {
            address = InetAddress.ofLiteral(remoteAddress);
        } catch (IllegalArgumentException | NullPointerException e) {
            return Network.EXTERNAL;
        }
        return internalCidrs.stream().anyMatch(cidr -> cidr.contains(address))
                ? Network.INTERNAL
                : Network.EXTERNAL;
    }

    private record Cidr(byte[] network, int prefixLength) {

        static Cidr parse(String cidr) {
            String[] parts = cidr.split("/", 2);
            byte[] network = InetAddress.ofLiteral(parts[0]).getAddress();
            int prefixLength = parts.length == 2 ? Integer.parseInt(parts[1]) : network.length * 8;
            if (prefixLength < 0 || prefixLength > network.length * 8) {
                throw new IllegalArgumentException("잘못된 CIDR: " + cidr);
            }
            return new Cidr(network, prefixLength);
        }

        boolean contains(InetAddress address) {
            byte[] target = address.getAddress();
            if (target.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (target[i] != network[i]) {
                    return false;
                }
            }
            int remainingBits = prefixLength % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            return (target[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
