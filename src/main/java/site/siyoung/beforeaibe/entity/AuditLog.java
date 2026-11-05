package site.siyoung.beforeaibe.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import site.siyoung.beforeaibe.entity.enums.Network;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// 감사 로그 (CORE-08). 상태를 바꾸는 모든 API와 웹쉘 이벤트를 접속 환경·IP와 함께 남기는 불변 기록
// 웹쉘 이벤트처럼 사람이 아닌 행위는 actor·network·ip가 비어 있을 수 있음
@Getter
@Entity
@Table(name = "audit_logs")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    private User actor;

    // APPROVAL_SUBMITTED, ROLLBACK_* 등. 목록이 와일드카드를 포함해 확정되지 않아 문자열로 둠
    @Column(nullable = false, length = 50)
    private String action;

    // 역할별 조회 범위(부서·프로젝트·요청)로 거르기 위한 대상
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "request_id")
    private Request request;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<UUID> taskIds;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Network network;

    @Column(length = 45)
    private String ip;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> detail;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Builder
    private AuditLog(User actor, String action, Department department, Project project, Request request,
                     List<UUID> taskIds, Network network, String ip, Map<String, Object> detail) {
        this.actor = actor;
        this.action = action;
        this.department = department;
        this.project = project;
        this.request = request;
        this.taskIds = taskIds;
        this.network = network;
        this.ip = ip;
        this.detail = detail;
    }
}
