package site.siyoung.beforeaibe.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

// 웹쉘 콜백 전용 TLS 포트. 앱 앞의 Nginx가 http로 붙으면 클라이언트 인증서가 앱까지 오지 않아 x509 인증이 안 되므로,
// 웹쉘이 앱에 직접 mTLS로 붙는 포트를 tailnet 주소에만 따로 염.
// 주소를 비워 두면 모든 인터페이스에 열리므로 기동을 막음
@Configuration
@ConditionalOnProperty(name = "webshell.callback-tls.enabled", havingValue = "true")
public class CallbackTlsConfig {

    private static final String CALLBACK_PATH_PREFIX = "/api/v1/internal/";

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> callbackTlsConnector(
            @Value("${webshell.callback-tls.port}") int port,
            @Value("${webshell.callback-tls.address}") String address,
            @Value("${webshell.callback-tls.certificate}") String certificate,
            @Value("${webshell.callback-tls.private-key}") String privateKey,
            @Value("${webshell.callback-tls.client-ca}") String clientCa) {
        if (address.isBlank() || certificate.isBlank() || privateKey.isBlank() || clientCa.isBlank()) {
            throw new IllegalStateException(
                    "콜백 TLS를 켜려면 주소·서버 인증서·개인키·클라이언트 CA를 모두 지정해야 합니다");
        }
        return factory -> factory.addAdditionalConnectors(connector(port, address, certificate, privateKey, clientCa));
    }

    private Connector connector(int port, String address, String certificate, String privateKey, String clientCa) {
        SSLHostConfig ssl = new SSLHostConfig();
        // 이 포트는 웹쉘 전용이라 인증서 없는 연결을 핸드셰이크에서 끊음
        ssl.setCertificateVerification("required");
        // Tomcat 기본 구현(JSSE)은 caCertificateFile을 쓰지 않으므로 PEM을 읽어 신뢰 저장소로 넘김
        ssl.setTrustStore(trustStore(clientCa));
        SSLHostConfigCertificate serverCertificate =
                new SSLHostConfigCertificate(ssl, SSLHostConfigCertificate.Type.UNDEFINED);
        serverCertificate.setCertificateFile(certificate);
        serverCertificate.setCertificateKeyFile(privateKey);
        ssl.addCertificate(serverCertificate);

        Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        connector.setScheme("https");
        connector.setSecure(true);
        connector.setPort(port);
        connector.setProperty("address", address);
        AbstractHttp11Protocol<?> protocol = (AbstractHttp11Protocol<?>) connector.getProtocolHandler();
        protocol.setSSLEnabled(true);
        protocol.addSslHostConfig(ssl);
        return connector;
    }

    private KeyStore trustStore(String clientCa) {
        try (InputStream in = Files.newInputStream(Path.of(clientCa))) {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            int index = 0;
            for (Certificate certificate : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                store.setCertificateEntry("client-ca-" + index++, certificate);
            }
            if (index == 0) {
                throw new IllegalStateException("콜백 클라이언트 CA 파일에 인증서가 없습니다");
            }
            return store;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("콜백 클라이언트 CA 파일을 읽을 수 없습니다", e);
        }
    }

    // 콜백 포트로는 콜백 경로만 받음
    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> callbackPortPathFilter(
            @Value("${webshell.callback-tls.port}") int port) {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                if (request.getLocalPort() == port && !request.getRequestURI().startsWith(CALLBACK_PATH_PREFIX)) {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND);
                    return;
                }
                chain.doFilter(request, response);
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
