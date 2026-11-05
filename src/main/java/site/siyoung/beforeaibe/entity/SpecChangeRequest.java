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
import site.siyoung.beforeaibe.entity.enums.SpecChangeRequestStatus;

import java.util.UUID;

// 직원의 기획서 수정 요청 (EMP-05). 팀장이 반영하면 새 기획서 버전이 생기고, 반려하면 사유를 남김
@Getter
@Entity
@Table(name = "spec_change_requests")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SpecChangeRequest extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requester_id", nullable = false)
    private User requester;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(nullable = false, columnDefinition = "text")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SpecChangeRequestStatus status;

    @Column(columnDefinition = "text")
    private String rejectionReason;

    @Builder
    private SpecChangeRequest(Project project, User requester, String content, String reason,
                              SpecChangeRequestStatus status) {
        this.project = project;
        this.requester = requester;
        this.content = content;
        this.reason = reason;
        this.status = status;
    }

    public void accept() {
        this.status = SpecChangeRequestStatus.ACCEPTED;
    }

    public void reject(String rejectionReason) {
        this.status = SpecChangeRequestStatus.REJECTED;
        this.rejectionReason = rejectionReason;
    }
}
