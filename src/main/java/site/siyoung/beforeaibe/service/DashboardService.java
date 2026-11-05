package site.siyoung.beforeaibe.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.dto.dashboard.DashboardResponse;
import site.siyoung.beforeaibe.dto.dashboard.DashboardResponse.Scope;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;
import site.siyoung.beforeaibe.entity.enums.Risk;
import site.siyoung.beforeaibe.entity.enums.Role;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;
import site.siyoung.beforeaibe.repository.DepartmentRepository;
import site.siyoung.beforeaibe.repository.RequestRepository;
import site.siyoung.beforeaibe.repository.UserRepository;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

// 역할별 대시보드 (3.9, ADM-06·DEP-01·EMP-02)
// ADMIN: 전체·아무 부서·ME / DEPT_MANAGER: 소속 부서·ME / EMPLOYEE: ME. 역할보다 넓은 범위는 403
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    private final RequestRepository requestRepository;
    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;

    public DashboardResponse get(UUID userId, Scope requestedScope, UUID requestedDepartmentId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        Scope scope = requestedScope == null ? defaultScope(user.getRole()) : requestedScope;

        UUID departmentId = null;
        UUID requesterId = null;
        switch (scope) {
            case COMPANY -> {
                if (user.getRole() != Role.ADMIN) {
                    throw new ApiException(ErrorCode.FORBIDDEN_ROLE, "전체 범위는 관리자만 볼 수 있습니다");
                }
            }
            case DEPARTMENT -> departmentId = resolveDepartment(user, requestedDepartmentId);
            case ME -> requesterId = userId;
        }

        long requestCount = requestRepository.countInScope(departmentId, requesterId);

        Map<RequestStatus, Long> statusCounts = new EnumMap<>(RequestStatus.class);
        for (RequestStatus status : RequestStatus.values()) {
            statusCounts.put(status, 0L);
        }
        requestRepository.countByStatusInScope(departmentId, requesterId)
                .forEach(count -> statusCounts.put(count.getStatus(), count.getTotal()));

        Map<Risk, Long> riskCounts = new EnumMap<>(Risk.class);
        for (Risk risk : Risk.values()) {
            riskCounts.put(risk, 0L);
        }
        requestRepository.countCurrentTasksByRiskInScope(departmentId, requesterId)
                .forEach(count -> riskCounts.put(count.getRisk(), count.getTotal()));

        // 승인 대기 건수는 범위와 상관없이 "내가 승인해야 할" 작업 수
        long pendingApprovalCount = requestRepository.countPendingApprovalTasks(userId);

        return DashboardResponse.of(scope, requestCount, pendingApprovalCount, riskCounts, statusCounts);
    }

    private Scope defaultScope(Role role) {
        return switch (role) {
            case ADMIN -> Scope.COMPANY;
            case DEPT_MANAGER -> Scope.DEPARTMENT;
            case EMPLOYEE -> Scope.ME;
        };
    }

    // ADMIN은 departmentId로 아무 부서나 지정(필수). DEPT_MANAGER는 소속 부서만(생략하면 소속 부서)
    private UUID resolveDepartment(User user, UUID requestedDepartmentId) {
        if (user.getRole() == Role.ADMIN) {
            if (requestedDepartmentId == null) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "DEPARTMENT 범위에는 departmentId가 필요합니다");
            }
            if (!departmentRepository.existsById(requestedDepartmentId)) {
                throw new ApiException(ErrorCode.NOT_FOUND, "부서를 찾을 수 없습니다");
            }
            return requestedDepartmentId;
        }
        if (user.getRole() != Role.DEPT_MANAGER || user.getDepartmentId() == null) {
            throw new ApiException(ErrorCode.FORBIDDEN_ROLE, "부서 범위를 볼 권한이 없습니다");
        }
        if (requestedDepartmentId != null && !requestedDepartmentId.equals(user.getDepartmentId())) {
            throw new ApiException(ErrorCode.FORBIDDEN_ROLE, "소속 부서만 볼 수 있습니다");
        }
        return user.getDepartmentId();
    }
}
