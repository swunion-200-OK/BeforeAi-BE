package site.siyoung.beforeaibe.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import site.siyoung.beforeaibe.entity.enums.Network;

import java.net.InetAddress;
import java.util.List;

// 출발지 IP로 접속 환경을 판별 (기능 명세서 5.6). 설정된 내부망 대역에 속하면 INTERNAL, 그 외는 모두 EXTERNAL
// 지금은 리버스 프록시가 없으므로 X-Forwarded-For는 믿지 않고 소켓의 원격 주소만 씀
@Component
public class NetworkResolver {

    private final List<Cidr> internalCidrs;

    public NetworkResolver(@Value("${network.internal-cidrs}") List<String> internalCidrs) {
        this.internalCidrs = internalCidrs.stream()
                .map(String::trim)
                .filter(cidr -> !cidr.isEmpty())
                .map(Cidr::parse)
                .toList();
    }

    public Network resolve(HttpServletRequest request) {
        return resolve(request.getRemoteAddr());
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
