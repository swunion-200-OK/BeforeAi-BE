package site.siyoung.beforeaibe.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.ai.dto.AiTask;
import site.siyoung.beforeaibe.ai.dto.AnalysisInput;
import site.siyoung.beforeaibe.ai.dto.AnalysisResult;
import site.siyoung.beforeaibe.entity.ForbiddenRule;
import site.siyoung.beforeaibe.entity.Plan;
import site.siyoung.beforeaibe.entity.Project;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.Task;
import site.siyoung.beforeaibe.entity.enums.PlanKind;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;
import site.siyoung.beforeaibe.entity.enums.Risk;
import site.siyoung.beforeaibe.entity.enums.TaskStatus;
import site.siyoung.beforeaibe.entity.value.TaskContent;
import site.siyoung.beforeaibe.repository.ForbiddenRuleRepository;
import site.siyoung.beforeaibe.repository.PlanRepository;
import site.siyoung.beforeaibe.repository.ProjectRepository;
import site.siyoung.beforeaibe.repository.RequestRepository;
import site.siyoung.beforeaibe.repository.SpecVersionRepository;
import site.siyoung.beforeaibe.service.ApproverResolver;
import site.siyoung.beforeaibe.service.RequestStreamService;
import site.siyoung.beforeaibe.support.UnifiedDiffNormalizer;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// AI 분석의 DB 트랜잭션 작업만 담당(입력 구성 / 결과 반영).
// 비동기 오케스트레이션(RequestAnalysisService)과 분리해 @Transactional 프록시가 제대로 걸리게 한다.
@Service
@RequiredArgsConstructor
@Slf4j
public class AnalysisPersistenceService {

    private static final int FORBIDDEN_RULE_LIMIT = 50;
    // 프롬프트가 무한정 커지지 않도록 자름
    private static final int SPEC_SUMMARY_LIMIT = 4000;
    private static final int PRIOR_TASK_CONTENT_LIMIT = 2000;

    private final RequestRepository requestRepository;
    private final ProjectRepository projectRepository;
    private final PlanRepository planRepository;
    private final ForbiddenRuleRepository forbiddenRuleRepository;
    private final RequestStreamService requestStreamService;
    private final ApproverResolver approverResolver;
    private final SpecVersionRepository specVersionRepository;

    // 분석 입력 구성. 요청이 없거나 ANALYZING이 아니면 null(분석 건너뜀).
    // answer: 재분석 시 요청자 추가 답변(최초 분석이면 null)
    @Transactional(readOnly = true)
    public AnalysisInput loadInput(UUID requestId, String answer) {
        Request request = requestRepository.findById(requestId).orElse(null);
        if (request == null || request.getStatus() != RequestStatus.ANALYZING) {
            return null;
        }
        Project project = projectRepository.findById(request.getProjectId()).orElse(null);
        AnalysisInput.ProjectContext context = project == null ? null
                : new AnalysisInput.ProjectContext(project.getName(), project.getRepository(), "HEAD",
                        specVersionRepository.findTopByProjectIdOrderByVersionDesc(project.getId())
                                .map(spec -> truncate(spec.getBody(), SPEC_SUMMARY_LIMIT))
                                .orElse(null));

        List<String> forbidden = project == null ? List.of()
                : forbiddenRuleRepository.firstPage(project.getDepartment().getId(), Limit.of(FORBIDDEN_RULE_LIMIT))
                        .stream().map(this::describe).toList();
        // 분석 재시도는 answer 없이 들어오므로 요청에 보관한 입력을 우선함
        String input = request.getReanalysisInput() != null ? request.getReanalysisInput() : answer;
        return new AnalysisInput(request.getPrompt(), context, forbidden, priorPlanSummary(request), input);
    }

    // 분석 결과 반영. 요청 행을 잠그고 ANALYZING일 때만 전이.
    @Transactional
    public void applyResult(UUID requestId, AnalysisResult result) {
        Request request = requestRepository.findByIdForUpdate(requestId).orElse(null);
        if (request == null || request.getStatus() != RequestStatus.ANALYZING) {
            return;
        }

        if (result.isFailed()) {
            request.failAnalysis();
            requestStreamService.publishNodeUpdated(request, RequestStreamService.requestNodeId(requestId),
                    request.getStatus().name());
            return;
        }
        if (result.isClarification()) {
            request.askClarification(result.clarification());
            request.finishAnalysisWithoutPlan();
            requestStreamService.publishNodeUpdated(request, RequestStreamService.requestNodeId(requestId),
                    request.getStatus().name());
            return;
        }

        List<AiTask> tasks;
        try {
            tasks = result.tasks().stream().map(this::withNormalizedDiff).toList();
        } catch (UnifiedDiffNormalizer.InvalidDiffException e) {
            // 깨진 diff를 저장하면 승인 후 웹쉘에서야 실패하므로 분석 실패로 돌려 재시도하게 함
            log.warn("요청 {} 분석 결과의 diff가 올바르지 않음: {}", requestId, e.getMessage());
            request.failAnalysis();
            requestStreamService.publishNodeUpdated(request, RequestStreamService.requestNodeId(requestId),
                    request.getStatus().name());
            return;
        }

        Project project = projectRepository.findById(request.getProjectId()).orElseThrow();
        Plan plan = Plan.builder()
                .request(request)
                .kind(PlanKind.NORMAL)
                .repository(project.getRepository())
                .baseCommit("HEAD")
                .build();

        List<ForbiddenRule> rules = forbiddenRuleRepository
                .firstPage(project.getDepartment().getId(), Limit.of(FORBIDDEN_RULE_LIMIT));
        int order = 0;
        for (AiTask aiTask : tasks) {
            plan.addTask(toTask(aiTask, order++, rules, project, request.getRequesterId()));
        }
        planRepository.save(plan);

        request.finishAnalysisWithPlan(plan.getId());
        requestStreamService.publishTreeReplaced(request);
    }

    private AiTask withNormalizedDiff(AiTask task) {
        if (task.diff() == null || task.diff().isBlank()) {
            return task;
        }
        return new AiTask(task.type(), task.risk(), task.title(), task.target(),
                UnifiedDiffNormalizer.normalize(task.diff()), task.command(), task.query(), task.body());
    }

    // 재분석이 이전 Plan을 바탕으로 고치도록 작업 목록과 내용을 넘김
    private String priorPlanSummary(Request request) {
        if (request.getCurrentPlanId() == null) {
            return null;
        }
        return planRepository.findWithTasksById(request.getCurrentPlanId())
                .map(plan -> plan.getTasks().stream()
                        .sorted(Comparator.comparingInt(Task::getOrder))
                        .map(this::describePriorTask)
                        .collect(Collectors.joining("\n")))
                .orElse(null);
    }

    private String describePriorTask(Task task) {
        TaskContent content = task.getContent();
        String detail = content == null ? null : Stream.of(content.diff(), content.command(), content.query(), content.body())
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        String head = "%d. [%s/%s] %s (대상: %s)".formatted(task.getOrder() + 1, task.getType(), task.getRisk(),
                task.getTitle(), task.getTarget());
        return detail == null ? head : head + "\n" + truncate(detail, PRIOR_TASK_CONTENT_LIMIT);
    }

    private String truncate(String value, int limit) {
        return value == null || value.length() <= limit ? value : value.substring(0, limit) + "\n...(생략)";
    }

    private Task toTask(AiTask aiTask, int order, List<ForbiddenRule> rules, Project project, UUID requesterId) {
        Risk risk = matchesForbidden(aiTask, rules) ? Risk.FORBIDDEN : aiTask.risk();
        return Task.builder()
                .order(order)
                .type(aiTask.type())
                .risk(risk)
                .title(nullToDefault(aiTask.title(), "제목 없음"))
                .target(nullToDefault(aiTask.target(), ""))
                .content(new TaskContent(aiTask.diff(), aiTask.command(), aiTask.query(), aiTask.body()))
                .relatedDepartments(List.of())
                .requiredApprovers(approverResolver.resolve(aiTask.type(), risk, project, requesterId))
                .status(TaskStatus.PENDING)
                .build();
    }

    // 금지 규칙과 작업 유형이 같고 대상이 겹치면 FORBIDDEN으로 본다(간단 매칭).
    private boolean matchesForbidden(AiTask aiTask, List<ForbiddenRule> rules) {
        return rules.stream().anyMatch(rule -> rule.getTaskType() == aiTask.type()
                && aiTask.target() != null
                && !aiTask.target().isBlank()
                && (aiTask.target().contains(rule.getTarget()) || rule.getTarget().contains(aiTask.target())));
    }

    private String describe(ForbiddenRule rule) {
        return "[%s] %s - %s".formatted(rule.getTaskType(), rule.getTarget(), rule.getReason());
    }

    private String nullToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
