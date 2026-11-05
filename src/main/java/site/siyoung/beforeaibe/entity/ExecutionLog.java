package site.siyoung.beforeaibe.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

// 웹쉘 서명이 붙은 실행 로그 한 줄 (CORE-03). 진행 상황 트리 STEP 노드의 logRange가 sequence를 가리킴
// 요청 단위로 조회하므로 request를 직접 들고 있음
@Getter
@Entity
@Table(
        name = "execution_logs",
        uniqueConstraints = @UniqueConstraint(name = "uk_execution_logs_execution_sequence", columnNames = {"execution_id", "log_sequence"})
)
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExecutionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "execution_id", nullable = false)
    private Execution execution;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private Request request;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id")
    private Task task;

    // sequence는 SQL 표준 예약어라 컬럼명만 log_sequence로 둠
    @Column(name = "log_sequence", nullable = false)
    private long sequence;

    // 웹쉘이 기록한 시각
    @Column(nullable = false)
    private Instant loggedAt;

    @Column(nullable = false, columnDefinition = "text")
    private String message;

    @Column(nullable = false, columnDefinition = "text")
    private String webshellSignature;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Builder
    private ExecutionLog(Execution execution, Request request, Task task, long sequence, Instant loggedAt,
                         String message, String webshellSignature) {
        this.execution = execution;
        this.request = request;
        this.task = task;
        this.sequence = sequence;
        this.loggedAt = loggedAt;
        this.message = message;
        this.webshellSignature = webshellSignature;
    }
}
