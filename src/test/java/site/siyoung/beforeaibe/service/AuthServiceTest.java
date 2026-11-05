package site.siyoung.beforeaibe.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import site.siyoung.beforeaibe.dto.auth.AuthTokens;
import site.siyoung.beforeaibe.entity.RefreshToken;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.Network;
import site.siyoung.beforeaibe.entity.enums.Role;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;
import site.siyoung.beforeaibe.repository.RefreshTokenRepository;
import site.siyoung.beforeaibe.repository.UserRepository;
import site.siyoung.beforeaibe.security.JwtProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 외부망 30분 비활동 만료는 시각 조작이 필요해 단위 테스트로 검증
class AuthServiceTest {

    private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
    private final JwtProvider jwtProvider = mock(JwtProvider.class);
    private AuthService authService;
    private User user;

    @BeforeEach
    void setUp() {
        authService = new AuthService(mock(UserRepository.class), refreshTokenRepository, mock(PasswordEncoder.class),
                jwtProvider, mock(OtpService.class), Duration.ofDays(14), Duration.ofMinutes(30));
        user = User.builder()
                .employeeNumber("EMP001")
                .passwordHash("hash")
                .name("홍길동")
                .role(Role.EMPLOYEE)
                .active(true)
                .build();
        when(jwtProvider.createAccessToken(any())).thenReturn("access-token");
        when(refreshTokenRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void 외부망_세션은_30분_동안_활동이_없으면_재발급되지_않는다() {
        RefreshToken token = tokenCreatedAgo(Network.EXTERNAL, Duration.ofMinutes(31));
        when(refreshTokenRepository.findByTokenHashForUpdate(anyString())).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> authService.refresh("raw"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
        assertThat(token.getRevokedAt()).isNull();
    }

    @Test
    void 외부망_세션도_30분_안이면_재발급하고_이전_토큰은_폐기한다() {
        RefreshToken token = tokenCreatedAgo(Network.EXTERNAL, Duration.ofMinutes(10));
        when(refreshTokenRepository.findByTokenHashForUpdate(anyString())).thenReturn(Optional.of(token));

        AuthTokens tokens = authService.refresh("raw");

        assertThat(tokens.accessToken()).isEqualTo("access-token");
        assertThat(tokens.refreshToken()).isNotBlank().isNotEqualTo("raw");
        assertThat(token.getRevokedAt()).isNotNull();
    }

    @Test
    void 내부망_세션은_30분이_지나도_재발급된다() {
        RefreshToken token = tokenCreatedAgo(Network.INTERNAL, Duration.ofHours(2));
        when(refreshTokenRepository.findByTokenHashForUpdate(anyString())).thenReturn(Optional.of(token));

        assertThat(authService.refresh("raw").accessToken()).isEqualTo("access-token");
    }

    private RefreshToken tokenCreatedAgo(Network network, Duration ago) {
        RefreshToken token = RefreshToken.builder()
                .user(user)
                .tokenHash("hash")
                .network(network)
                .expiresAt(Instant.now().plus(Duration.ofDays(1)))
                .build();
        ReflectionTestUtils.setField(token, "createdAt", Instant.now().minus(ago));
        return token;
    }
}
