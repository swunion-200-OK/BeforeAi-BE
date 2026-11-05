package site.siyoung.beforeaibe.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import site.siyoung.beforeaibe.entity.enums.RequestSource;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;

import java.util.UUID;

@Getter
@Entity
@Table(name = "requests")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Request extends BaseTimeEntity {

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

    @Builder
    private Request(UUID projectId, UUID requesterId, String prompt, RequestSource source,
                    RequestStatus status, int analysisAttempts, UUID currentPlanId) {
        this.projectId = projectId;
        this.requesterId = requesterId;
        this.prompt = prompt;
        this.source = source;
        this.status = status;
        this.analysisAttempts = analysisAttempts;
        this.currentPlanId = currentPlanId;
    }

    public void askClarification(String clarification) {
        this.clarification = clarification;
    }

    public void clearClarification() {
        this.clarification = null;
    }

    public long increaseProgressVersion() {
        return ++progressVersion;
    }
}
