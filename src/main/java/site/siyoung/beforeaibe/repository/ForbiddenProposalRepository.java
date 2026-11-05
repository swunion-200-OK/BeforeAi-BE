package site.siyoung.beforeaibe.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import site.siyoung.beforeaibe.entity.ForbiddenProposal;
import site.siyoung.beforeaibe.entity.enums.ForbiddenProposalStatus;
import java.time.Instant;
import java.util.*;

public interface ForbiddenProposalRepository extends JpaRepository<ForbiddenProposal, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from ForbiddenProposal f where f.id = :id")
    Optional<ForbiddenProposal> findByIdForUpdate(@Param("id") UUID id);

    @Query("select f from ForbiddenProposal f where (:status is null or f.status = :status) order by f.createdAt desc, f.id desc")
    List<ForbiddenProposal> firstPage(@Param("status") ForbiddenProposalStatus status, Limit limit);

    @Query("""
            select f from ForbiddenProposal f where (:status is null or f.status = :status)
              and (f.createdAt < :createdAt or (f.createdAt = :createdAt and f.id < :id))
            order by f.createdAt desc, f.id desc
            """)
    List<ForbiddenProposal> pageAfter(@Param("status") ForbiddenProposalStatus status, @Param("createdAt") Instant createdAt,
                              @Param("id") UUID id, Limit limit);
}
