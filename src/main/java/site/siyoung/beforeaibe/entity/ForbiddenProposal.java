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
import site.siyoung.beforeaibe.entity.enums.ForbiddenProposalStatus;
import site.siyoung.beforeaibe.entity.enums.TaskType;

import java.util.UUID;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;

// 팀장이 제안한 부서 금지 작업 (DEP-06). 관리자가 확정하면 ForbiddenRule이 새로 만들어짐
@Getter
@Entity
@Table(name = "forbidden_proposals")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ForbiddenProposal extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "department_id", nullable = false)
    private Department department;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskType taskType;

    @Column(nullable = false, length = 1024)
    private String target;

    @Column(nullable = false, columnDefinition = "text")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ForbiddenProposalStatus status;

    @Column(columnDefinition = "text")
    private String rejectionReason;

    @Builder
    private ForbiddenProposal(Department department, TaskType taskType, String target, String reason,
                              ForbiddenProposalStatus status) {
        this.department = department;
        this.taskType = taskType;
        this.target = target;
        this.reason = reason;
        this.status = status;
    }

    public void confirm() {
        requireOpen();
        this.status = ForbiddenProposalStatus.CONFIRMED;
    }

    public void reject(String rejectionReason) {
        requireOpen();
        this.status = ForbiddenProposalStatus.REJECTED;
        this.rejectionReason = rejectionReason;
    }

    private void requireOpen() {
        if (status != ForbiddenProposalStatus.OPEN) {
            throw new ApiException(
                    ErrorCode.INVALID_STATE, "대기 중인 제안만 처리할 수 있습니다");
        }
    }
}
