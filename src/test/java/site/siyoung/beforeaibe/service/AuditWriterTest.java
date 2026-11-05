package site.siyoung.beforeaibe.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import site.siyoung.beforeaibe.repository.AuditLogRepository;
import site.siyoung.beforeaibe.repository.RequestRepository;
import site.siyoung.beforeaibe.repository.UserRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AuditWriterTest {

    private final AtomicInteger submitted = new AtomicInteger();
    // 제출된 작업은 실행하지 않고 횟수만 센다 (persist는 이 테스트의 관심사가 아님)
    private final TaskExecutor executor = task -> submitted.incrementAndGet();
    private final AuditWriter writer = new AuditWriter(mock(AuditLogRepository.class), mock(UserRepository.class),
            mock(RequestRepository.class), executor);

    @AfterEach
    void tearDown() {
        // 동기화를 켜지 않은 테스트에서는 clear가 예외를 던짐
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private void recordAfterCommit() {
        writer.recordAfterCommit(null, "TASK_SUCCEEDED", UUID.randomUUID(), List.of(UUID.randomUUID()), null, null,
                Map.of());
    }

    @Test
    void 트랜잭션_동기화가_있으면_커밋_콜백_전에는_제출하지_않고_콜백_후_제출한다() {
        TransactionSynchronizationManager.initSynchronization();

        recordAfterCommit();

        assertThat(submitted).hasValue(0);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(submitted).hasValue(1);
    }

    @Test
    void 롤백되어_커밋_콜백이_없으면_제출하지_않는다() {
        TransactionSynchronizationManager.initSynchronization();

        recordAfterCommit();
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertThat(submitted).hasValue(0);
    }

    @Test
    void 트랜잭션_동기화가_없으면_바로_제출한다() {
        recordAfterCommit();

        assertThat(submitted).hasValue(1);
    }

    @Test
    void 제출이_실패해도_예외를_던지지_않는다() {
        AuditWriter failing = new AuditWriter(mock(AuditLogRepository.class), mock(UserRepository.class),
                mock(RequestRepository.class), task -> {
                    throw new IllegalStateException("큐 가득 참");
                });

        failing.recordAfterCommit(null, "TASK_FAILED", null, List.of(), null, null, Map.of());
    }
}
