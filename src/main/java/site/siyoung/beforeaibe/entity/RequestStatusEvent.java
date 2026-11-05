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
import lombok.Getter;
import lombok.NoArgsConstructor;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;

import java.time.Instant;
import java.util.UUID;

// 요청 상태 변경 이력(불변). 시스템이 바꾼 상태는 actorId가 null
@Getter
@Entity
@Table(name = "request_status_events")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RequestStatusEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private Request request;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RequestStatus status;

    private UUID actorId;

    @Column(nullable = false, updatable = false)
    private Instant occurredAt;

    static RequestStatusEvent of(Request request, RequestStatus status, UUID actorId) {
        RequestStatusEvent event = new RequestStatusEvent();
        event.request = request;
        event.status = status;
        event.actorId = actorId;
        event.occurredAt = Instant.now();
        return event;
    }
}
