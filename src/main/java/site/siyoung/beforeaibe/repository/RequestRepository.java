package site.siyoung.beforeaibe.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;
import site.siyoung.beforeaibe.entity.enums.Risk;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RequestRepository extends JpaRepository<Request, UUID> {

    // 같은 요청의 상태를 동시에 바꾸지 못하도록 행을 잠금(SELECT ... FOR UPDATE)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Request r where r.id = :id")
    Optional<Request> findByIdForUpdate(@Param("id") UUID id);

    // 현재 Plan의 작업 중 승인 후보(requiredApprovers[].candidates)에 사용자가 들어 있는 요청
    @Query(value = """
            select distinct r.id from requests r
            join tasks t on t.plan_id = r.current_plan_id
            where t.required_approvers @> jsonb_build_array(
                    jsonb_build_object('candidates', jsonb_build_array(cast(:userId as text))))
            """, nativeQuery = true)
    List<UUID> findIdsByCurrentPlanCandidate(@Param("userId") UUID userId);

    // 내 승인 대기 작업 목록(3.6 CORE-04)용: 승인 받을 수 있는 상태의 요청 중,
    // 현재 Plan에 PENDING 작업이 있고 그 작업의 승인권자가 ANYONE이거나 후보에 내가 들어 있는 요청 (최신순)
    @Query(value = """
            select distinct r.* from requests r
            join plans p on p.id = r.current_plan_id
            join tasks t on t.plan_id = p.id
            where r.status in ('PENDING_APPROVAL', 'REAPPROVAL_REQUIRED')
              and t.status = 'PENDING'
              and (t.required_approvers @> jsonb_build_array(jsonb_build_object('rule', 'ANYONE'))
                   or t.required_approvers @> jsonb_build_array(
                          jsonb_build_object('candidates', jsonb_build_array(cast(:userId as text)))))
            order by r.created_at desc, r.id desc
            """, nativeQuery = true)
    List<Request> findPendingApprovalRequestsForApprover(@Param("userId") UUID userId);

    // 조회 범위: all이면 전체, 아니면 본인 요청·승인 후보인 요청·departmentId 부서 프로젝트의 요청.
    // requesterId·status·projectId가 null이면 해당 조건 없이 조회
    @Query("""
            select r from Request r
            where (:all = true
                   or r.requesterId = :userId
                   or r.id in :candidateRequestIds
                   or r.projectId in (select p.id from Project p where p.department.id = :departmentId))
              and (:requesterId is null or r.requesterId = :requesterId)
              and (:status is null or r.status = :status)
              and (:projectId is null or r.projectId = :projectId)
            order by r.createdAt desc, r.id desc
            """)
    List<Request> findPage(@Param("all") boolean all,
                           @Param("userId") UUID userId,
                           @Param("candidateRequestIds") Collection<UUID> candidateRequestIds,
                           @Param("departmentId") UUID departmentId,
                           @Param("requesterId") UUID requesterId,
                           @Param("status") RequestStatus status,
                           @Param("projectId") UUID projectId,
                           Limit limit);

    // 커서(마지막으로 받은 항목의 createdAt, id) 다음 페이지
    @Query("""
            select r from Request r
            where (:all = true
                   or r.requesterId = :userId
                   or r.id in :candidateRequestIds
                   or r.projectId in (select p.id from Project p where p.department.id = :departmentId))
              and (:requesterId is null or r.requesterId = :requesterId)
              and (:status is null or r.status = :status)
              and (:projectId is null or r.projectId = :projectId)
              and (r.createdAt < :createdAt or (r.createdAt = :createdAt and r.id < :id))
            order by r.createdAt desc, r.id desc
            """)
    List<Request> findPageAfter(@Param("all") boolean all,
                                @Param("userId") UUID userId,
                                @Param("candidateRequestIds") Collection<UUID> candidateRequestIds,
                                @Param("departmentId") UUID departmentId,
                                @Param("requesterId") UUID requesterId,
                                @Param("status") RequestStatus status,
                                @Param("projectId") UUID projectId,
                                @Param("createdAt") Instant createdAt,
                                @Param("id") UUID id,
                                Limit limit);

    // 대시보드(3.9) 집계 범위: departmentId가 있으면 그 부서 프로젝트의 요청, requesterId가 있으면 그 사람이 요청한 요청,
    // 둘 다 null이면 전체
    @Query("""
            select count(r) from Request r
            where (:departmentId is null
                   or r.projectId in (select p.id from Project p where p.department.id = :departmentId))
              and (:requesterId is null or r.requesterId = :requesterId)
            """)
    long countInScope(@Param("departmentId") UUID departmentId, @Param("requesterId") UUID requesterId);

    @Query("""
            select r.status as status, count(r) as total from Request r
            where (:departmentId is null
                   or r.projectId in (select p.id from Project p where p.department.id = :departmentId))
              and (:requesterId is null or r.requesterId = :requesterId)
            group by r.status
            """)
    List<StatusCount> countByStatusInScope(@Param("departmentId") UUID departmentId,
                                           @Param("requesterId") UUID requesterId);

    // 범위 안 요청들의 현재 Plan 작업 수를 위험도별로 셈
    @Query("""
            select t.risk as risk, count(t) as total from Task t
            where t.plan.id in (
                    select r.currentPlanId from Request r
                    where (:departmentId is null
                           or r.projectId in (select p.id from Project p where p.department.id = :departmentId))
                      and (:requesterId is null or r.requesterId = :requesterId))
            group by t.risk
            """)
    List<RiskCount> countCurrentTasksByRiskInScope(@Param("departmentId") UUID departmentId,
                                                   @Param("requesterId") UUID requesterId);

    // 내가 승인해야 할 대기 작업 수(3.9 대시보드). 3.6 내 승인 대기 목록(findPendingApprovalRequestsForApprover +
    // ApprovalPolicy.isApprover)과 같은 기준이라 목록의 작업 수와 같음: 승인 받을 수 있는 상태의 요청에서
    // 현재 Plan의 PENDING 작업 중 승인권자가 ANYONE이거나 후보에 내가 들어 있는 것
    @Query(value = """
            select count(*) from tasks t
            join requests r on r.current_plan_id = t.plan_id
            where r.status in ('PENDING_APPROVAL', 'REAPPROVAL_REQUIRED')
              and t.status = 'PENDING'
              and (t.required_approvers @> jsonb_build_array(jsonb_build_object('rule', 'ANYONE'))
                   or t.required_approvers @> jsonb_build_array(
                          jsonb_build_object('candidates', jsonb_build_array(cast(:userId as text)))))
            """, nativeQuery = true)
    long countPendingApprovalTasks(@Param("userId") UUID userId);

    // 대시보드 부서별 집계: 부서마다 요청 수, 승인 대기 요청 수, 실행 중 요청 수
    @Query("""
            select p.department.id as departmentId,
                   count(r) as requests,
                   sum(case when r.status in ('PENDING_APPROVAL', 'REAPPROVAL_REQUIRED') then 1 else 0 end) as pendingApprovals,
                   sum(case when r.status in ('RUNNING', 'ROLLING_BACK') then 1 else 0 end) as running
            from Request r, Project p
            where r.projectId = p.id
            group by p.department.id
            """)
    List<DepartmentCount> countByDepartment();

    interface DepartmentCount {
        UUID getDepartmentId();

        long getRequests();

        long getPendingApprovals();

        long getRunning();
    }

    interface StatusCount {
        RequestStatus getStatus();

        long getTotal();
    }

    interface RiskCount {
        Risk getRisk();

        long getTotal();
    }
}
