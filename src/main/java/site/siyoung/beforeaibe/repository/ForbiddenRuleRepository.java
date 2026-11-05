package site.siyoung.beforeaibe.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import site.siyoung.beforeaibe.entity.ForbiddenRule;
import site.siyoung.beforeaibe.entity.enums.ForbiddenProposalStatus;
import java.time.Instant;
import java.util.*;

public interface ForbiddenRuleRepository extends JpaRepository<ForbiddenRule, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from ForbiddenRule f where f.id = :id")
    Optional<ForbiddenRule> findByIdForUpdate(@Param("id") UUID id);

    @Query("select f from ForbiddenRule f where f.department.id = :departmentId order by f.createdAt desc, f.id desc")
    List<ForbiddenRule> firstPage(@Param("departmentId") UUID departmentId, Limit limit);

    @Query("""
            select f from ForbiddenRule f where f.department.id = :departmentId
              and (f.createdAt < :createdAt or (f.createdAt = :createdAt and f.id < :id))
            order by f.createdAt desc, f.id desc
            """)
    List<ForbiddenRule> pageAfter(@Param("departmentId") UUID departmentId, @Param("createdAt") Instant createdAt,
                              @Param("id") UUID id, Limit limit);
}
