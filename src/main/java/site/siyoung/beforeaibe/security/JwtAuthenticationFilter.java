package site.siyoung.beforeaibe.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;

import java.io.IOException;
import java.util.List;
import java.util.Set;

// Authorization: Bearer 액세스 토큰을 읽어 인증 정보를 채움. 서블릿 필터로 중복 등록되지 않도록 빈이 아니라 SecurityConfig에서 생성
// 인증하지 않고 넘긴 요청이 보호된 API에 닿으면 RestAuthenticationEntryPoint가 아래 속성의 오류로 응답함
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String ERROR_ATTRIBUTE = JwtAuthenticationFilter.class.getName() + ".ERROR";

    private static final String BEARER_PREFIX = "Bearer ";

    // 비밀번호 변경이 필요한 사용자가 호출할 수 있는 API
    private static final Set<String> ALLOWED_BEFORE_PASSWORD_CHANGE = Set.of(
            "GET /api/v1/me",
            "PUT /api/v1/me/password",
            "POST /api/v1/auth/logout");

    private final JwtProvider jwtProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            authenticate(header.substring(BEARER_PREFIX.length()).trim(), request);
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(String token, HttpServletRequest request) {
        try {
            Claims claims = jwtProvider.parse(token);
            if (jwtProvider.isTemporary(claims)) {
                throw new ApiException(ErrorCode.OTP_REQUIRED);
            }
            AuthUser authUser = jwtProvider.toAuthUser(claims);
            if (authUser.passwordChangeRequired() && !isAllowedBeforePasswordChange(request)) {
                throw new ApiException(ErrorCode.INVALID_STATE, "비밀번호를 먼저 변경해야 합니다");
            }
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    authUser, null, List.of(new SimpleGrantedAuthority("ROLE_" + authUser.role().name()))));
        } catch (ApiException e) {
            request.setAttribute(ERROR_ATTRIBUTE, e);
        }
    }

    private boolean isAllowedBeforePasswordChange(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return ALLOWED_BEFORE_PASSWORD_CHANGE.contains(request.getMethod() + " " + path);
    }
}
