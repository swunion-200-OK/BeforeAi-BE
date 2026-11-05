package site.siyoung.beforeaibe.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.siyoung.beforeaibe.entity.Plan;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlanRepository extends JpaRepository<Plan, UUID> {

    // 상세·진행 트리에서 Task를 따로 조회하지 않도록 함께 가져옴
    @Query("select p from Plan p left join fetch p.tasks where p.id = :id")
    Optional<Plan> findWithTasksById(@Param("id") UUID id);

    // 진행 트리의 Plan v1, v2 … 순서
    List<Plan> findByRequest_IdOrderByCreatedAtAscIdAsc(UUID requestId);
}
