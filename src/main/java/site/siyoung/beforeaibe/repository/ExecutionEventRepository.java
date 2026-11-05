package site.siyoung.beforeaibe.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.siyoung.beforeaibe.entity.ExecutionEvent;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ExecutionEventRepository extends JpaRepository<ExecutionEvent, UUID> {

    // 웹쉘이 같은 이벤트를 다시 보내도 한 번만 반영하기 위한 중복 확인
    boolean existsByEventId(UUID eventId);

    // 진행 트리 STEP 노드를 만들 Plan의 작업 이벤트. 웹쉘이 기록한 시각 순
    @Query("""
            select ev from ExecutionEvent ev join fetch ev.execution e
            where e.plan.id = :planId and ev.task is not null and ev.type in :types
            order by ev.occurredAt asc, ev.createdAt asc
            """)
    List<ExecutionEvent> findTaskEventsByPlanId(@Param("planId") UUID planId,
                                                @Param("types") Collection<String> types);
}
