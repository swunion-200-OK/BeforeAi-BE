package site.siyoung.beforeaibe.security;

import org.junit.jupiter.api.Test;
import site.siyoung.beforeaibe.entity.enums.Network;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NetworkResolverTest {

    private final NetworkResolver resolver = new NetworkResolver(List.of("100.64.0.0/10", " 10.0.0.0/8 "));

    @Test
    void Tailscale_대역은_내부망으로_판별한다() {
        assertThat(resolver.resolve("100.64.0.1")).isEqualTo(Network.INTERNAL);
        assertThat(resolver.resolve("100.127.255.254")).isEqualTo(Network.INTERNAL);
    }

    @Test
    void 대역_경계_밖은_외부망으로_판별한다() {
        assertThat(resolver.resolve("100.63.255.255")).isEqualTo(Network.EXTERNAL);
        assertThat(resolver.resolve("100.128.0.1")).isEqualTo(Network.EXTERNAL);
        assertThat(resolver.resolve("203.0.113.10")).isEqualTo(Network.EXTERNAL);
    }

    @Test
    void 설정에_추가한_대역도_내부망으로_판별한다() {
        assertThat(resolver.resolve("10.1.2.3")).isEqualTo(Network.INTERNAL);
    }

    @Test
    void 해석할_수_없는_주소와_IPv6는_외부망으로_판별한다() {
        assertThat(resolver.resolve("not-an-ip")).isEqualTo(Network.EXTERNAL);
        assertThat(resolver.resolve((String) null)).isEqualTo(Network.EXTERNAL);
        assertThat(resolver.resolve("::1")).isEqualTo(Network.EXTERNAL);
    }
}
