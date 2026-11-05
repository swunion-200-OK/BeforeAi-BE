package site.siyoung.beforeaibe.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import site.siyoung.beforeaibe.entity.enums.WebhookType;

import java.util.UUID;

// 관리자가 등록한 outbound webhook 대상. 요청 상태 변화가 커밋되면 enabled=true인 대상으로 비동기 발송함.
// URL은 Slack/Discord Incoming Webhook 주소 같은 비밀값이므로 조회 응답에서 마스킹한다(서비스에서 처리).
@Getter
@Entity
@Table(name = "webhooks")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Webhook extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WebhookType type;

    @Column(nullable = false, columnDefinition = "text")
    private String url;

    @Column(nullable = false)
    private boolean enabled;

    @Builder
    private Webhook(String name, WebhookType type, String url, boolean enabled) {
        this.name = name;
        this.type = type;
        this.url = url;
        this.enabled = enabled;
    }

    public void enable() {
        this.enabled = true;
    }

    public void disable() {
        this.enabled = false;
    }
}
