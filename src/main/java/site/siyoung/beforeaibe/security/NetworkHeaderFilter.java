package site.siyoung.beforeaibe.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// 모든 응답에 X-Network 헤더를 붙임 (API 명세서 1.1). 인증 실패 응답에도 붙도록 시큐리티 필터보다 먼저 실행
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class NetworkHeaderFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Network";

    private final NetworkResolver networkResolver;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        response.setHeader(HEADER_NAME, networkResolver.resolve(request).name());
        filterChain.doFilter(request, response);
    }
}
