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
import jakarta.persistence.OneToOne;
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

// 결재 문서의 판 하나. 제출(submit)된 Plan 하나에 대응하는 불변 기록이며, 도장은 그 Plan의 Approval로 그림
@Getter
@Entity
@Table(
        name = "approval_document_versions",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_approval_document_versions_document_version",
                        columnNames = {"document_id", "version"}),
                @UniqueConstraint(name = "uk_approval_document_versions_plan", columnNames = "plan_id")
        }
)
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApprovalDocumentVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private ApprovalDocument document;

    @Column(nullable = false)
    private int version;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    // 판이 만들어진 시각 = 그 Plan을 제출한 시각. 문서의 기안일로 씀
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Builder
    private ApprovalDocumentVersion(ApprovalDocument document, int version, Plan plan) {
        this.document = document;
        this.version = version;
        this.plan = plan;
    }
}
