package site.siyoung.beforeaibe.webshell;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;

import org.springframework.web.util.UriBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Map;
import site.siyoung.beforeaibe.dto.admin.AdminProof;

// 내부망 웹쉘 API 호출 (5장). 웹 서비스 서버의 클라이언트 인증서로 mTLS 연결함.
// 클라이언트 인증서는 Spring Boot SSL 번들(spring.ssl.bundle.*)로 등록하고 그 이름을 webshell.ssl-bundle에 적음.
// 정책 묶음은 같은 연결로 실제 배포한다. 기존 공개키 등록·폐기와 전사 중단 포트는 여전히 스텁임.
@Slf4j
@Component
public class WebshellClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    // 웹쉘 주소가 설정되지 않았으면 null이고, 호출하면 502 WEBSHELL_UNAVAILABLE
    private final RestClient restClient;

    public WebshellClient(@Value("${webshell.base-url:}") String baseUrl,
                          @Value("${webshell.ssl-bundle:}") String sslBundle,
                          ObjectProvider<SslBundles> sslBundles) {
        if (baseUrl == null || baseUrl.isBlank()) {
            this.restClient = null;
            return;
        }
        HttpClient.Builder httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT);
        if (sslBundle != null && !sslBundle.isBlank()) {
            httpClient.sslContext(sslBundles.getObject().getBundle(sslBundle).createSslContext());
        }
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient.build());
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    // POST /v1/executions — 승인 서명을 모아 웹쉘에 실행을 요청함 (API 명세서 5.1).
    // plan 원문은 승인자 기기·웹 서비스·웹쉘이 같은 해시를 계산하도록 PlanHashService.hashTarget과 같은 객체를 보냄
    public ExecutionResult submitExecution(ExecutionRequest request) {
        if (restClient == null) {
            throw new ApiException(ErrorCode.WEBSHELL_UNAVAILABLE);
        }
        try {
            return restClient.post()
                    .uri("/v1/executions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(ExecutionResult.class);
        } catch (RestClientException e) {
            log.warn("웹쉘 실행 요청 실패: requestId={}", request.plan() == null ? null : request.plan().get("requestId"), e);
            throw new ApiException(ErrorCode.WEBSHELL_UNAVAILABLE);
        }
    }

    // POST /v1/executions/{executionId}/kill
    public KillResult kill(UUID executionId, String reason) {
        if (restClient == null) {
            throw new ApiException(ErrorCode.WEBSHELL_UNAVAILABLE);
        }
        try {
            return restClient.post()
                    .uri("/v1/executions/{executionId}/kill", executionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new KillBody(reason))
                    .retrieve()
                    .body(KillResult.class);
        } catch (RestClientException e) {
            log.warn("웹쉘 실행 중단 요청 실패: executionId={}", executionId, e);
            throw new ApiException(ErrorCode.WEBSHELL_UNAVAILABLE);
        }
    }

    private record KillBody(String reason) {
    }

    public PolicyDeploymentClient.Result applyPolicy(Map<String, Object> policy, AdminProof proof) {
        if (restClient == null) throw new ApiException(ErrorCode.WEBSHELL_UNAVAILABLE);
        try {
            return restClient.post().uri("/v1/policies").contentType(MediaType.APPLICATION_JSON)
                    .body(new PolicyBody(policy, proof)).retrieve().body(PolicyDeploymentClient.Result.class);
        } catch (RestClientException e) {
            log.warn("웹쉘 정책 배포 요청 실패: {}", e.getClass().getSimpleName());
            throw new ApiException(ErrorCode.WEBSHELL_UNAVAILABLE);
        }
    }

    private record PolicyBody(Map<String, Object> policy, AdminProof adminProof) {}

    // 웹쉘 중단 응답. 서명 대상·직렬화 규칙이 명세에 없어 webshellSignature는 아직 검증하지 않음
    public record KillResult(UUID executionId, String status, String webshellSignature) {
    }

    // GET /v1/repos/files?repository=&path=&ref= — 저장소 디렉터리 목록 + 기준 커밋 (읽기 전용, API 명세서 3.8 #1)
    // 실제 경로·스키마는 웹쉘 계약 확정 전이라 잠정값. 저장소에 쓰지 않음
    public FileListResult listFiles(String repository, String path, String ref) {
        if (restClient == null) {
            throw new ApiException(ErrorCode.WEBSHELL_UNAVAILABLE);
        }
        try {
            return restClient.get()
                    .uri(uriBuilder -> fileUri(uriBuilder, "/v1/repos/files", repository, path, ref))
                    .retrieve()
                    .body(FileListResult.class);
        } catch (RestClientException e) {
            log.warn("웹쉘 파일 목록 조회 실패: repository={}, path={}", repository, path, e);
            throw new ApiException(ErrorCode.WEBSHELL_UNAVAILABLE);
        }
    }

    // GET /v1/repos/files/content?repository=&path=&ref= — 파일 내용 (읽기 전용, API 명세서 3.8 #2)
    public FileContentResult readFile(String repository, String path, String ref) {
        if (restClient == null) {
            throw new ApiException(ErrorCode.WEBSHELL_UNAVAILABLE);
        }
        try {
            return restClient.get()
                    .uri(uriBuilder -> fileUri(uriBuilder, "/v1/repos/files/content", repository, path, ref))
                    .retrieve()
                    .body(FileContentResult.class);
        } catch (RestClientException e) {
            log.warn("웹쉘 파일 내용 조회 실패: repository={}, path={}", repository, path, e);
            throw new ApiException(ErrorCode.WEBSHELL_UNAVAILABLE);
        }
    }

    // 저장소의 현재 기준 커밋. 에디터 저장 시 EDIT_CONFLICT 판정에 씀 (API 명세서 3.8 #3)
    public String currentCommit(String repository, String ref) {
        return listFiles(repository, null, ref).baseCommit();
    }

    private URI fileUri(UriBuilder uriBuilder, String path, String repository, String repoPath, String ref) {
        uriBuilder.path(path).queryParam("repository", repository);
        if (repoPath != null && !repoPath.isBlank()) {
            uriBuilder.queryParam("path", repoPath);
        }
        if (ref != null && !ref.isBlank()) {
            uriBuilder.queryParam("ref", ref);
        }
        return uriBuilder.build();
    }

    // 웹쉘 디렉터리 목록 응답. 필드명·스키마는 컬렉션 예시 기준이며 계약 확정 전
    public record FileListResult(String baseCommit, String path, List<FileItem> items) {
    }

    public record FileItem(String name, String path, String type) {
    }

    // 웹쉘 파일 내용 응답. 계약 확정 전
    public record FileContentResult(String path, String ref, String baseCommit, String content) {
    }

    // 실행 요청 본문 (API 명세서 5.1). plan은 PlanHashService.hashTarget이 만든 정규화 전 객체를 그대로 보냄
    public record ExecutionRequest(Map<String, Object> plan, List<ApprovalPayload> approvals) {
    }

    // 승인 봉투는 웹 서비스가 열어 보지 않고 keyId와 함께 그대로 전달함 (AGENT.md 보안 규칙)
    public record ApprovalPayload(UUID keyId, ApprovalEnvelope envelope) {

        public record ApprovalEnvelope(String alg, String encapsulatedKey, String ciphertext) {
        }
    }

    // 웹쉘이 실행을 받아들이며 돌려준 값 (202). webshellSignature·planHash는 웹쉘이 직접 계산함
    public record ExecutionResult(UUID executionId, String planHash, String status, String webshellSignature) {
    }
}
