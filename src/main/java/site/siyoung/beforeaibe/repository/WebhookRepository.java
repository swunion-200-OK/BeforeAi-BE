package site.siyoung.beforeaibe.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import site.siyoung.beforeaibe.entity.Webhook;

import java.util.List;
import java.util.UUID;

public interface WebhookRepository extends JpaRepository<Webhook, UUID> {

    // 발송 대상: 활성화된 webhook만 (디스패처에서 사용)
    List<Webhook> findByEnabledTrue();

    // 관리자 목록 조회 (최신 등록 우선)
    List<Webhook> findAllByOrderByCreatedAtDesc();
}
