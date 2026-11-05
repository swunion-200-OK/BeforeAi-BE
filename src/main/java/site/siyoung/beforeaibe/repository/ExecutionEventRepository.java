package site.siyoung.beforeaibe.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import site.siyoung.beforeaibe.entity.ExecutionEvent;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ExecutionEventRepository extends JpaRepository<ExecutionEvent, UUID> {

    // 같은 이벤트가 다시 오면 한 번만 반영
    boolean existsByEventId(UUID eventId);

    boolean existsByTask_IdAndType(UUID taskId, String type);

    // 진행 트리의 STEP 노드 (작업별 실행 이벤트)
    List<ExecutionEvent> findByTask_IdInOrderByOccurredAtAscIdAsc(Collection<UUID> taskIds);
}
