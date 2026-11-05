package site.siyoung.beforeaibe.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Limit;
import site.siyoung.beforeaibe.TestcontainersConfiguration;
import site.siyoung.beforeaibe.config.JpaAuditingConfig;
import site.siyoung.beforeaibe.entity.Execution;
import site.siyoung.beforeaibe.entity.ExecutionLog;
import site.siyoung.beforeaibe.entity.Plan;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.enums.ExecutionStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
class ExecutionLogRepositoryTest {

    @Autowired
    private ExecutionLogRepository executionLogRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    void 요청의_실행_로그는_실행_세션_순서와_sequence_순으로_이어서_조회된다() {
        Request request = RepositoryTestSupport.persistRequest(em);
        Plan plan = em.persist(RepositoryTestSupport.plan(request));
        Execution first = em.persistAndFlush(execution(request, plan));
        Execution second = em.persistAndFlush(execution(request, plan));
        // 같은 sequence가 실행 세션마다 다시 시작해도 섞이지 않아야 함
        em.persist(log(second, request, 1, "두 번째 실행 1"));
        em.persist(log(first, request, 2, "첫 실행 2"));
        em.persist(log(first, request, 1, "첫 실행 1"));
        // 다른 요청의 로그는 나오지 않아야 함
        Request other = RepositoryTestSupport.persistRequest(em);
        Execution otherExecution = em.persistAndFlush(execution(other, em.persist(RepositoryTestSupport.plan(other))));
        em.persist(log(otherExecution, other, 1, "다른 요청"));
        em.flush();
        em.clear();

        List<ExecutionLog> page = executionLogRepository.findPage(request.getId(), Limit.of(2));
        assertThat(page).extracting(ExecutionLog::getMessage).containsExactly("첫 실행 1", "첫 실행 2");

        ExecutionLog last = page.getLast();
        List<ExecutionLog> next = executionLogRepository.findPageAfter(request.getId(),
                last.getExecution().getCreatedAt(), last.getExecution().getId(), last.getSequence(), Limit.of(10));
        assertThat(next).extracting(ExecutionLog::getMessage).containsExactly("두 번째 실행 1");
    }

    private static Execution execution(Request request, Plan plan) {
        return Execution.builder()
                .request(request).plan(plan).webshellExecutionId(UUID.randomUUID()).planHash("sha256:4be1")
                .status(ExecutionStatus.COMPLETED).webshellSignature("c2ln").build();
    }

    private static ExecutionLog log(Execution execution, Request request, long sequence, String message) {
        return ExecutionLog.builder()
                .execution(execution).request(request).sequence(sequence).loggedAt(Instant.now())
                .message(message).webshellSignature("c2ln").build();
    }
}
