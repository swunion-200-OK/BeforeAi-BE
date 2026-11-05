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
import site.siyoung.beforeaibe.entity.enums.ForbiddenRuleStatus;
import site.siyoung.beforeaibe.entity.enums.TaskType;

import java.util.UUID;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;

// 확정된 부서 금지 규칙 (CORE-09). 서비스는 해제를 "요청"만 할 수 있고 실제 해제는 웹쉘 서버 콘솔에서 함 (5.7)
@Getter
@Entity
@Table(name = "forbidden_rules")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ForbiddenRule extends BaseTimeEntity {

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
    private ForbiddenRuleStatus status;

    @Column(columnDefinition = "text")
    private String releaseReason;

    @Builder
    private ForbiddenRule(Department department, TaskType taskType, String target, String reason,
                          ForbiddenRuleStatus status) {
        this.department = department;
        this.taskType = taskType;
        this.target = target;
        this.reason = reason;
        this.status = status;
    }

    public void requestRelease(String releaseReason) {
        if (status != ForbiddenRuleStatus.CONFIRMED) {
            throw new ApiException(
                    ErrorCode.INVALID_STATE, "이미 해제를 요청한 규칙입니다");
        }
        this.status = ForbiddenRuleStatus.RELEASE_PENDING;
        this.releaseReason = releaseReason;
    }
}
