package site.siyoung.beforeaibe.dto.dashboard;

import site.siyoung.beforeaibe.entity.enums.RequestStatus;
import site.siyoung.beforeaibe.entity.enums.Risk;

import java.util.List;
import java.util.Map;
import java.util.UUID;

// 역할별 대시보드 (3.9 GET /dashboard, ADM-06·DEP-01·EMP-02). 필드명은 컬렉션 예시를 따름(계약 미정)
// riskCounts·statusCounts는 0건인 항목도 모두 담음
public record DashboardResponse(
        Scope scope,
        long requestCount,
        long pendingApprovalCount,
        Map<Risk, Long> riskCounts,
        Map<RequestStatus, Long> statusCounts,
        List<DepartmentStat> byDepartment
) {

    // 부서별 집계. pendingApprovals는 승인 대기 요청 수, running은 실행 중 요청 수
    public record DepartmentStat(UUID departmentId, String name, long requests, long pendingApprovals, long running) {
    }

    // 집계 범위. 쿼리 파라미터 ?scope=로도 받음
    public enum Scope {
        COMPANY,
        DEPARTMENT,
        ME
    }

    public static DashboardResponse of(Scope scope, long requestCount, long pendingApprovalCount,
                                       Map<Risk, Long> riskCounts, Map<RequestStatus, Long> statusCounts,
                                       List<DepartmentStat> byDepartment) {
        return new DashboardResponse(scope, requestCount, pendingApprovalCount, riskCounts, statusCounts, byDepartment);
    }
}
