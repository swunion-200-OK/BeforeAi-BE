package site.siyoung.beforeaibe.entity;

import jakarta.persistence.Entity;
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

import java.util.UUID;

// 프로젝트 담당자. DB 접근·데이터 수정·배포 작업의 승인권을 가짐 (기능 명세서 5.2)
@Getter
@Entity
@Table(
        name = "project_owners",
        uniqueConstraints = @UniqueConstraint(name = "uk_project_owners_project_user", columnNames = {"project_id", "user_id"})
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectOwner {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Builder
    private ProjectOwner(Project project, User user) {
        this.project = project;
        this.user = user;
    }
}
