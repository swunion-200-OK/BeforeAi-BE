package site.siyoung.beforeaibe.dto.dashboard;

import site.siyoung.beforeaibe.entity.enums.RequestStatus;
import site.siyoung.beforeaibe.entity.enums.Risk;

import java.util.Map;

// 역할별 대시보드 (3.9 GET /dashboard, ADM-06·DEP-01·EMP-02). 필드명은 컬렉션 예시를 따름(계약 미정)
// riskCounts·statusCounts는 0건인 항목도 모두 담음
public record DashboardResponse(
        Scope scope,
        long requestCount,
        long pendingApprovalCount,
        Map<Risk, Long> riskCounts,
        Map<RequestStatus, Long> statusCounts
) {

    // 집계 범위. 쿼리 파라미터 ?scope=로도 받음
    public enum Scope {
        COMPANY,
        DEPARTMENT,
        ME
    }

    public static DashboardResponse of(Scope scope, long requestCount, long pendingApprovalCount,
                                       Map<Risk, Long> riskCounts, Map<RequestStatus, Long> statusCounts) {
        return new DashboardResponse(scope, requestCount, pendingApprovalCount, riskCounts, statusCounts);
    }
}
