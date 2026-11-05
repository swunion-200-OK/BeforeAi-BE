package site.siyoung.beforeaibe.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.Role;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtProvider {

    private static final String CLAIM_TOKEN_TYPE = "tokenType";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_PASSWORD_CHANGE_REQUIRED = "passwordChangeRequired";

    private static final String TYPE_ACCESS = "ACCESS";
    // 외부망 로그인 후 OTP 확인 전까지만 쓰는 임시 토큰. 일반 API 호출에는 쓸 수 없음
    private static final String TYPE_OTP = "OTP";

    private final SecretKey key;
    private final Duration accessTokenTtl;
    private final Duration temporaryTokenTtl;

    public JwtProvider(@Value("${jwt.secret}") String secret,
                       @Value("${jwt.access-token-ttl}") Duration accessTokenTtl,
                       @Value("${jwt.temporary-token-ttl}") Duration temporaryTokenTtl) {
        // HS256에는 32바이트 이상이 필요하며, 짧으면 기동 시점에 실패함
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenTtl = accessTokenTtl;
        this.temporaryTokenTtl = temporaryTokenTtl;
    }

    public String createAccessToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim(CLAIM_TOKEN_TYPE, TYPE_ACCESS)
                .claim(CLAIM_ROLE, user.getRole().name())
                .claim(CLAIM_PASSWORD_CHANGE_REQUIRED, user.isPasswordChangeRequired())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenTtl)))
                .signWith(key)
                .compact();
    }

    public String createTemporaryToken(UUID userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim(CLAIM_TOKEN_TYPE, TYPE_OTP)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(temporaryTokenTtl)))
                .signWith(key)
                .compact();
    }

    // 서명·만료가 올바른 토큰의 클레임. 아니면 401 UNAUTHENTICATED
    public Claims parse(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
    }

    public boolean isTemporary(Claims claims) {
        return TYPE_OTP.equals(claims.get(CLAIM_TOKEN_TYPE, String.class));
    }

    public AuthUser toAuthUser(Claims claims) {
        if (!TYPE_ACCESS.equals(claims.get(CLAIM_TOKEN_TYPE, String.class))) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
        return new AuthUser(
                UUID.fromString(claims.getSubject()),
                Role.valueOf(claims.get(CLAIM_ROLE, String.class)),
                Boolean.TRUE.equals(claims.get(CLAIM_PASSWORD_CHANGE_REQUIRED, Boolean.class)));
    }

    // OTP 확인 API에서 임시 토큰의 사용자 ID를 꺼냄
    public UUID parseTemporaryToken(String token) {
        Claims claims = parse(token);
        if (!isTemporary(claims)) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
        return UUID.fromString(claims.getSubject());
    }
}
