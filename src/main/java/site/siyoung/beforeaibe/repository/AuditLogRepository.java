package site.siyoung.beforeaibe.repository;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.siyoung.beforeaibe.entity.AuditLog;
import site.siyoung.beforeaibe.entity.enums.Network;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// 감사 로그 검색 (3.9). scopeDepartmentId·scopeRequesterId는 역할별 조회 범위, 나머지는 사용자가 준 검색 조건.
// 범위와 조건을 모두 AND로 묶어 범위를 넘는 조건은 결과가 좁혀지도록 함. null인 값은 해당 조건 없이 조회.
// 시각 파라미터를 null로 바인딩하면 Postgres가 타입을 추론하지 못하므로 from·to는 서비스에서 항상 값을 채워 넘김.
// 요청이 없는 로그(LOGIN 등)도 남도록 request는 left join으로 거름
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    @Query("""
            select a from AuditLog a
            left join fetch a.actor
            left join a.request r
            where (:scopeDepartmentId is null or a.department.id = :scopeDepartmentId)
              and (:scopeRequesterId is null or r.requesterId = :scopeRequesterId)
              and (:actorId is null or a.actor.id = :actorId)
              and (:departmentId is null or a.department.id = :departmentId)
              and (:projectId is null or a.project.id = :projectId)
              and (:requestId is null or r.id = :requestId)
              and (:action is null or a.action = :action)
              and (:network is null or a.network = :network)
              and a.createdAt >= :from and a.createdAt < :to
            order by a.createdAt desc, a.id desc
            """)
    List<AuditLog> findPage(@Param("scopeDepartmentId") UUID scopeDepartmentId,
                            @Param("scopeRequesterId") UUID scopeRequesterId,
                            @Param("actorId") UUID actorId,
                            @Param("departmentId") UUID departmentId,
                            @Param("projectId") UUID projectId,
                            @Param("requestId") UUID requestId,
                            @Param("action") String action,
                            @Param("network") Network network,
                            @Param("from") Instant from,
                            @Param("to") Instant to,
                            Limit limit);

    // 커서(마지막으로 받은 항목의 createdAt, id) 다음 페이지
    @Query("""
            select a from AuditLog a
            left join fetch a.actor
            left join a.request r
            where (:scopeDepartmentId is null or a.department.id = :scopeDepartmentId)
              and (:scopeRequesterId is null or r.requesterId = :scopeRequesterId)
              and (:actorId is null or a.actor.id = :actorId)
              and (:departmentId is null or a.department.id = :departmentId)
              and (:projectId is null or a.project.id = :projectId)
              and (:requestId is null or r.id = :requestId)
              and (:action is null or a.action = :action)
              and (:network is null or a.network = :network)
              and a.createdAt >= :from and a.createdAt < :to
              and (a.createdAt < :createdAt or (a.createdAt = :createdAt and a.id < :id))
            order by a.createdAt desc, a.id desc
            """)
    List<AuditLog> findPageAfter(@Param("scopeDepartmentId") UUID scopeDepartmentId,
                                 @Param("scopeRequesterId") UUID scopeRequesterId,
                                 @Param("actorId") UUID actorId,
                                 @Param("departmentId") UUID departmentId,
                                 @Param("projectId") UUID projectId,
                                 @Param("requestId") UUID requestId,
                                 @Param("action") String action,
                                 @Param("network") Network network,
                                 @Param("from") Instant from,
                                 @Param("to") Instant to,
                                 @Param("createdAt") Instant createdAt,
                                 @Param("id") UUID id,
                                 Limit limit);

    // 내보내기 전 건수 확인 (10만 건 제한)
    @Query("""
            select count(a) from AuditLog a
            left join a.request r
            where (:scopeDepartmentId is null or a.department.id = :scopeDepartmentId)
              and (:scopeRequesterId is null or r.requesterId = :scopeRequesterId)
              and (:actorId is null or a.actor.id = :actorId)
              and (:departmentId is null or a.department.id = :departmentId)
              and (:projectId is null or a.project.id = :projectId)
              and (:requestId is null or r.id = :requestId)
              and (:action is null or a.action = :action)
              and (:network is null or a.network = :network)
              and a.createdAt >= :from and a.createdAt < :to
            """)
    long countByConditions(@Param("scopeDepartmentId") UUID scopeDepartmentId,
                           @Param("scopeRequesterId") UUID scopeRequesterId,
                           @Param("actorId") UUID actorId,
                           @Param("departmentId") UUID departmentId,
                           @Param("projectId") UUID projectId,
                           @Param("requestId") UUID requestId,
                           @Param("action") String action,
                           @Param("network") Network network,
                           @Param("from") Instant from,
                           @Param("to") Instant to);

    // 내보내기 파일용 전체 조회. 파일 열(행위자·부서·프로젝트 이름)을 위해 함께 가져옴
    @Query("""
            select a from AuditLog a
            left join fetch a.actor
            left join fetch a.department
            left join fetch a.project
            left join a.request r
            where (:scopeDepartmentId is null or a.department.id = :scopeDepartmentId)
              and (:scopeRequesterId is null or r.requesterId = :scopeRequesterId)
              and (:actorId is null or a.actor.id = :actorId)
              and (:departmentId is null or a.department.id = :departmentId)
              and (:projectId is null or a.project.id = :projectId)
              and (:requestId is null or r.id = :requestId)
              and (:action is null or a.action = :action)
              and (:network is null or a.network = :network)
              and a.createdAt >= :from and a.createdAt < :to
            order by a.createdAt desc, a.id desc
            """)
    List<AuditLog> findAllForExport(@Param("scopeDepartmentId") UUID scopeDepartmentId,
                                    @Param("scopeRequesterId") UUID scopeRequesterId,
                                    @Param("actorId") UUID actorId,
                                    @Param("departmentId") UUID departmentId,
                                    @Param("projectId") UUID projectId,
                                    @Param("requestId") UUID requestId,
                                    @Param("action") String action,
                                    @Param("network") Network network,
                                    @Param("from") Instant from,
                                    @Param("to") Instant to);
}
