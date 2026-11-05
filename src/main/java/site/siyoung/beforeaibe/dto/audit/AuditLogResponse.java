package site.siyoung.beforeaibe.dto.audit;

import site.siyoung.beforeaibe.entity.AuditLog;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.Network;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// 감사 로그 항목·상세 (3.9 GET /audit-logs, GET /audit-logs/{logId}). 웹쉘 이벤트처럼 행위자가 없으면 actor는 null
public record AuditLogResponse(
        UUID id,
        Instant at,
        Actor actor,
        String action,
        Target target,
        Network network,
        String ip,
        Map<String, Object> detail
) {

    public static AuditLogResponse from(AuditLog log) {
        User actor = log.getActor();
        UUID requestId = log.getRequest() == null ? null : log.getRequest().getId();
        return new AuditLogResponse(log.getId(), log.getCreatedAt(),
                actor == null ? null : new Actor(actor.getId(), actor.getName()),
                log.getAction(), new Target(requestId, log.getTaskIds()),
                log.getNetwork(), log.getIp(), log.getDetail());
    }

    public record Actor(UUID id, String name) {
    }

    public record Target(UUID requestId, List<UUID> taskIds) {
    }
}
