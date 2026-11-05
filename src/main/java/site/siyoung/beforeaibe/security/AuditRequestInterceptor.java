package site.siyoung.beforeaibe.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import site.siyoung.beforeaibe.service.AuditWriter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

// FE가 BE에 보낸 요청을 감사 로그에 남긴다 (CORE-08 확장).
// 핸들러에 도달해 처리된 요청만 대상으로 하며, afterCompletion에서 상태 코드가 확정된 뒤 기록한다.
// - 인증 실패(401)·없는 경로(404)는 제외: 매핑된 핸들러에 도달한 요청만 기록(인터셉터 특성상 자연히 제외되지만 상태로도 한 번 더 거른다).
// - detail에는 위험할 수 있는 값(요청 본문·쿼리스트링·헤더·토큰)을 절대 넣지 않는다. method/path(템플릿)/status/소요시간만.
// - SecurityContext가 살아 있는 지점이라 로그인 사용자 ID를 바로 읽는다(미인증이면 null).
@Component
@RequiredArgsConstructor
public class AuditRequestInterceptor implements HandlerInterceptor {

    private static final String START_TIME = AuditRequestInterceptor.class.getName() + ".START";

    private final AuditWriter auditWriter;
    private final NetworkResolver networkResolver;

    @Value("${audit.request.enabled:true}")
    private boolean enabled;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_TIME, System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        if (!enabled) {
            return;
        }
        int status = response.getStatus();
        // 401(미인증)·404(없는 경로)는 기록하지 않음
        if (status == HttpServletResponse.SC_UNAUTHORIZED || status == HttpServletResponse.SC_NOT_FOUND) {
            return;
        }

        String method = request.getMethod();
        // 화면을 열 때마다 쌓이는 성공한 조회가 제출·승인 같은 변경 기록을 묻으므로 남기지 않음. 실패한 조회는 남김
        boolean read = "GET".equals(method) || "HEAD".equals(method);
        if (read && status < 400 && ex == null) {
            return;
        }

        String pattern = routeTemplate(request);
        // 실패 상태(4xx/5xx)는 action 끝에 _FAILED를 붙여 내보내기 성공/실패 판정과 맞춘다
        String action = truncate(method + " " + pattern + (status >= 400 ? "_FAILED" : ""));

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("method", method);
        detail.put("path", pattern);
        detail.put("status", status);
        Object start = request.getAttribute(START_TIME);
        if (start instanceof Long startedAt) {
            detail.put("durationMs", System.currentTimeMillis() - startedAt);
        }

        UUID requestId = pathUuid(request, "requestId");
        UUID projectId = pathUuid(request, "projectId");
        if (projectId != null) {
            detail.put("projectId", projectId.toString());
        }

        auditWriter.record(currentUserId(), action, requestId, networkResolver.resolve(request),
                networkResolver.clientIp(request), detail);
    }

    // 경로 템플릿(예: /api/v1/requests/{requestId}). 없으면 raw URI를 UUID→{id}로 정규화해 카디널리티를 줄인다.
    private static String routeTemplate(HttpServletRequest request) {
        Object best = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (best instanceof String pattern && !pattern.isBlank()) {
            return pattern;
        }
        return request.getRequestURI().replaceAll(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "{id}");
    }

    @SuppressWarnings("unchecked")
    private static UUID pathUuid(HttpServletRequest request, String name) {
        Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(vars instanceof Map<?, ?> map)) {
            return null;
        }
        Object value = ((Map<String, String>) map).get(name);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthUser authUser) {
            return authUser.id();
        }
        return null;
    }

    private static String truncate(String action) {
        return action.length() <= 50 ? action : action.substring(0, 50);
    }
}
