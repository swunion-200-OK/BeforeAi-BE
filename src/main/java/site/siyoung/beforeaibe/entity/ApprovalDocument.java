package site.siyoung.beforeaibe.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

// 결재 문서. 프롬프트 요청 하나당 하나이며, 판은 ApprovalDocumentVersion에 쌓이고 currentVersion이 최신 판.
// 3.7 프로젝트 문서(Document)와는 다른 개념이라 이름을 나눔
@Getter
@Entity
@Table(
        name = "approval_documents",
        uniqueConstraints = @UniqueConstraint(name = "uk_approval_documents_request", columnNames = "request_id")
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApprovalDocument extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private Request request;

    @Column(nullable = false)
    private int currentVersion;

    @Builder
    private ApprovalDocument(Request request, int currentVersion) {
        this.request = request;
        this.currentVersion = currentVersion;
    }

    // 바뀐 Plan이 다시 제출되면 새 판으로 옮김
    public void changeCurrentVersion(int currentVersion) {
        this.currentVersion = currentVersion;
    }
}
