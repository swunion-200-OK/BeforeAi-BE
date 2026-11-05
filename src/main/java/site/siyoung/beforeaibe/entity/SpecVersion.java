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

// 기획서 버전. 수정·복원·수정 요청 반영 때마다 새 버전으로 쌓이는 불변 기록이며 가장 큰 version이 현재 기획서 (5.8)
@Getter
@Entity
@Table(
        name = "project_spec_versions",
        uniqueConstraints = @UniqueConstraint(name = "uk_project_spec_versions_project_version", columnNames = {"project_id", "version"})
)
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SpecVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    // 수정 사유. 최초 기획서는 없음
    @Column(columnDefinition = "text")
    private String reason;

    // 이전 버전을 복원해 만든 버전이면 그 버전 번호
    private Integer restoredFromVersion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Builder
    private SpecVersion(Project project, int version, String body, String reason,
                        Integer restoredFromVersion, User createdBy) {
        this.project = project;
        this.version = version;
        this.body = body;
        this.reason = reason;
        this.restoredFromVersion = restoredFromVersion;
        this.createdBy = createdBy;
    }
}
