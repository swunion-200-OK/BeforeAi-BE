package site.siyoung.beforeaibe.service;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import site.siyoung.beforeaibe.dto.admin.KillAllResponse;
import site.siyoung.beforeaibe.entity.Execution;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.Task;
import site.siyoung.beforeaibe.entity.enums.ExecutionStatus;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;
import site.siyoung.beforeaibe.entity.enums.TaskStatus;
import site.siyoung.beforeaibe.repository.ExecutionRepository;
import site.siyoung.beforeaibe.repository.RequestRepository;
import site.siyoung.beforeaibe.webshell.WebshellClient;

// 전사 실행 일괄 중단 (CORE-11). ADMIN 전용, 외부망에서도 호출 가능.
// 웹쉘이 중단한 실행 세션을 단건 킬 스위치(RequestExecutionService.kill)와 같은 방식으로 바로 KILLED로 반영함.
// 단건 킬은 명세상 RUNNING에서만 호출할 수 있지만, 전사 중단은 웹쉘이 이미 멈춘 실행을 기록하는 것이므로
// 문서 롤백 실행(ROLLING_BACK)도 KILLED로 남김. 그러지 않으면 요청이 롤백 중으로 계속 남음
@Slf4j
@Service
@RequiredArgsConstructor
public class KillSwitchService {

    // 아직 시작하지 않아 중단으로 실행되지 않는 작업
    private static final Set<TaskStatus> NOT_STARTED = EnumSet.of(TaskStatus.PENDING, TaskStatus.APPROVED);
    // 웹쉘에서 실행 중인 요청 상태 (문서 롤백 Plan 실행 포함)
    private static final Set<RequestStatus> EXECUTING = EnumSet.of(RequestStatus.RUNNING, RequestStatus.ROLLING_BACK);

    private final WebshellClient webshellClient;
    private final ExecutionRepository executionRepository;
    private final RequestRepository requestRepository;
    private final RequestStreamService requestStreamService;
    private final RequestStatusEventPublisher requestStatusEventPublisher;

    // 웹쉘이 중단을 받아들인 뒤에만 상태를 바꿈. 웹쉘 호출이 실패하면 502이고 바뀐 것은 없음
    @Transactional
    public KillAllResponse killAll(String reason) {
        WebshellClient.KillAllResult result = webshellClient.killAll(reason);
        List<UUID> executionIds = result == null || result.executionIds() == null ? List.of() : result.executionIds();
        for (UUID webshellExecutionId : executionIds) {
            executionRepository.findRequestIdByWebshellExecutionId(webshellExecutionId)
                    .ifPresentOrElse(requestId -> kill(requestId, webshellExecutionId, reason),
                            () -> log.warn("웹쉘이 중단했다고 알린 실행 세션을 찾을 수 없음: executionId={}",
                                    webshellExecutionId));
        }
        return new KillAllResponse("KILLED", executionIds);
    }

    // 같은 요청의 콜백·단건 중단과 겹치지 않도록 요청 행을 먼저 잠그고, 그 뒤에 실행 세션의 최신 상태를 읽음
    private void kill(UUID requestId, UUID webshellExecutionId, String reason) {
        Request request = requestRepository.findByIdForUpdate(requestId).orElseThrow();
        Execution execution = executionRepository.findByWebshellExecutionId(webshellExecutionId).orElseThrow();
        if (execution.getStatus() != ExecutionStatus.RUNNING) {
            return;
        }
        execution.kill(reason);
        RequestStatus previousStatus = request.getStatus();
        boolean killed = EXECUTING.contains(request.getStatus());
        if (killed) {
            request.kill();
        }
        for (Task task : execution.getPlan().getTasks()) {
            if (NOT_STARTED.contains(task.getStatus())) {
                task.changeStatus(TaskStatus.SKIPPED);
            } else if (task.getStatus() == TaskStatus.RUNNING) {
                // 실행 중이던 작업은 끝까지 가지 못했으므로 실패로 남김
                task.changeStatus(TaskStatus.FAILED);
            }
        }
        requestStreamService.publishTreeReplaced(request);
        if (killed) {
            requestStatusEventPublisher.publish(request, previousStatus, null);
        }
    }
}
