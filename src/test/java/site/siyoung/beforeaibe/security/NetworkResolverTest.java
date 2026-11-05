package site.siyoung.beforeaibe.security;

import org.junit.jupiter.api.Test;
import site.siyoung.beforeaibe.entity.enums.Network;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import jakarta.servlet.http.HttpServletRequest;

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

    // --- 프록시 헤더 신뢰 모드 ---

    @Test
    void 프록시_신뢰모드는_헤더로_내부외부를_판별한다() {
        NetworkResolver proxied = new NetworkResolver(List.of("100.64.0.0/10"), true, "X-Access-Network");
        HttpServletRequest internal = mock(HttpServletRequest.class);
        when(internal.getHeader("X-Access-Network")).thenReturn("internal");
        HttpServletRequest external = mock(HttpServletRequest.class);
        when(external.getHeader("X-Access-Network")).thenReturn("external");
        HttpServletRequest none = mock(HttpServletRequest.class);
        when(none.getHeader("X-Access-Network")).thenReturn(null);

        assertThat(proxied.resolve(internal)).isEqualTo(Network.INTERNAL);
        assertThat(proxied.resolve(external)).isEqualTo(Network.EXTERNAL);
        // 신뢰 프록시를 거치지 않아 헤더가 없으면 보수적으로 EXTERNAL
        assertThat(proxied.resolve(none)).isEqualTo(Network.EXTERNAL);
    }

    @Test
    void 프록시_신뢰모드는_XForwardedFor_첫IP를_클라이언트IP로_쓴다() {
        NetworkResolver proxied = new NetworkResolver(List.of("100.64.0.0/10"), true, "X-Access-Network");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.9, 10.0.0.1, 127.0.0.1");

        assertThat(proxied.clientIp(request)).isEqualTo("203.0.113.9");
    }

    @Test
    void XForwardedFor가_없으면_소켓주소를_클라이언트IP로_쓴다() {
        NetworkResolver proxied = new NetworkResolver(List.of("100.64.0.0/10"), true, "X-Access-Network");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("100.64.0.5");

        assertThat(proxied.clientIp(request)).isEqualTo("100.64.0.5");
    }

    @Test
    void 프록시_불신뢰모드는_XForwardedFor를_무시하고_소켓주소를_쓴다() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("100.64.0.5");

        // resolver는 프록시 불신뢰(기본). XFF가 있어도 보지 않는다.
        assertThat(resolver.clientIp(request)).isEqualTo("100.64.0.5");
    }
}
