package site.siyoung.beforeaibe.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.dto.auth.AuthTokens;
import site.siyoung.beforeaibe.dto.auth.LoginRequest;
import site.siyoung.beforeaibe.dto.auth.OtpVerifyRequest;
import site.siyoung.beforeaibe.entity.RefreshToken;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.Network;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;
import site.siyoung.beforeaibe.repository.RefreshTokenRepository;
import site.siyoung.beforeaibe.repository.UserRepository;
import site.siyoung.beforeaibe.security.JwtProvider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

// TODO(3.9): LOGIN 등 감사 로그는 감사 로그 엔티티를 만들 때 함께 기록
@Service
@Transactional
public class AuthService {

    private static final int REFRESH_TOKEN_BYTES = 32;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final OtpService otpService;
    private final Duration refreshTokenTtl;
    private final Duration externalIdleTimeout;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtProvider jwtProvider,
                       OtpService otpService,
                       @Value("${auth.refresh-token-ttl}") Duration refreshTokenTtl,
                       @Value("${auth.external-idle-timeout}") Duration externalIdleTimeout) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtProvider = jwtProvider;
        this.otpService = otpService;
        this.refreshTokenTtl = refreshTokenTtl;
        this.externalIdleTimeout = externalIdleTimeout;
    }

    // 내부망은 바로 토큰 발급, 외부망은 OTP 확인용 임시 토큰만 발급 (CORE-10)
    public AuthTokens login(LoginRequest request, Network network) {
        // 계정 존재 여부가 드러나지 않도록 사번 없음·비활성·비밀번호 불일치를 같은 오류로 응답
        User user = userRepository.findByEmployeeNumber(request.employeeNumber())
                .filter(User::isActive)
                .filter(found -> passwordEncoder.matches(request.password(), found.getPasswordHash()))
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "사번 또는 비밀번호가 올바르지 않습니다"));

        if (network == Network.EXTERNAL) {
            // OTP 등록은 내부망 전용이라 외부망에서는 등록 없이 로그인할 수 없음
            if (!user.isOtpEnabled()) {
                throw new ApiException(ErrorCode.UNAUTHENTICATED, "외부망 로그인에는 OTP 등록이 필요합니다. 내부망에서 먼저 등록해 주세요");
            }
            return AuthTokens.otpRequired(jwtProvider.createTemporaryToken(user.getId()));
        }
        return issueTokens(user, network);
    }

    public AuthTokens verifyOtp(OtpVerifyRequest request, Network network) {
        UUID userId = jwtProvider.parseTemporaryToken(request.temporaryToken());
        User user = userRepository.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));

        if (!user.isOtpEnabled() || !otpService.verify(user.getOtpSecret(), request.otpCode())) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "OTP 코드가 올바르지 않습니다");
        }
        return issueTokens(user, network);
    }

    // 리프레시 토큰 로테이션: 쓴 토큰은 폐기하고 같은 접속 환경으로 새 토큰을 발급
    public AuthTokens refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
        Instant now = Instant.now();
        // 먼저 잠근 요청이 커밋하면 뒤 요청은 폐기된 토큰을 읽게 되어 401
        RefreshToken token = refreshTokenRepository.findByTokenHashForUpdate(hash(rawRefreshToken))
                .filter(found -> found.isUsable(now))
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));

        // 외부망 세션은 마지막 재발급(=마지막 활동) 후 30분이 지나면 만료 (기능 명세서 5.6)
        if (token.getNetwork() == Network.EXTERNAL
                && token.getCreatedAt().plus(externalIdleTimeout).isBefore(now)) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "외부망 세션이 활동이 없어 만료되었습니다");
        }

        User user = token.getUser();
        if (!user.isActive()) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
        token.revoke(now);
        return issueTokens(user, token.getNetwork());
    }

    // 본인 리프레시 토큰만 폐기. 쿠키가 없거나 이미 폐기된 토큰이어도 성공으로 처리
    public void logout(UUID userId, String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        Instant now = Instant.now();
        refreshTokenRepository.findByTokenHash(hash(rawRefreshToken))
                .filter(token -> token.getUser().getId().equals(userId))
                .ifPresent(token -> token.revoke(now));
    }

    private AuthTokens issueTokens(User user, Network network) {
        String rawRefreshToken = newRawRefreshToken();
        refreshTokenRepository.save(RefreshToken.builder()
                .user(user)
                .tokenHash(hash(rawRefreshToken))
                .network(network)
                .expiresAt(Instant.now().plus(refreshTokenTtl))
                .build());
        return AuthTokens.issued(jwtProvider.createAccessToken(user), rawRefreshToken,
                user.isPasswordChangeRequired());
    }

    private String newRawRefreshToken() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String rawRefreshToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawRefreshToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
