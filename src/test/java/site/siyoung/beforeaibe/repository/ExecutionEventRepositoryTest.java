package site.siyoung.beforeaibe.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import site.siyoung.beforeaibe.TestcontainersConfiguration;
import site.siyoung.beforeaibe.config.JpaAuditingConfig;
import site.siyoung.beforeaibe.entity.Execution;
import site.siyoung.beforeaibe.entity.ExecutionEvent;
import site.siyoung.beforeaibe.entity.Plan;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.enums.ExecutionStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
class ExecutionEventRepositoryTest {

    @Autowired
    private ExecutionEventRepository executionEventRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    void eventId로_이미_받은_이벤트인지_확인한다() {
        Execution execution = persistExecution();
        UUID eventId = UUID.randomUUID();
        em.persist(event(execution, eventId));
        em.flush();
        em.clear();

        assertThat(executionEventRepository.existsByEventId(eventId)).isTrue();
        assertThat(executionEventRepository.existsByEventId(UUID.randomUUID())).isFalse();
        ExecutionEvent found = executionEventRepository.findAll().getFirst();
        assertThat(found.getData()).containsEntry("status", "SUCCEEDED");
    }

    @Test
    void 같은_eventId는_두_번_저장되지_않는다() {
        Execution execution = persistExecution();
        UUID eventId = UUID.randomUUID();
        em.persistAndFlush(event(execution, eventId));

        assertThatThrownBy(() -> em.persistAndFlush(event(execution, eventId)))
                .isInstanceOf(org.hibernate.exception.ConstraintViolationException.class);
    }

    private Execution persistExecution() {
        Request request = RepositoryTestSupport.persistRequest(em);
        Plan plan = em.persist(RepositoryTestSupport.plan(request));
        return em.persistAndFlush(Execution.builder()
                .request(request).plan(plan).webshellExecutionId(UUID.randomUUID()).planHash("sha256:4be1")
                .status(ExecutionStatus.RUNNING).webshellSignature("c2ln").build());
    }

    private static ExecutionEvent event(Execution execution, UUID eventId) {
        return ExecutionEvent.builder()
                .eventId(eventId).execution(execution).type("TASK_SUCCEEDED").occurredAt(Instant.now())
                .data(Map.of("status", "SUCCEEDED")).webshellSignature("c2ln").build();
    }
}
