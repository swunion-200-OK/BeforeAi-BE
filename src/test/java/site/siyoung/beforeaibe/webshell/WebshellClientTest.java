package site.siyoung.beforeaibe.webshell;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundles;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.Map;
import site.siyoung.beforeaibe.dto.admin.AdminProof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 로컬 HTTP 서버를 웹쉘로 두고 요청 형식과 오류 처리를 확인함. mTLS 자체는 실제 인증서가 있어야 해서 여기서는 다루지 않음
class WebshellClientTest {

    private static final UUID EXECUTION_ID = UUID.fromString("0e83d7ef-f597-5f38-a8c7-4b8fc5888828");

    private HttpServer server;
    private int responseStatus;
    private String responseBody;
    private String receivedMethod;
    private String receivedPath;
    private String receivedQuery;
    private String receivedRawQuery;
    private String receivedContentType;
    private String receivedBody;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            receivedMethod = exchange.getRequestMethod();
            receivedPath = exchange.getRequestURI().getPath();
            receivedQuery = exchange.getRequestURI().getQuery();
            receivedRawQuery = exchange.getRequestURI().getRawQuery();
            receivedContentType = exchange.getRequestHeaders().getFirst("Content-Type");
            receivedBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static ObjectProvider<SslBundles> sslBundles() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("sslBundles", new DefaultSslBundleRegistry());
        return beanFactory.getBeanProvider(SslBundles.class);
    }

    private WebshellClient client() {
        return new WebshellClient("http://127.0.0.1:" + server.getAddress().getPort(), "", sslBundles());
    }

    @Test
    void 중단_요청을_보내고_웹쉘_응답을_읽는다() {
        responseStatus = 200;
        responseBody = """
                {"executionId": "%s", "status": "KILLED", "webshellSignature": "c2ln"}
                """.formatted(EXECUTION_ID);

        WebshellClient.KillResult result = client().kill(EXECUTION_ID, "잘못된 브랜치에 배포 중");

        assertThat(receivedMethod).isEqualTo("POST");
        assertThat(receivedPath).isEqualTo("/v1/executions/" + EXECUTION_ID + "/kill");
        assertThat(receivedContentType).startsWith("application/json");
        assertThat(receivedBody).isEqualTo("{\"reason\":\"잘못된 브랜치에 배포 중\"}");
        assertThat(result).isEqualTo(new WebshellClient.KillResult(EXECUTION_ID, "KILLED", "c2ln"));
    }

    @Test
    void 실행_요청을_보내고_웹쉘_응답을_읽는다() {
        responseStatus = 202;
        responseBody = """
                {"executionId": "%s", "planHash": "sha256:4be1", "status": "RUNNING", "webshellSignature": "c2ln"}
                """.formatted(EXECUTION_ID);
        WebshellClient.ExecutionRequest request = new WebshellClient.ExecutionRequest(
                java.util.Map.of("requestId", "r-1"),
                java.util.List.of(new WebshellClient.ApprovalPayload(
                        UUID.fromString("00000000-0000-0000-0000-0000000000a1"),
                        new WebshellClient.ApprovalPayload.ApprovalEnvelope("HPKE", "ZW5j", "Y2lwaGVy"))));

        WebshellClient.ExecutionResult result = client().submitExecution(request);

        assertThat(receivedMethod).isEqualTo("POST");
        assertThat(receivedPath).isEqualTo("/v1/executions");
        assertThat(receivedContentType).startsWith("application/json");
        assertThat(receivedBody).contains("\"requestId\":\"r-1\"").contains("\"keyId\"").contains("\"ciphertext\":\"Y2lwaGVy\"");
        assertThat(result).isEqualTo(
                new WebshellClient.ExecutionResult(EXECUTION_ID, "sha256:4be1", "RUNNING", "c2ln"));
    }

    @Test
    void 파일_목록은_경로와_ref를_전달하고_항목을_읽는다() {
        responseStatus = 200;
        responseBody = """
                {"baseCommit": "abc123", "path": "src/한글 폴더",
                 "items": [{"name": "login.ts", "path": "src/한글 폴더/login.ts", "type": "file"}]}
                """;

        WebshellClient.FileListResult result = client().listFiles(
                "git@internal:auth-service.git", "src/한글 폴더", "feature/login");

        assertThat(receivedMethod).isEqualTo("GET");
        assertThat(receivedPath).isEqualTo("/v1/repos/files");
        assertThat(receivedQuery).isEqualTo(
                "repository=git@internal:auth-service.git&path=src/한글 폴더&ref=feature/login");
        assertThat(receivedRawQuery).contains("%20").doesNotContain("한글", " ");
        assertThat(result.baseCommit()).isEqualTo("abc123");
        assertThat(result.path()).isEqualTo("src/한글 폴더");
        assertThat(result.items()).containsExactly(
                new WebshellClient.FileItem("login.ts", "src/한글 폴더/login.ts", "file"));
    }

    @Test
    void 파일_내용은_지정한_ref에서_읽는다() {
        responseStatus = 200;
        responseBody = """
                {"path": "src/login.ts", "ref": "feature/login", "baseCommit": "abc123",
                 "content": "export const message = '로그인 실패';"}
                """;

        WebshellClient.FileContentResult result = client().readFile(
                "git@internal:auth-service.git", "src/login.ts", "feature/login");

        assertThat(receivedMethod).isEqualTo("GET");
        assertThat(receivedPath).isEqualTo("/v1/repos/files/content");
        assertThat(receivedQuery).isEqualTo(
                "repository=git@internal:auth-service.git&path=src/login.ts&ref=feature/login");
        assertThat(result).isEqualTo(new WebshellClient.FileContentResult(
                "src/login.ts", "feature/login", "abc123", "export const message = '로그인 실패';"));
    }

    @Test
    void 현재_커밋은_루트_목록에서_읽고_빈_ref는_생략한다() {
        responseStatus = 200;
        responseBody = """
                {"baseCommit": "abc123", "path": "", "items": []}
                """;

        String commit = client().currentCommit("git@internal:auth-service.git", " ");

        assertThat(receivedMethod).isEqualTo("GET");
        assertThat(receivedPath).isEqualTo("/v1/repos/files");
        assertThat(receivedQuery).isEqualTo("repository=git@internal:auth-service.git");
        assertThat(commit).isEqualTo("abc123");
    }

    @Test
    void 실행_요청이_실패하면_502_WEBSHELL_UNAVAILABLE이다() {
        responseStatus = 503;
        responseBody = "{}";
        WebshellClient.ExecutionRequest request = new WebshellClient.ExecutionRequest(
                java.util.Map.of("requestId", "r-1"), java.util.List.of());

        assertThatThrownBy(() -> client().submitExecution(request))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WEBSHELL_UNAVAILABLE));
    }

    @Test
    void 웹쉘이_오류로_응답하면_502_WEBSHELL_UNAVAILABLE이다() {
        responseStatus = 500;
        responseBody = "{}";

        assertThatThrownBy(() -> client().kill(EXECUTION_ID, "중단"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WEBSHELL_UNAVAILABLE));
    }

    @Test
    void 웹쉘에_연결할_수_없으면_502_WEBSHELL_UNAVAILABLE이다() {
        WebshellClient client = client();
        server.stop(0);

        assertThatThrownBy(() -> client.kill(EXECUTION_ID, "중단"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WEBSHELL_UNAVAILABLE));
    }

    @Test
    void 웹쉘_주소가_없으면_호출하지_않고_502_WEBSHELL_UNAVAILABLE이다() {
        WebshellClient client = new WebshellClient("", "", sslBundles());

        assertThatThrownBy(() -> client.kill(EXECUTION_ID, "중단"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WEBSHELL_UNAVAILABLE));
    }

    @Test
    void 등록되지_않은_SSL_번들_이름이면_시작할_때_실패한다() {
        assertThatThrownBy(() -> new WebshellClient("https://127.0.0.1:1", "webshell", sslBundles()))
                .isInstanceOf(NoSuchSslBundleException.class);
    }

    @Test
    void 정책_스냅샷과_관리자_서명을_함께_전달한다() {
        responseStatus = 200;
        responseBody = "{\"version\":2,\"policyHash\":\"sha256:test\",\"applied\":true}";
        UUID keyId = UUID.randomUUID();
        var result = client().applyPolicy(Map.of("version", 2, "forbiddenRules", java.util.List.of()),
                new AdminProof(keyId, "signature"));
        assertThat(receivedMethod).isEqualTo("POST");
        assertThat(receivedPath).isEqualTo("/v1/policies");
        assertThat(receivedBody).contains("\"adminProof\":", "\"keyId\":\"" + keyId + "\"",
                "\"signature\":\"signature\"", "\"forbiddenRules\":[]");
        assertThat(result).isEqualTo(new PolicyDeploymentClient.Result(2, "sha256:test", true));
    }

    @Test
    void 정책_배포_오류는_502로_반환한다() {
        responseStatus = 503;
        responseBody = "{}";
        assertThatThrownBy(() -> client().applyPolicy(Map.of("version", 2), new AdminProof(UUID.randomUUID(), "signature")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WEBSHELL_UNAVAILABLE));
    }

    @Test
    void 승인_서명_검증_실패_403은_실패_항목과_함께_VerificationFailedException이다() {
        responseStatus = 403;
        responseBody = """
                {"error": {"code": "VERIFICATION_FAILED", "message": "승인 서명 검증에 실패했습니다",
                 "details": {"failedChecks": ["NONCE_ALREADY_USED", "PLAN_HASH_MISMATCH"]}}}
                """;
        WebshellClient.ExecutionRequest request = new WebshellClient.ExecutionRequest(
                Map.of("requestId", "r-1"), java.util.List.of());

        assertThatThrownBy(() -> client().submitExecution(request))
                .isInstanceOfSatisfying(WebshellClient.VerificationFailedException.class,
                        e -> assertThat(e.getFailedChecks()).containsExactly("NONCE_ALREADY_USED", "PLAN_HASH_MISMATCH"));
    }

    @Test
    void 코드가_VERIFICATION_FAILED가_아닌_403은_502_WEBSHELL_UNAVAILABLE이다() {
        responseStatus = 403;
        responseBody = """
                {"error": {"code": "FORBIDDEN", "message": "클라이언트 인증서가 허용되지 않았습니다"}}
                """;
        WebshellClient.ExecutionRequest request = new WebshellClient.ExecutionRequest(
                Map.of("requestId", "r-1"), java.util.List.of());

        assertThatThrownBy(() -> client().submitExecution(request))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WEBSHELL_UNAVAILABLE));
    }

    @Test
    void 본문을_읽을_수_없는_403은_502_WEBSHELL_UNAVAILABLE이다() {
        responseStatus = 403;
        responseBody = "forbidden";
        WebshellClient.ExecutionRequest request = new WebshellClient.ExecutionRequest(
                Map.of("requestId", "r-1"), java.util.List.of());

        assertThatThrownBy(() -> client().submitExecution(request))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WEBSHELL_UNAVAILABLE));
    }

    @Test
    void 실행_요청의_403_이외_오류는_502_WEBSHELL_UNAVAILABLE이다() {
        responseStatus = 500;
        responseBody = "{}";
        WebshellClient.ExecutionRequest request = new WebshellClient.ExecutionRequest(
                Map.of("requestId", "r-1"), java.util.List.of());

        assertThatThrownBy(() -> client().submitExecution(request))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WEBSHELL_UNAVAILABLE));
    }

    @Test
    void 실행_상태를_조회한다() {
        responseStatus = 200;
        UUID taskId = UUID.randomUUID();
        responseBody = """
                {"executionId": "%s", "status": "RUNNING",
                 "tasks": [{"taskId": "%s", "status": "SUCCEEDED"}], "webshellSignature": "c2ln"}
                """.formatted(EXECUTION_ID, taskId);

        WebshellClient.ExecutionStatusResult result = client().getExecution(EXECUTION_ID);

        assertThat(receivedMethod).isEqualTo("GET");
        assertThat(receivedPath).isEqualTo("/v1/executions/" + EXECUTION_ID);
        assertThat(result).isEqualTo(new WebshellClient.ExecutionStatusResult(EXECUTION_ID, "RUNNING",
                java.util.List.of(new WebshellClient.ExecutionStatusResult.TaskResult(taskId, "SUCCEEDED")), "c2ln"));
    }

    @Test
    void 전체_중단을_요청하고_중단된_실행_ID를_읽는다() {
        responseStatus = 200;
        responseBody = """
                {"executionIds": ["%s"], "status": "KILLED", "webshellSignature": "c2ln"}
                """.formatted(EXECUTION_ID);

        WebshellClient.KillAllResult result = client().killAll("전사 긴급 실행 중단");

        assertThat(receivedMethod).isEqualTo("POST");
        assertThat(receivedPath).isEqualTo("/v1/kill-all");
        assertThat(receivedContentType).startsWith("application/json");
        assertThat(receivedBody).isEqualTo("{\"reason\":\"전사 긴급 실행 중단\"}");
        assertThat(result).isEqualTo(new WebshellClient.KillAllResult(java.util.List.of(EXECUTION_ID), "KILLED", "c2ln"));
    }

    @Test
    void 전체_중단_실패는_502_WEBSHELL_UNAVAILABLE이다() {
        responseStatus = 503;
        responseBody = "{}";

        assertThatThrownBy(() -> client().killAll("중단"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WEBSHELL_UNAVAILABLE));
    }

    @Test
    void 현재_정책_버전과_해시를_조회한다() {
        responseStatus = 200;
        responseBody = "{\"version\": 8, \"policyHash\": \"sha256:abc\"}";

        WebshellClient.PolicyCurrentResult result = client().getCurrentPolicy();

        assertThat(receivedMethod).isEqualTo("GET");
        assertThat(receivedPath).isEqualTo("/v1/policies/current");
        assertThat(result).isEqualTo(new WebshellClient.PolicyCurrentResult(8, "sha256:abc"));
    }
}
