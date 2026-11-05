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
    private String receivedContentType;
    private String receivedBody;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            receivedMethod = exchange.getRequestMethod();
            receivedPath = exchange.getRequestURI().getPath();
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
}
