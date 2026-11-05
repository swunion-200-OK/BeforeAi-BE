package site.siyoung.beforeaibe.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import site.siyoung.beforeaibe.entity.Plan;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.Task;
import site.siyoung.beforeaibe.entity.enums.ApproverRule;
import site.siyoung.beforeaibe.entity.enums.PlanKind;
import site.siyoung.beforeaibe.entity.enums.RequestSource;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;
import site.siyoung.beforeaibe.entity.enums.Risk;
import site.siyoung.beforeaibe.entity.enums.TaskStatus;
import site.siyoung.beforeaibe.entity.enums.TaskType;
import site.siyoung.beforeaibe.entity.value.RequiredApprover;
import site.siyoung.beforeaibe.entity.value.TaskContent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlanHashServiceTest {

    private static final UUID REQUEST_ID = UUID.fromString("3cc84fb8-bf15-50bf-ba57-a96cb27fc8b0");
    private static final UUID PLAN_ID = UUID.fromString("3b8577ba-2c34-5587-8ef5-070e43948f6e");
    private static final UUID PROJECT_ID = UUID.fromString("f29db1e4-ac0e-5807-ab60-769dcdbe6a3c");
    private static final UUID CODE_TASK_ID = UUID.fromString("a7c3b121-6a1c-5ec1-a64b-592af1c08c0a");
    private static final UUID DEPLOY_TASK_ID = UUID.fromString("261514d6-cf26-5489-a9a8-7cbc6bd60839");

    private final PlanHashService planHashService = new PlanHashService();

    @Test
    void 객체_키는_UTF16_코드_단위_순으로_정렬된다() {
        // RFC 8785 3.2.3의 정렬 예시
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("\u20ac", 1);
        value.put("\r", 2);
        value.put("\ufb33", 3);
        value.put("1", 4);
        value.put("\ud83d\ude00", 5);
        value.put("\u0080", 6);
        value.put("\u00f6", 7);

        assertThat(planHashService.canonicalize(value))
                .isEqualTo("{\"\\r\":2,\"1\":4,\"\u0080\":6,\"\u00f6\":7,\"\u20ac\":1,\"\ud83d\ude00\":5,\"\ufb33\":3}");
    }

    @Test
    void 문자열은_따옴표_역슬래시_제어_문자만_이스케이프하고_한글은_그대로_둔다() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("z", 1);
        nested.put("y", "\u0001\t");
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("b", 10L);
        value.put("a", "줄\n\"따옴표\"\\");
        value.put("c", Arrays.asList(true, null, nested));

        assertThat(planHashService.canonicalize(value))
                .isEqualTo("{\"a\":\"줄\\n\\\"따옴표\\\"\\\\\",\"b\":10,\"c\":[true,null,{\"y\":\"\\u0001\\t\",\"z\":1}]}");
    }

    @Test
    void Plan_해시는_hashTarget_정규화_결과의_SHA256이고_작업은_order_순이며_금지_작업은_빠진다() throws Exception {
        Request request = Request.builder()
                .projectId(PROJECT_ID).requesterId(UUID.randomUUID()).prompt("프롬프트")
                .source(RequestSource.PROMPT).status(RequestStatus.REVIEWING).analysisAttempts(1).build();
        ReflectionTestUtils.setField(request, "id", REQUEST_ID);
        Plan plan = Plan.builder().request(request).kind(PlanKind.NORMAL)
                .repository("git@internal:auth-service.git").baseCommit("9b2f4e1").build();
        ReflectionTestUtils.setField(plan, "id", PLAN_ID);
        // 일부러 order 역순으로 넣고, 금지 작업은 해시 대상에서 빠져야 함
        plan.addTask(task(UUID.randomUUID(), 3, TaskType.DB, Risk.FORBIDDEN, "prod-db",
                new TaskContent(null, null, "DROP TABLE users", null)));
        plan.addTask(task(DEPLOY_TASK_ID, 2, TaskType.DEPLOY, Risk.HIGH, "staging",
                new TaskContent(null, "./deploy.sh staging", null, null)));
        plan.addTask(task(CODE_TASK_ID, 1, TaskType.CODE_FEATURE, Risk.MEDIUM, "src/auth/login.ts",
                new TaskContent("--- a/src/auth/login.ts\n+++ b/...", null, null, null)));

        String expectedCanonical = "{\"baseCommit\":\"9b2f4e1\","
                + "\"planId\":\"" + PLAN_ID + "\","
                + "\"projectId\":\"" + PROJECT_ID + "\","
                + "\"repository\":\"git@internal:auth-service.git\","
                + "\"requestId\":\"" + REQUEST_ID + "\","
                + "\"tasks\":["
                + "{\"content\":{\"diff\":\"--- a/src/auth/login.ts\\n+++ b/...\"},\"id\":\"" + CODE_TASK_ID
                + "\",\"order\":1,\"risk\":\"MEDIUM\",\"target\":\"src/auth/login.ts\",\"type\":\"CODE_FEATURE\"},"
                + "{\"content\":{\"command\":\"./deploy.sh staging\"},\"id\":\"" + DEPLOY_TASK_ID
                + "\",\"order\":2,\"risk\":\"HIGH\",\"target\":\"staging\",\"type\":\"DEPLOY\"}"
                + "]}";

        assertThat(planHashService.canonicalize(planHashService.hashTarget(request, plan))).isEqualTo(expectedCanonical);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(expectedCanonical.getBytes(StandardCharsets.UTF_8));
        assertThat(planHashService.hash(request, plan)).isEqualTo("sha256:" + HexFormat.of().formatHex(digest));
    }

    private static Task task(UUID id, int order, TaskType type, Risk risk, String target, TaskContent content) {
        Task task = Task.builder()
                .order(order).type(type).risk(risk).title("작업").target(target).content(content)
                .relatedDepartments(List.of())
                .requiredApprovers(List.of(new RequiredApprover(ApproverRule.ANYONE, 1, null)))
                .status(TaskStatus.PENDING)
                .build();
        ReflectionTestUtils.setField(task, "id", id);
        return task;
    }
}
