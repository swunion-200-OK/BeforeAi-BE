package site.siyoung.beforeaibe.support;

import java.util.Map;
import java.util.Optional;

// HTTP 요청(메서드 + 경로 템플릿)을 감사 로그의 이벤트 이름으로 바꾼다.
// 프론트 필터가 action 정확 일치로 거르므로 경로 대신 안정적인 이벤트 이름을 쓴다.
// 표에 없는 경로는 비워 두며, 호출 쪽이 기존처럼 "METHOD 경로"로 남긴다.
// (POST /audit-logs/exports, POST /requests/{requestId}/execution은 표에 없어 인터셉터가 경로 행("METHOD 경로")으로 남기며,
// 서비스가 별도로 남기는 이벤트 행과 함께 기록된다.)
public final class AuditActionMapper {

    private static final Map<String, String> ACTIONS = Map.ofEntries(
            Map.entry("POST /api/v1/requests", "REQUEST_CREATED"),
            Map.entry("POST /api/v1/projects/{projectId}/editor/changes", "REQUEST_CREATED"),
            Map.entry("PATCH /api/v1/requests/{requestId}/plan", "PLAN_UPDATED"),
            Map.entry("POST /api/v1/requests/{requestId}/submit", "SUBMITTED"),
            Map.entry("POST /api/v1/requests/{requestId}/approvals", "APPROVAL_SUBMITTED"),
            Map.entry("POST /api/v1/requests/{requestId}/rejections", "REJECTED"),
            Map.entry("POST /api/v1/requests/{requestId}/cancel", "REQUEST_CANCELED"),
            Map.entry("POST /api/v1/requests/{requestId}/kill", "KILLED"),
            Map.entry("POST /api/v1/admin/kill-all", "KILLED"),
            Map.entry("POST /api/v1/requests/{requestId}/rollback", "ROLLBACK_REQUESTED"),
            Map.entry("PUT /api/v1/projects/{projectId}/spec", "SPEC_UPDATED"),
            Map.entry("POST /api/v1/projects/{projectId}/spec/versions/{version}/restore", "SPEC_UPDATED"),
            Map.entry("POST /api/v1/projects/{projectId}/spec/change-requests/{crId}/accept", "SPEC_UPDATED"),
            Map.entry("POST /api/v1/departments/{deptId}/forbidden-proposals", "FORBIDDEN_RULE_PROPOSED"),
            Map.entry("POST /api/v1/admin/forbidden-proposals/{proposalId}/confirm", "FORBIDDEN_RULE_CONFIRMED"),
            Map.entry("POST /api/v1/admin/forbidden-rules", "FORBIDDEN_RULE_CONFIRMED"),
            Map.entry("POST /api/v1/admin/forbidden-proposals/{proposalId}/reject", "FORBIDDEN_RULE_REJECTED"),
            Map.entry("POST /api/v1/admin/forbidden-rules/{ruleId}/release", "FORBIDDEN_RULE_RELEASE_REQUESTED"),
            Map.entry("POST /api/v1/me/signing-keys", "KEY_SUBMITTED"),
            Map.entry("POST /api/v1/admin/signing-keys/{keyId}/register", "KEY_REGISTERED"),
            Map.entry("DELETE /api/v1/admin/signing-keys/{keyId}", "KEY_REVOKED"),
            Map.entry("POST /api/v1/auth/login", "LOGIN"),
            Map.entry("POST /api/v1/auth/otp/verify", "LOGIN"),
            Map.entry("PUT /api/v1/me/password", "ACCOUNT_CHANGED"),
            Map.entry("POST /api/v1/me/otp", "ACCOUNT_CHANGED"),
            Map.entry("POST /api/v1/admin/users", "ACCOUNT_CHANGED"),
            Map.entry("PATCH /api/v1/admin/users/{userId}", "ACCOUNT_CHANGED"),
            Map.entry("POST /api/v1/admin/users/{userId}/reset-password", "ACCOUNT_CHANGED"),
            Map.entry("PUT /api/v1/admin/policy/preset", "POLICY_CHANGED"),
            Map.entry("PUT /api/v1/admin/approval-matrix", "POLICY_CHANGED"));

    private AuditActionMapper() {
    }

    public static Optional<String> map(String method, String routeTemplate) {
        if (method == null || routeTemplate == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(ACTIONS.get(method + " " + routeTemplate));
    }
}
