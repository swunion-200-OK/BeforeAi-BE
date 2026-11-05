package site.siyoung.beforeaibe.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import site.siyoung.beforeaibe.entity.enums.RequestSource;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;
import site.siyoung.beforeaibe.security.CurrentActor;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Getter
@Entity
@Table(name = "requests")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Request extends BaseTimeEntity {

    // 웹쉘 실행이 끝난 뒤의 요청 상태. 중단(KILLED)은 kill()로 따로 바꿈
    private static final Set<RequestStatus> EXECUTION_FINAL_STATUSES = EnumSet.of(
            RequestStatus.COMPLETED, RequestStatus.PARTIALLY_COMPLETED, RequestStatus.FAILED,
            RequestStatus.ROLLED_BACK, RequestStatus.ROLLBACK_FAILED);

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID projectId;

    @Column(nullable = false)
    private UUID requesterId;

    @Column(nullable = false, columnDefinition = "text")
    private String prompt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RequestSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RequestStatus status;

    @Column(nullable = false)
    private int analysisAttempts;

    private UUID currentPlanId;

    // AI 분석이 돌려준 확인 질문. 요청자가 PATCH /requests/{id}/plan의 answer로 답하면 비움 (API 명세서 4.1)
    @Column(columnDefinition = "text")
    private String clarification;

    // 진행 상황 트리 버전. 트리가 바뀔 때마다 1씩 늘고 SSE 이벤트 id·Last-Event-ID로 씀 (API 명세서 2.6, 4.9)
    @Column(nullable = false)
    private long progressVersion;

    // 재분석 입력(답변·수정 지시). 분석이 실패해 재시도해도 잃지 않도록 새 Plan이 만들어질 때까지 보관함
    @Column(columnDefinition = "text")
    private String reanalysisInput;

    // 웹쉘이 돌려준 승인 서명 검증 실패 항목
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> failedChecks;

    @OneToMany(mappedBy = "request", cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    private List<RequestStatusEvent> statusEvents = new ArrayList<>();

    @Builder
    private Request(UUID projectId, UUID requesterId, String prompt, RequestSource source,
                    RequestStatus status, int analysisAttempts, UUID currentPlanId) {
        this.projectId = projectId;
        this.requesterId = requesterId;
        this.prompt = prompt;
        this.source = source;
        transition(status);
        this.analysisAttempts = analysisAttempts;
        this.currentPlanId = currentPlanId;
    }

    // 실패한 AI 분석을 다시 돌림. 시도 횟수 상한 검사는 서비스에서 함
    public void retryAnalysis() {
        this.analysisAttempts++;
        transition(RequestStatus.ANALYZING);
    }

    // AI 분석 성공: 생성된 Plan을 현재 Plan으로 두고 검토 대기로 전이
    public void finishAnalysisWithPlan(UUID planId) {
        this.currentPlanId = planId;
        this.reanalysisInput = null;
        transition(RequestStatus.REVIEWING);
    }

    // AI 분석이 되물음(clarification)만 남긴 경우: Plan 없이 검토 대기로 전이(요청자가 answer로 재분석)
    public void finishAnalysisWithoutPlan() {
        transition(RequestStatus.REVIEWING);
    }

    // AI 분석 실패: 재시도 가능한 실패 상태로 전이
    public void failAnalysis() {
        transition(RequestStatus.ANALYSIS_FAILED);
    }

    // Plan 수정·확인 질문 답변 뒤 재분석. 삭제를 반영한 새 Plan이 있으면 현재 Plan으로 바꿈
    public void startReanalysis(UUID planId) {
        this.currentPlanId = planId;
        transition(RequestStatus.ANALYZING);
    }

    // 에디터 변경 요청: 확정된 diff로 만든 Plan을 바로 현재 Plan으로 연결. 상태는 생성 시점 그대로 둠 (API 명세서 3.8)
    public void attachPlan(UUID planId) {
        this.currentPlanId = planId;
    }

    public void submit() {
        this.failedChecks = null;
        transition(RequestStatus.PENDING_APPROVAL);
    }

    // 부분 완료 요청의 실패 작업으로 만든 새 Plan을 승인 대기로 올림
    public void retryFailedTasks(UUID planId) {
        this.currentPlanId = planId;
        transition(RequestStatus.PENDING_APPROVAL);
    }

    // 현재 Plan의 작업이 모두 승인되고 웹쉘이 실행을 받아들이면 실행 중으로 전이
    public void startRunning() {
        transition(RequestStatus.RUNNING);
    }

    // 문서 롤백 Plan(kind=ROLLBACK)의 승인이 모두 모이고 웹쉘이 실행을 받아들이면 롤백 중으로 전이
    public void startRollingBack() {
        transition(RequestStatus.ROLLING_BACK);
    }

    // 웹쉘이 승인 서명 검증(POST /v1/executions 403)을 거부하면 실행 세션 없이 검증 실패로 둠
    public void failVerification(List<String> failedChecks) {
        this.failedChecks = List.copyOf(failedChecks);
        transition(RequestStatus.VERIFICATION_FAILED);
    }

    // 웹쉘 콜백의 최종 상태 이벤트로 실행을 끝냄. 실행이 끝난 뒤의 상태만 받음 (API 명세서 5 콜백)
    public void finishExecution(RequestStatus finalStatus) {
        if (!EXECUTION_FINAL_STATUSES.contains(finalStatus)) {
            throw new IllegalArgumentException("실행 최종 상태가 아닙니다: " + finalStatus);
        }
        transition(finalStatus);
    }

    // 승인권자가 작업을 거부하면 요청을 거부 상태로 둠
    public void reject() {
        transition(RequestStatus.REJECTED);
    }

    // 문서 롤백 Plan(kind=ROLLBACK)을 현재 Plan으로 두고 승인 대기로 올림. 승인 후 웹쉘 실행에서 ROLLING_BACK이 됨
    public void startRollback(UUID planId) {
        this.currentPlanId = planId;
        transition(RequestStatus.PENDING_APPROVAL);
    }

    // 승인자가 수정을 요청하면 요청자가 Plan을 고치도록 검토 단계로 되돌림
    public void returnForChanges() {
        transition(RequestStatus.REVIEWING);
    }

    public void cancel() {
        transition(RequestStatus.CANCELED);
    }

    public void kill() {
        transition(RequestStatus.KILLED);
    }

    public void askClarification(String clarification) {
        this.clarification = clarification;
    }

    public void clearClarification() {
        this.clarification = null;
    }

    // 되물음이 이어져도 앞선 답변이 사라지지 않도록 덧붙임
    public void appendReanalysisInput(String input) {
        this.reanalysisInput = reanalysisInput == null ? input : reanalysisInput + "\n\n" + input;
    }

    private void transition(RequestStatus next) {
        if (next == null || next == this.status) {
            this.status = next;
            return;
        }
        this.status = next;
        statusEvents.add(RequestStatusEvent.of(this, next, CurrentActor.id()));
    }

    public long increaseProgressVersion() {
        return ++progressVersion;
    }
}
