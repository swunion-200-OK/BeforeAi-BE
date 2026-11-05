package site.siyoung.beforeaibe.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import site.siyoung.beforeaibe.security.AuditRequestInterceptor;
import site.siyoung.beforeaibe.security.InternalNetworkInterceptor;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final InternalNetworkInterceptor internalNetworkInterceptor;
    private final AuditRequestInterceptor auditRequestInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(internalNetworkInterceptor);
        // FE → BE 요청 감사 로깅. 웹쉘 콜백·헬스체크·API 문서는 제외
        registry.addInterceptor(auditRequestInterceptor)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns(
                        "/api/v1/internal/**",
                        "/api/v1/requests/*/logs/stream",
                        "/api/v1/requests/*/progress/stream",
                        "/actuator/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html");
    }
}
