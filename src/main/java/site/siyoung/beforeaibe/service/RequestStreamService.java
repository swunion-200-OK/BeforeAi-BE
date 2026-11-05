package site.siyoung.beforeaibe.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import site.siyoung.beforeaibe.dto.request.ExecutionLogResponse;
import site.siyoung.beforeaibe.dto.request.ProgressNodeResponse;
import site.siyoung.beforeaibe.entity.Request;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

// 요청별 SSE 구독 관리 (진행 상황 트리 4.9, 실행 로그 CORE-03).
// 진행 트리 이벤트 id는 Request.progressVersion이고, 변경마다 1씩 올린 뒤 트랜잭션이 커밋되면 보냄
@Slf4j
@Service
public class RequestStreamService {

    static final String NODE_UPDATED = "node.updated";
    static final String NODE_ADDED = "node.added";
    static final String TREE_REPLACED = "tree.replaced";
    static final String HEARTBEAT = "heartbeat";
    static final String LOG = "log";

    // 명세 4.9: heartbeat는 30초 간격
    private static final long HEARTBEAT_SECONDS = 30;
    // 연결 유지는 heartbeat로 하므로 서버 쪽 타임아웃은 두지 않음
    private static final long NO_TIMEOUT = 0L;

    private final Map<UUID, Set<SseEmitter>> progressEmitters = new ConcurrentHashMap<>();
    private final Map<UUID, Set<SseEmitter>> logEmitters = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public RequestStreamService() {
        heartbeatScheduler.scheduleAtFixedRate(this::sendHeartbeats, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS,
                TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        heartbeatScheduler.shutdownNow();
    }

    // 진행 트리 노드 id (API 명세서 2.6). 이벤트의 nodeId와 트리 조회 응답의 id가 같아야 하므로 한곳에 둠
    public static String requestNodeId(UUID requestId) {
        return "request:" + requestId;
    }

    static String planNodeId(UUID planId) {
        return "plan:" + planId;
    }

    static String taskNodeId(UUID taskId) {
        return "task:" + taskId;
    }

    static String approvalNodeId(UUID approvalId) {
        return "approval:" + approvalId;
    }

    // 승인 대기 노드는 승인 기록이 없으므로 작업 id와 필요 승인권자 순번으로 id를 만듦
    static String pendingApprovalNodeId(UUID taskId, int index) {
        return "approval:pending:" + taskId + ":" + index;
    }

    // STEP은 실행 세션마다 작업 하나에 하나씩 만들므로 실행 세션 id와 작업 id로 id를 만듦
    static String stepNodeId(UUID executionId, UUID taskId) {
        return "step:" + executionId + ":" + taskId;
    }

    // 재연결 때 받은 Last-Event-ID가 현재 version과 다르면 지난 이벤트를 이어 줄 수 없으므로 tree.replaced로 전체 재조회를 지시
    public SseEmitter subscribeProgress(UUID requestId, long currentVersion, Long lastEventId) {
        SseEmitter emitter = register(progressEmitters, requestId);
        if (lastEventId != null && lastEventId != currentVersion) {
            send(progressEmitters, requestId, emitter, treeReplacedEvent(currentVersion));
        }
        return emitter;
    }

    public SseEmitter subscribeLogs(UUID requestId) {
        return register(logEmitters, requestId);
    }

    // 노드의 상태가 바뀜 → node.updated
    public void publishNodeUpdated(Request request, String nodeId, String status) {
        long version = request.increaseProgressVersion();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nodeId", nodeId);
        data.put("status", status);
        data.put("at", Instant.now());
        UUID requestId = request.getId();
        afterCommit(() -> broadcast(progressEmitters, requestId,
                SseEmitter.event().id(String.valueOf(version)).name(NODE_UPDATED).data(data)));
    }

    // 자식 노드가 생김 → node.added (API 명세서 4.9). 웹쉘 작업 이벤트로 STEP이 생길 때 씀
    public void publishNodeAdded(Request request, String parentId, ProgressNodeResponse node) {
        long version = request.increaseProgressVersion();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("parentId", parentId);
        data.put("node", node);
        UUID requestId = request.getId();
        afterCommit(() -> broadcast(progressEmitters, requestId,
                SseEmitter.event().id(String.valueOf(version)).name(NODE_ADDED).data(data)));
    }

    // Plan이 새로 생기거나 여러 노드가 한꺼번에 바뀜 → tree.replaced (클라이언트가 전체 트리를 다시 조회)
    public void publishTreeReplaced(Request request) {
        long version = request.increaseProgressVersion();
        UUID requestId = request.getId();
        afterCommit(() -> broadcast(progressEmitters, requestId, treeReplacedEvent(version)));
    }

    // 실행 로그 한 줄을 구독자에게 보냄. 로그는 웹쉘 콜백(5장)으로 들어오므로 콜백 구현에서 호출함
    public void publishLog(UUID requestId, ExecutionLogResponse logLine) {
        afterCommit(() -> broadcast(logEmitters, requestId, SseEmitter.event().name(LOG).data(logLine)));
    }

    private SseEmitter.SseEventBuilder treeReplacedEvent(long version) {
        return SseEmitter.event().id(String.valueOf(version)).name(TREE_REPLACED).data(Map.of("version", version));
    }

    private SseEmitter register(Map<UUID, Set<SseEmitter>> emitters, UUID requestId) {
        SseEmitter emitter = new SseEmitter(NO_TIMEOUT);
        emitters.computeIfAbsent(requestId, id -> ConcurrentHashMap.newKeySet()).add(emitter);
        Runnable remove = () -> remove(emitters, requestId, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        return emitter;
    }

    private void remove(Map<UUID, Set<SseEmitter>> emitters, UUID requestId, SseEmitter emitter) {
        emitters.computeIfPresent(requestId, (id, set) -> {
            set.remove(emitter);
            return set.isEmpty() ? null : set;
        });
    }

    private void broadcast(Map<UUID, Set<SseEmitter>> emitters, UUID requestId, SseEmitter.SseEventBuilder event) {
        Set<SseEmitter> targets = emitters.get(requestId);
        if (targets == null) {
            return;
        }
        Set.copyOf(targets).forEach(emitter -> send(emitters, requestId, emitter, event));
    }

    private void send(Map<UUID, Set<SseEmitter>> emitters, UUID requestId, SseEmitter emitter,
                      SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            // 연결이 끊긴 구독자는 정리함
            remove(emitters, requestId, emitter);
            emitter.completeWithError(e);
        }
    }

    // 진행 트리는 명세의 heartbeat 이벤트를, 로그 스트림은 이벤트 계약이 없으므로 클라이언트에 전달되지 않는 SSE 주석을 보냄
    private void sendHeartbeats() {
        try {
            progressEmitters.forEach((requestId, set) -> broadcast(progressEmitters, requestId,
                    SseEmitter.event().name(HEARTBEAT).data(Map.of())));
            logEmitters.forEach((requestId, set) -> broadcast(logEmitters, requestId,
                    SseEmitter.event().comment(HEARTBEAT)));
        } catch (RuntimeException e) {
            // 예외가 나가면 이후 heartbeat가 멈추므로 여기서 막음
            log.warn("SSE heartbeat 전송 실패", e);
        }
    }

    // 롤백된 변경을 보내지 않도록 커밋 뒤에 보냄. 트랜잭션 밖에서 부르면 바로 보냄
    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
