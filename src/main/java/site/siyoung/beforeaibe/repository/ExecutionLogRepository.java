package site.siyoung.beforeaibe.repository;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.siyoung.beforeaibe.entity.ExecutionLog;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ExecutionLogRepository extends JpaRepository<ExecutionLog, UUID> {

    // sequence는 실행 세션 안에서만 유일하므로, 실행 세션 순서(생성 시각, id) → sequence 순으로 정렬함
    @Query("""
            select l from ExecutionLog l join fetch l.execution e left join fetch l.task
            where l.request.id = :requestId
            order by e.createdAt asc, e.id asc, l.sequence asc
            """)
    List<ExecutionLog> findPage(@Param("requestId") UUID requestId, Limit limit);

    // 커서(마지막으로 받은 로그의 실행 세션 생성 시각, 실행 세션 id, sequence) 다음 페이지
    @Query("""
            select l from ExecutionLog l join fetch l.execution e left join fetch l.task
            where l.request.id = :requestId
              and (e.createdAt > :executionCreatedAt
                   or (e.createdAt = :executionCreatedAt and e.id > :executionId)
                   or (e.createdAt = :executionCreatedAt and e.id = :executionId and l.sequence > :sequence))
            order by e.createdAt asc, e.id asc, l.sequence asc
            """)
    List<ExecutionLog> findPageAfter(@Param("requestId") UUID requestId,
                                     @Param("executionCreatedAt") Instant executionCreatedAt,
                                     @Param("executionId") UUID executionId,
                                     @Param("sequence") long sequence,
                                     Limit limit);

    // 진행 트리 STEP 노드의 logRange: Plan의 실행 세션·작업별 로그 sequence 최솟값·최댓값
    @Query("""
            select l.execution.id as executionId, l.task.id as taskId,
                   min(l.sequence) as fromSequence, max(l.sequence) as toSequence
            from ExecutionLog l
            where l.execution.plan.id = :planId and l.task is not null
            group by l.execution.id, l.task.id
            """)
    List<LogRangeView> findLogRangesByPlanId(@Param("planId") UUID planId);

    interface LogRangeView {
        UUID getExecutionId();

        UUID getTaskId();

        long getFromSequence();

        long getToSequence();
    }
}
