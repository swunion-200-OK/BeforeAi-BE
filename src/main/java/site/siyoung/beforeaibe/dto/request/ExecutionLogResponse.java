package site.siyoung.beforeaibe.dto.request;

import site.siyoung.beforeaibe.entity.ExecutionLog;

import java.time.Instant;
import java.util.UUID;

// 실행 로그 한 줄. 웹쉘 서명을 그대로 내려 클라이언트가 위조 여부를 확인할 수 있게 함
public record ExecutionLogResponse(
        long sequence,
        Instant at,
        UUID taskId,
        String message,
        String webshellSignature
) {

    public static ExecutionLogResponse from(ExecutionLog log) {
        return new ExecutionLogResponse(log.getSequence(), log.getLoggedAt(),
                log.getTask() == null ? null : log.getTask().getId(), log.getMessage(), log.getWebshellSignature());
    }
}
