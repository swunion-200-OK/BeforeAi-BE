package site.siyoung.beforeaibe.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.entity.AuditLog;
import site.siyoung.beforeaibe.entity.enums.Network;
import site.siyoung.beforeaibe.repository.AuditLogRepository;
import site.siyoung.beforeaibe.repository.RequestRepository;
import site.siyoung.beforeaibe.repository.UserRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

// 감사 로그 쓰기 공통 경로. 본 요청(API 처리)을 절대 깨지 않도록 요청 스레드 밖(applicationTaskExecutor)에서
// 자기 트랜잭션으로 저장하고, 실패해도 예외를 삼키고 로그만 남긴다.
// department/project는 엔드포인트별 도메인 지식이 필요해 일반 기록(HTTP 요청)에서는 채우지 않는다
// (그 로그는 ADMIN 조회 범위에서만 보인다). requestId가 있으면 상관관계용으로 연결한다.
@Slf4j
@Component
public class AuditWriter {

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final RequestRepository requestRepository;
    private final TaskExecutor taskExecutor;

    public AuditWriter(AuditLogRepository auditLogRepository,
                       UserRepository userRepository,
                       RequestRepository requestRepository,
                       @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor) {
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
        this.requestRepository = requestRepository;
        this.taskExecutor = taskExecutor;
    }

    // 비동기 기록. 호출한 요청 스레드를 블로킹하지 않는다.
    public void record(UUID actorId, String action, UUID requestId, Network network, String ip,
                       Map<String, Object> detail) {
        try {
            taskExecutor.execute(() -> persist(actorId, action, requestId, network, ip, detail));
        } catch (RuntimeException e) {
            // 실행기 큐가 가득 차는 등 제출 자체가 실패해도 본 요청에는 영향 주지 않음
            log.warn("감사 로그 비동기 제출 실패: action={}", action, e);
        }
    }

    // 자기 트랜잭션. 저장 실패는 삼키고 로그만 남겨 본 요청/다른 작업에 전파하지 않는다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persist(UUID actorId, String action, UUID requestId, Network network, String ip,
                        Map<String, Object> detail) {
        try {
            auditLogRepository.save(AuditLog.builder()
                    .actor(actorId == null ? null : userRepository.getReferenceById(actorId))
                    .action(truncate(action))
                    .request(requestId == null ? null : requestRepository.getReferenceById(requestId))
                    .taskIds(List.of())
                    .network(network)
                    .ip(ip)
                    .detail(detail == null ? Map.of() : detail)
                    .build());
        } catch (RuntimeException e) {
            log.warn("감사 로그 저장 실패: action={}", action, e);
        }
    }

    // action 컬럼은 최대 50자
    private static String truncate(String action) {
        if (action == null) {
            return "";
        }
        return action.length() <= 50 ? action : action.substring(0, 50);
    }
}
