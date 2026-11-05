package site.siyoung.beforeaibe.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.siyoung.beforeaibe.dto.auth.AuthTokens;
import site.siyoung.beforeaibe.dto.auth.LoginRequest;
import site.siyoung.beforeaibe.dto.auth.LoginResponse;
import site.siyoung.beforeaibe.dto.auth.OtpVerifyRequest;
import site.siyoung.beforeaibe.dto.auth.TokenResponse;
import site.siyoung.beforeaibe.security.AuthUser;
import site.siyoung.beforeaibe.security.NetworkResolver;
import site.siyoung.beforeaibe.service.AuthService;

import java.time.Duration;

// 리프레시 토큰은 응답 본문 대신 HttpOnly 쿠키로만 주고받음 (API 명세서 1.1)
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public static final String REFRESH_TOKEN_COOKIE = "refresh_token";
    private static final String REFRESH_TOKEN_COOKIE_PATH = "/api/v1/auth";

    private final AuthService authService;
    private final NetworkResolver networkResolver;
    private final Duration refreshTokenTtl;
    private final boolean secureCookie;

    public AuthController(AuthService authService,
                          NetworkResolver networkResolver,
                          @Value("${auth.refresh-token-ttl}") Duration refreshTokenTtl,
                          @Value("${auth.refresh-cookie.secure}") boolean secureCookie) {
        this.authService = authService;
        this.networkResolver = networkResolver;
        this.refreshTokenTtl = refreshTokenTtl;
        this.secureCookie = secureCookie;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                               HttpServletRequest httpRequest) {
        AuthTokens tokens = authService.login(request, networkResolver.resolve(httpRequest));
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (tokens.refreshToken() != null) {
            response.header(HttpHeaders.SET_COOKIE, refreshTokenCookie(tokens.refreshToken()).toString());
        }
        return response.body(LoginResponse.from(tokens));
    }

    @PostMapping("/otp/verify")
    public ResponseEntity<TokenResponse> verifyOtp(@Valid @RequestBody OtpVerifyRequest request,
                                                   HttpServletRequest httpRequest) {
        AuthTokens tokens = authService.verifyOtp(request, networkResolver.resolve(httpRequest));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie(tokens.refreshToken()).toString())
                .body(TokenResponse.from(tokens));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(
            @CookieValue(name = REFRESH_TOKEN_COOKIE, required = false) String refreshToken) {
        AuthTokens tokens = authService.refresh(refreshToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie(tokens.refreshToken()).toString())
                .body(TokenResponse.from(tokens));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal AuthUser authUser,
                                       @CookieValue(name = REFRESH_TOKEN_COOKIE, required = false) String refreshToken) {
        authService.logout(authUser.id(), refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, expiredRefreshTokenCookie().toString())
                .build();
    }

    private ResponseCookie refreshTokenCookie(String value) {
        return baseCookie(value).maxAge(refreshTokenTtl).build();
    }

    private ResponseCookie expiredRefreshTokenCookie() {
        return baseCookie("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String value) {
        return ResponseCookie.from(REFRESH_TOKEN_COOKIE, value)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Strict")
                .path(REFRESH_TOKEN_COOKIE_PATH);
    }
}
