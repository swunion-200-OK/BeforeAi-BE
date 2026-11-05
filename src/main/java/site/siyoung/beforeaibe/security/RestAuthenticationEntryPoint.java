package site.siyoung.beforeaibe.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import site.siyoung.beforeaibe.dto.common.ErrorResponse;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

// 인증되지 않은 요청을 명세 1.2 오류 형식으로 응답. 필터가 남긴 사유(OTP_REQUIRED 등)가 있으면 그 오류를 씀
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        ApiException error = request.getAttribute(JwtAuthenticationFilter.ERROR_ATTRIBUTE) instanceof ApiException e
                ? e
                : new ApiException(ErrorCode.UNAUTHENTICATED);
        response.setStatus(error.getErrorCode().getStatus().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(),
                ErrorResponse.of(error.getErrorCode(), error.getMessage(), error.getDetails()));
    }
}
