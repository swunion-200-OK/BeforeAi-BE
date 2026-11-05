package site.siyoung.beforeaibe.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditActionMapperTest {

    @Test
    void 요청_생성_경로는_REQUEST_CREATED로_매핑된다() {
        assertThat(AuditActionMapper.map("POST", "/api/v1/requests")).contains("REQUEST_CREATED");
        assertThat(AuditActionMapper.map("POST", "/api/v1/projects/{projectId}/editor/changes"))
                .contains("REQUEST_CREATED");
    }

    @Test
    void 제출_승인_거절_경로가_매핑된다() {
        assertThat(AuditActionMapper.map("POST", "/api/v1/requests/{requestId}/submit")).contains("SUBMITTED");
        assertThat(AuditActionMapper.map("POST", "/api/v1/requests/{requestId}/approvals"))
                .contains("APPROVAL_SUBMITTED");
        assertThat(AuditActionMapper.map("POST", "/api/v1/requests/{requestId}/rejections")).contains("REJECTED");
    }

    @Test
    void 중단_경로_두_개는_모두_KILLED로_매핑된다() {
        assertThat(AuditActionMapper.map("POST", "/api/v1/requests/{requestId}/kill")).contains("KILLED");
        assertThat(AuditActionMapper.map("POST", "/api/v1/admin/kill-all")).contains("KILLED");
    }

    @Test
    void 키_삭제와_계정_변경_경로가_매핑된다() {
        assertThat(AuditActionMapper.map("DELETE", "/api/v1/admin/signing-keys/{keyId}")).contains("KEY_REVOKED");
        assertThat(AuditActionMapper.map("PATCH", "/api/v1/admin/users/{userId}")).contains("ACCOUNT_CHANGED");
        assertThat(AuditActionMapper.map("PUT", "/api/v1/admin/policy/preset")).contains("POLICY_CHANGED");
    }

    @Test
    void 메서드가_다르면_매핑되지_않는다() {
        assertThat(AuditActionMapper.map("GET", "/api/v1/requests")).isEmpty();
    }

    @Test
    void 표에_없는_경로는_비어_있다() {
        assertThat(AuditActionMapper.map("POST", "/api/v1/audit-logs/exports")).isEmpty();
        assertThat(AuditActionMapper.map("POST", "/api/v1/requests/{requestId}/execution")).isEmpty();
        assertThat(AuditActionMapper.map("POST", null)).isEmpty();
    }
}
