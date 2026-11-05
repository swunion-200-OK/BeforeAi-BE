package site.siyoung.beforeaibe.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import site.siyoung.beforeaibe.entity.enums.ExecutionStatus;
import site.siyoung.beforeaibe.security.CurrentActor;

import java.time.Instant;
import java.util.UUID;

// 웹쉘이 승인 서명 검증을 통과시켜 연 실행 세션 1건 (POST /v1/executions 202 응답).
// 검증 실패(403)는 세션이 열리지 않으므로 이 기록을 만들지 않고 요청 상태와 감사 로그로만 남김
@Getter
@Entity
@Table(name = "executions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Execution extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private Request request;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    // 웹쉘이 발급한 executionId. 콜백 경로(/internal/executions/{executionId}/events)와 중단 요청에 씀
    @Column(nullable = false, unique = true)
    private UUID webshellExecutionId;

    // 웹쉘이 직접 계산해 돌려준 Plan 해시
    @Column(nullable = false, length = 128)
    private String planHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ExecutionStatus status;

    @Column(nullable = false, columnDefinition = "text")
    private String webshellSignature;

    @Column(columnDefinition = "text")
    private String killReason;

    private UUID killedBy;

    private Instant killedAt;

    @Builder
    private Execution(Request request, Plan plan, UUID webshellExecutionId, String planHash,
                      ExecutionStatus status, String webshellSignature) {
        this.request = request;
        this.plan = plan;
        this.webshellExecutionId = webshellExecutionId;
        this.planHash = planHash;
        this.status = status;
        this.webshellSignature = webshellSignature;
    }

    public void changeStatus(ExecutionStatus status) {
        this.status = status;
    }

    public void kill(String killReason) {
        this.status = ExecutionStatus.KILLED;
        this.killReason = killReason;
        this.killedBy = CurrentActor.id();
        this.killedAt = Instant.now();
    }
}
