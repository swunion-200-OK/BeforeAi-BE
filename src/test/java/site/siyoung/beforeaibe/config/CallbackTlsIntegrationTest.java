package site.siyoung.beforeaibe.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import site.siyoung.beforeaibe.TestcontainersConfiguration;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 실제 Tomcat 커넥터에 mTLS로 붙어 프록시 없는 콜백 경로를 검증함. 인증서는 테스트 때마다 임시로 만듦
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class CallbackTlsIntegrationTest {

    private static final char[] PASSWORD = "changeit".toCharArray();
    private static Path dir;
    private static int port;

    @Value("${local.server.port}")
    private int plainPort;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        dir = Files.createTempDirectory("callback-tls");
        generate("server", "beforeai-server", "ip:127.0.0.1");
        generate("webshell", "beforeai-webshell", null);
        generate("stranger", "someone-else", null);
        // 웹쉘·낯선 인증서를 모두 신뢰해 CN 검사까지 도달하는지 봄
        Files.writeString(dir.resolve("ca.pem"),
                Files.readString(dir.resolve("webshell.crt")) + Files.readString(dir.resolve("stranger.crt")));
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        registry.add("webshell.callback-tls.enabled", () -> "true");
        registry.add("webshell.callback-tls.port", () -> port);
        registry.add("webshell.callback-tls.address", () -> "127.0.0.1");
        registry.add("webshell.callback-tls.certificate", () -> dir.resolve("server.crt").toString());
        registry.add("webshell.callback-tls.private-key", () -> dir.resolve("server.key").toString());
        registry.add("webshell.callback-tls.client-ca", () -> dir.resolve("ca.pem").toString());
        registry.add("webshell.callback-client-cn", () -> "beforeai-webshell");
        registry.add("network.internal-cidrs", () -> "127.0.0.0/8");
        // 운영처럼 프록시 헤더를 믿는 설정에서도 콜백 포트는 소켓 IP로 내부망 판별돼야 함
        registry.add("network.trust-proxy-header", () -> "true");
    }

    private static void generate(String name, String cn, String san) throws Exception {
        Path store = dir.resolve(name + ".p12");
        var command = new java.util.ArrayList<>(java.util.List.of(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(), "-genkeypair", "-alias", name,
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=" + cn, "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", new String(PASSWORD)));
        if (san != null) {
            command.addAll(java.util.List.of("-ext", "SAN=" + san));
        }
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertThat(process.waitFor()).isZero();

        KeyStore keyStore = load(store);
        Key key = keyStore.getKey(name, PASSWORD);
        Certificate certificate = keyStore.getCertificate(name);
        Files.writeString(dir.resolve(name + ".key"), pem("PRIVATE KEY", key.getEncoded()));
        Files.writeString(dir.resolve(name + ".crt"), pem("CERTIFICATE", certificate.getEncoded()));
    }

    private static KeyStore load(Path store) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(store)) {
            keyStore.load(in, PASSWORD);
        }
        return keyStore;
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    private HttpClient client(String clientStore) throws Exception {
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("server", load(dir.resolve("server.p12")).getCertificate("server"));
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trust);
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        if (clientStore == null) {
            KeyStore empty = KeyStore.getInstance("PKCS12");
            empty.load(null, null);
            keyManagers.init(empty, PASSWORD);
        } else {
            keyManagers.init(load(dir.resolve(clientStore + ".p12")), PASSWORD);
        }
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);
        return HttpClient.newBuilder().sslContext(context).build();
    }

    private HttpResponse<String> post(String store, String path) throws Exception {
        return client(store).send(HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(EVENT)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static final UUID EXECUTION_ID = UUID.randomUUID();

    private static final String EVENT = """
            {"eventId": "%s", "type": "TASK_SUCCEEDED", "executionId": "%s", "requestId": "%s",
             "at": "2026-01-01T00:00:00Z", "data": {}, "webshellSignature": "sig"}"""
            .formatted(UUID.randomUUID(), EXECUTION_ID, UUID.randomUUID());

    private static String callbackPath() {
        return "/api/v1/internal/executions/" + EXECUTION_ID + "/events";
    }

    @Test
    void 허용된_CN의_인증서면_콜백_컨트롤러까지_도달한다() throws Exception {
        HttpResponse<String> response = post("webshell", callbackPath());

        // 없는 실행 세션이라 404. 인증(401)·내부망 판별(403)은 통과했다는 뜻
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("NOT_FOUND");
    }

    @Test
    void 신뢰하는_CA라도_CN이_다르면_거부한다() throws Exception {
        assertThat(post("stranger", callbackPath()).statusCode()).isEqualTo(401);
    }

    @Test
    void 인증서가_없으면_TLS_핸드셰이크에서_끊는다() {
        assertThatThrownBy(() -> post(null, callbackPath())).isInstanceOf(IOException.class);
    }

    @Test
    void 콜백_포트로는_다른_경로에_접근할_수_없다() throws Exception {
        assertThat(post("webshell", "/api/v1/auth/login").statusCode()).isEqualTo(404);
    }

    @Test
    void 평문_포트의_콜백은_인증서가_없어_거부된다() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + plainPort + callbackPath()))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(EVENT)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
    }
}
