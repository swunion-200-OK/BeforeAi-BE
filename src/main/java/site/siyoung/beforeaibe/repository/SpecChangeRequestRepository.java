package site.siyoung.beforeaibe.repository;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.siyoung.beforeaibe.entity.SpecChangeRequest;
import site.siyoung.beforeaibe.entity.enums.SpecChangeRequestStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpecChangeRequestRepository extends JpaRepository<SpecChangeRequest, UUID> {

    // 다른 프로젝트의 수정 요청 ID로 접근하지 못하도록 프로젝트까지 함께 조건으로 씀
    Optional<SpecChangeRequest> findByIdAndProjectId(UUID id, UUID projectId);

    // status가 null이면 상태와 관계없이 조회
    @Query("""
            select c from SpecChangeRequest c
            where c.project.id = :projectId
              and (:status is null or c.status = :status)
            order by c.createdAt desc, c.id desc
            """)
    List<SpecChangeRequest> findPage(@Param("projectId") UUID projectId,
                                     @Param("status") SpecChangeRequestStatus status,
                                     Limit limit);

    // 커서(마지막으로 받은 항목의 createdAt, id) 다음 페이지
    @Query("""
            select c from SpecChangeRequest c
            where c.project.id = :projectId
              and (:status is null or c.status = :status)
              and (c.createdAt < :createdAt or (c.createdAt = :createdAt and c.id < :id))
            order by c.createdAt desc, c.id desc
            """)
    List<SpecChangeRequest> findPageAfter(@Param("projectId") UUID projectId,
                                          @Param("status") SpecChangeRequestStatus status,
                                          @Param("createdAt") Instant createdAt,
                                          @Param("id") UUID id,
                                          Limit limit);
}
