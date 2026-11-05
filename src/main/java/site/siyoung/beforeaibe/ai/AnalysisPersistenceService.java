package site.siyoung.beforeaibe.ai;

import lombok.RequiredArgsConstructor;
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
import site.siyoung.beforeaibe.entity.enums.ApproverRule;
import site.siyoung.beforeaibe.entity.enums.PlanKind;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;
import site.siyoung.beforeaibe.entity.enums.Risk;
import site.siyoung.beforeaibe.entity.enums.TaskStatus;
import site.siyoung.beforeaibe.entity.value.RequiredApprover;
import site.siyoung.beforeaibe.entity.value.TaskContent;
import site.siyoung.beforeaibe.repository.ForbiddenRuleRepository;
import site.siyoung.beforeaibe.repository.PlanRepository;
import site.siyoung.beforeaibe.repository.ProjectRepository;
import site.siyoung.beforeaibe.repository.RequestRepository;
import site.siyoung.beforeaibe.service.RequestStreamService;

import java.util.List;
import java.util.UUID;

// AI 분석의 DB 트랜잭션 작업만 담당(입력 구성 / 결과 반영).
// 비동기 오케스트레이션(RequestAnalysisService)과 분리해 @Transactional 프록시가 제대로 걸리게 한다.
@Service
@RequiredArgsConstructor
public class AnalysisPersistenceService {

    private static final int FORBIDDEN_RULE_LIMIT = 50;

    private final RequestRepository requestRepository;
    private final ProjectRepository projectRepository;
    private final PlanRepository planRepository;
    private final ForbiddenRuleRepository forbiddenRuleRepository;
    private final RequestStreamService requestStreamService;

    // 분석 입력 구성. 요청이 없거나 ANALYZING이 아니면 null(분석 건너뜀).
    @Transactional(readOnly = true)
    public AnalysisInput loadInput(UUID requestId) {
        Request request = requestRepository.findById(requestId).orElse(null);
        if (request == null || request.getStatus() != RequestStatus.ANALYZING) {
            return null;
        }
        Project project = projectRepository.findById(request.getProjectId()).orElse(null);
        AnalysisInput.ProjectContext context = project == null ? null
                : new AnalysisInput.ProjectContext(project.getName(), project.getRepository(), "HEAD", null);

        List<String> forbidden = project == null ? List.of()
                : forbiddenRuleRepository.firstPage(project.getDepartment().getId(), Limit.of(FORBIDDEN_RULE_LIMIT))
                        .stream().map(this::describe).toList();
        return new AnalysisInput(request.getPrompt(), context, forbidden, null, request.getClarification());
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
        for (AiTask aiTask : result.tasks()) {
            plan.addTask(toTask(aiTask, order++, rules));
        }
        planRepository.save(plan);

        request.finishAnalysisWithPlan(plan.getId());
        requestStreamService.publishTreeReplaced(request);
    }

    private Task toTask(AiTask aiTask, int order, List<ForbiddenRule> rules) {
        Risk risk = matchesForbidden(aiTask, rules) ? Risk.FORBIDDEN : aiTask.risk();
        return Task.builder()
                .order(order)
                .type(aiTask.type())
                .risk(risk)
                .title(nullToDefault(aiTask.title(), "제목 없음"))
                .target(nullToDefault(aiTask.target(), ""))
                .content(new TaskContent(aiTask.diff(), aiTask.command(), aiTask.query(), aiTask.body()))
                .relatedDepartments(List.of())
                // 승인 기준표 정밀 매핑은 후속 작업. 우선 ANYONE 1명으로 둠
                .requiredApprovers(List.of(new RequiredApprover(ApproverRule.ANYONE, 1, null)))
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
