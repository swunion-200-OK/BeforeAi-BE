package site.siyoung.beforeaibe.repository;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.siyoung.beforeaibe.entity.AuditExport;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AuditExportRepository extends JpaRepository<AuditExport, UUID> {

    // 내가 요청한 내보내기 목록 첫 페이지 (GET /audit-logs/exports)
    @Query("""
            select e from AuditExport e
            where e.requester.id = :requesterId
            order by e.createdAt desc, e.id desc
            """)
    List<AuditExport> findFirstPage(@Param("requesterId") UUID requesterId, Limit limit);

    // 커서(마지막으로 받은 항목의 createdAt, id) 다음 페이지
    @Query("""
            select e from AuditExport e
            where e.requester.id = :requesterId
              and (e.createdAt < :createdAt or (e.createdAt = :createdAt and e.id < :id))
            order by e.createdAt desc, e.id desc
            """)
    List<AuditExport> findPageAfter(@Param("requesterId") UUID requesterId,
                                    @Param("createdAt") Instant createdAt,
                                    @Param("id") UUID id,
                                    Limit limit);
}
