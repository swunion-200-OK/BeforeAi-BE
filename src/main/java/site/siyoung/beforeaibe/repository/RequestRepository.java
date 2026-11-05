package site.siyoung.beforeaibe.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;

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
}
