package site.siyoung.beforeaibe.repository;

import site.siyoung.beforeaibe.entity.Plan;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.Task;
import site.siyoung.beforeaibe.entity.value.RequiredApprover;
import site.siyoung.beforeaibe.entity.value.TaskContent;
import site.siyoung.beforeaibe.enums.ApproverRule;
import site.siyoung.beforeaibe.enums.PlanKind;
import site.siyoung.beforeaibe.enums.RequestSource;
import site.siyoung.beforeaibe.enums.RequestStatus;
import site.siyoung.beforeaibe.enums.Risk;
import site.siyoung.beforeaibe.enums.TaskStatus;
import site.siyoung.beforeaibe.enums.TaskType;

import java.util.List;
import java.util.UUID;

final class RepositoryTestSupport {

    static final UUID BACKEND_DEPT_ID = UUID.randomUUID();

    private RepositoryTestSupport() {
    }

    static Request request() {
        return Request.builder()
                .projectId(UUID.randomUUID())
                .requesterId(UUID.randomUUID())
                .prompt("로그인 실패 시 에러 메시지를 바꾸고 스테이징에 배포해줘")
                .source(RequestSource.PROMPT)
                .status(RequestStatus.REVIEWING)
                .analysisAttempts(1)
                .build();
    }

    static Plan plan(Request request) {
        return Plan.builder()
                .request(request)
                .kind(PlanKind.NORMAL)
                .repository("git@internal:auth-service.git")
                .baseCommit("9b2f4e1")
                .build();
    }

    static Task codeTask(int order) {
        return Task.builder()
                .order(order)
                .type(TaskType.CODE_FEATURE)
                .risk(Risk.MEDIUM)
                .title("로그인 실패 메시지 변경")
                .target("src/auth/login.ts")
                .content(new TaskContent("--- a/src/auth/login.ts\n+++ b/src/auth/login.ts", null, null, null))
                .relatedDepartments(List.of(BACKEND_DEPT_ID))
                .requiredApprovers(List.of(new RequiredApprover(ApproverRule.ANYONE, 1, null)))
                .status(TaskStatus.PENDING)
                .build();
    }
}
