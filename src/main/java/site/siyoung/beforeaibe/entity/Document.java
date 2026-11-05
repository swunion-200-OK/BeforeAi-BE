package site.siyoung.beforeaibe.entity;

import jakarta.persistence.Column;
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

// 문서모드로 관리하는 프로젝트 문서 (CORE-06). 내용은 DocumentVersion에 버전별로 쌓이고 currentVersion이 적용된 버전
@Getter
@Entity
@Table(
        name = "documents",
        uniqueConstraints = @UniqueConstraint(name = "uk_documents_project_path", columnNames = {"project_id", "path"})
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Document extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, length = 1024)
    private String path;

    @Column(nullable = false)
    private int currentVersion;

    @Builder
    private Document(Project project, String title, String path, int currentVersion) {
        this.project = project;
        this.title = title;
        this.path = path;
        this.currentVersion = currentVersion;
    }

    // 적용·롤백이 끝나면 현재 버전을 옮김
    public void changeCurrentVersion(int currentVersion) {
        this.currentVersion = currentVersion;
    }
}
