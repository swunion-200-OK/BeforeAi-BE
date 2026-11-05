package site.siyoung.beforeaibe.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import site.siyoung.beforeaibe.dto.request.ExecutionLogResponse;
import site.siyoung.beforeaibe.dto.request.ProgressNodeResponse;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.enums.RequestSource;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

// 트랜잭션 없이 구독 → 발행 → 수신을 확인함. API 테스트는 @Transactional이라 커밋 뒤 전송(afterCommit)이 실행되지 않음
class RequestStreamServiceTest {

    private final RequestStreamService requestStreamService = new RequestStreamService();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new StreamController(requestStreamService)).build();
    }

    @AfterEach
    void tearDown() {
        requestStreamService.shutdown();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @RestController
    static class StreamController {

        private final RequestStreamService requestStreamService;

        StreamController(RequestStreamService requestStreamService) {
            this.requestStreamService = requestStreamService;
        }

        @GetMapping(path = "/progress/{requestId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter progress(@PathVariable UUID requestId, @RequestParam long version,
                            @RequestHeader(name = "Last-Event-ID", required = false) Long lastEventId) {
            return requestStreamService.subscribeProgress(requestId, version, lastEventId);
        }

        @GetMapping(path = "/logs/{requestId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter logs(@PathVariable UUID requestId) {
            return requestStreamService.subscribeLogs(requestId);
        }
    }

    private static Request newRequest() {
        Request request = Request.builder()
                .projectId(UUID.randomUUID()).requesterId(UUID.randomUUID()).prompt("프롬프트")
                .source(RequestSource.PROMPT).status(RequestStatus.REVIEWING).analysisAttempts(1).build();
        ReflectionTestUtils.setField(request, "id", UUID.randomUUID());
        return request;
    }

    private MvcResult subscribeProgress(UUID requestId, long version) throws Exception {
        return mockMvc.perform(get("/progress/{id}", requestId).param("version", String.valueOf(version)))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    @Test
    void 트랜잭션_밖에서_상태가_바뀌면_version을_올리고_node_updated를_바로_보낸다() throws Exception {
        Request request = newRequest();
        MvcResult stream = subscribeProgress(request.getId(), 0);

        requestStreamService.publishNodeUpdated(request, RequestStreamService.requestNodeId(request.getId()),
                "CANCELED");

        assertThat(request.getProgressVersion()).isEqualTo(1);
        assertThat(stream.getResponse().getContentAsString())
                .contains("id:1")
                .contains("event:node.updated")
                .contains("\"nodeId\":\"request:" + request.getId() + "\"")
                .contains("\"status\":\"CANCELED\"");
    }

    @Test
    void STEP이_생기면_부모_노드와_함께_node_added를_보낸다() throws Exception {
        Request request = newRequest();
        MvcResult stream = subscribeProgress(request.getId(), 0);
        UUID executionId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();

        requestStreamService.publishNodeAdded(request, RequestStreamService.taskNodeId(taskId),
                ProgressNodeResponse.step(RequestStreamService.stepNodeId(executionId, taskId), "apply-diff",
                        "RUNNING", null));

        assertThat(request.getProgressVersion()).isEqualTo(1);
        assertThat(stream.getResponse().getContentAsString())
                .contains("id:1")
                .contains("event:node.added")
                .contains("\"parentId\":\"task:" + taskId + "\"")
                .contains("\"id\":\"step:" + executionId + ":" + taskId + "\"")
                .contains("\"type\":\"STEP\"")
                .contains("\"label\":\"apply-diff\"")
                .doesNotContain("logRange");
    }

    @Test
    void 트랜잭션_안에서는_커밋된_뒤에만_보낸다() throws Exception {
        Request request = newRequest();
        MvcResult stream = subscribeProgress(request.getId(), 0);
        TransactionSynchronizationManager.initSynchronization();

        requestStreamService.publishTreeReplaced(request);
        assertThat(stream.getResponse().getContentAsString()).doesNotContain("tree.replaced");

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(stream.getResponse().getContentAsString())
                .contains("id:1")
                .contains("event:tree.replaced")
                .contains("\"version\":1");
    }

    @Test
    void 롤백되면_보내지_않는다() throws Exception {
        Request request = newRequest();
        MvcResult stream = subscribeProgress(request.getId(), 0);
        TransactionSynchronizationManager.initSynchronization();

        requestStreamService.publishTreeReplaced(request);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertThat(stream.getResponse().getContentAsString()).isEmpty();
    }

    @Test
    void Last_Event_ID가_현재_version과_같으면_보내지_않고_다르면_tree_replaced를_보낸다() throws Exception {
        UUID requestId = UUID.randomUUID();

        MvcResult upToDate = mockMvc.perform(get("/progress/{id}", requestId).param("version", "5")
                        .header("Last-Event-ID", "5"))
                .andReturn();
        MvcResult stale = mockMvc.perform(get("/progress/{id}", requestId).param("version", "5")
                        .header("Last-Event-ID", "2"))
                .andReturn();

        assertThat(upToDate.getResponse().getContentAsString()).isEmpty();
        assertThat(stale.getResponse().getContentAsString()).contains("id:5").contains("event:tree.replaced");
    }

    @Test
    void 다른_요청의_구독자에게는_보내지_않는다() throws Exception {
        Request request = newRequest();
        MvcResult other = subscribeProgress(UUID.randomUUID(), 0);

        requestStreamService.publishTreeReplaced(request);

        assertThat(other.getResponse().getContentAsString()).isEmpty();
    }

    @Test
    void 실행_로그는_log_이벤트로_보낸다() throws Exception {
        UUID requestId = UUID.randomUUID();
        MvcResult stream = mockMvc.perform(get("/logs/{id}", requestId))
                .andExpect(request().asyncStarted())
                .andReturn();

        requestStreamService.publishLog(requestId, new ExecutionLogResponse(
                120, Instant.parse("2026-11-04T03:10:00Z"), null, "diff 적용 시작", "c2ln"));

        assertThat(stream.getResponse().getContentAsString())
                .contains("event:log")
                .contains("\"sequence\":120")
                .contains("\"webshellSignature\":\"c2ln\"");
    }
}
