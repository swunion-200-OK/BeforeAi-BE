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
import java.util.UUID;

// 승인 후보의 작업 거부 기록 (DEP-03). 문서 작업의 "미적용"도 같은 기록으로 남김
@Getter
@Entity
@Table(name = "rejections")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Rejection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private Request request;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    // 요청 본문이 아니라 로그인한 사용자로 채움
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rejecter_id", nullable = false)
    private User rejecter;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<UUID> taskIds;

    @Column(nullable = false, columnDefinition = "text")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Network network;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Builder
    private Rejection(Request request, Plan plan, User rejecter, List<UUID> taskIds, String reason, Network network) {
        this.request = request;
        this.plan = plan;
        this.rejecter = rejecter;
        this.taskIds = taskIds;
        this.reason = reason;
        this.network = network;
    }
}
