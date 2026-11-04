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
import site.siyoung.beforeaibe.enums.RequestSource;
import site.siyoung.beforeaibe.enums.RequestStatus;

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
}
