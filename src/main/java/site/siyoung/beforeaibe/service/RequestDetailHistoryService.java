package site.siyoung.beforeaibe.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.dto.request.RequestDetailResponse;
import site.siyoung.beforeaibe.dto.request.RequestDetailResponse.Decision;
import site.siyoung.beforeaibe.dto.request.RequestDetailResponse.Kill;
import site.siyoung.beforeaibe.dto.request.RequestDetailResponse.StatusEvent;
import site.siyoung.beforeaibe.entity.Plan;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.RequestStatusEvent;
import site.siyoung.beforeaibe.entity.Task;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.PlanKind;
import site.siyoung.beforeaibe.repository.ApprovalChangeRequestRepository;
import site.siyoung.beforeaibe.repository.ExecutionRepository;
import site.siyoung.beforeaibe.repository.PlanRepository;
import site.siyoung.beforeaibe.repository.RejectionRepository;
import site.siyoung.beforeaibe.repository.RequestStatusEventRepository;
import site.siyoung.beforeaibe.repository.UserRepository;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

// 요청 상세에 붙는 진행 이력(거부·수정 요청·중단·상태 변경·직전 Plan과의 차이)
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RequestDetailHistoryService {

    private final RejectionRepository rejectionRepository;
    private final ApprovalChangeRequestRepository changeRequestRepository;
    private final ExecutionRepository executionRepository;
    private final RequestStatusEventRepository statusEventRepository;
    private final PlanRepository planRepository;
    private final UserRepository userRepository;

    public RequestDetailResponse.History history(Request request, Plan currentPlan) {
        UUID requestId = request.getId();
        Decision rejection = rejectionRepository.findFirstByRequest_IdOrderByCreatedAtDesc(requestId)
                .map(r -> new Decision(r.getRejecter().getName(), r.getReason(), r.getCreatedAt(), r.getTaskIds()))
                .orElse(null);
        Decision changeRequest = changeRequestRepository.findFirstByRequest_IdOrderByCreatedAtDesc(requestId)
                .map(c -> new Decision(c.getRequestedBy().getName(), c.getReason(), c.getCreatedAt(), c.getTaskIds()))
                .orElse(null);

        List<RequestStatusEvent> statusEvents = statusEventRepository.findByRequest_IdOrderByOccurredAtAsc(requestId);
        var execution = executionRepository.findFirstByRequest_IdAndKilledAtNotNullOrderByKilledAtDesc(requestId);
        Set<UUID> actorIds = statusEvents.stream().map(RequestStatusEvent::getActorId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        execution.map(e -> e.getKilledBy()).ifPresent(actorIds::add);
        Map<UUID, String> names = userRepository.findAllById(actorIds).stream()
                .collect(Collectors.toMap(User::getId, User::getName));

        Kill kill = execution.map(e -> new Kill(e.getKilledBy() == null ? null : names.get(e.getKilledBy()),
                e.getKillReason(), e.getKilledAt())).orElse(null);
        List<StatusEvent> events = statusEvents.stream()
                .map(e -> new StatusEvent(e.getStatus(), e.getOccurredAt(),
                        e.getActorId() == null ? null : names.get(e.getActorId())))
                .toList();

        Plan previous = currentPlan == null || currentPlan.getKind() == PlanKind.ROLLBACK ? null
                : previousPlan(requestId, currentPlan.getId());
        return new RequestDetailResponse.History(rejection, changeRequest, kill, events,
                request.getFailedChecks() == null ? List.of() : request.getFailedChecks(),
                previous == null ? List.of() : changedTaskIds(previous, currentPlan),
                previous == null ? List.of() : removedTaskTitles(previous, currentPlan));
    }

    private Plan previousPlan(UUID requestId, UUID currentPlanId) {
        List<Plan> plans = planRepository.findByRequest_IdOrderByCreatedAtAscIdAsc(requestId);
        Plan previous = null;
        for (Plan plan : plans) {
            if (plan.getId().equals(currentPlanId)) {
                return previous;
            }
            previous = plan;
        }
        return null;
    }

    // Plan을 새로 만들면 작업 ID가 바뀌므로 제목·대상으로 같은 작업을 찾고, 내용·유형·위험도가 다르면 바뀐 작업으로 봄
    private List<UUID> changedTaskIds(Plan previous, Plan current) {
        Map<String, Task> before = previous.getTasks().stream()
                .collect(Collectors.toMap(this::identity, Function.identity(), (a, b) -> a));
        return current.getTasks().stream()
                .filter(task -> {
                    Task old = before.get(identity(task));
                    return old == null || old.getType() != task.getType() || old.getRisk() != task.getRisk()
                            || !Objects.equals(old.getContent(), task.getContent());
                })
                .map(Task::getId)
                .toList();
    }

    private List<String> removedTaskTitles(Plan previous, Plan current) {
        Set<String> kept = current.getTasks().stream().map(this::identity).collect(Collectors.toSet());
        return previous.getTasks().stream()
                .filter(task -> !kept.contains(identity(task)))
                .map(Task::getTitle)
                .toList();
    }

    private String identity(Task task) {
        return task.getTitle() + "\u0000" + task.getTarget();
    }
}
