package site.siyoung.beforeaibe.webhook;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;

// 등록된 webhook URL로 JSON 본문을 POST한다. 알림은 부가기능이므로 호출 측에서
// 예외를 삼키도록 실패하면 false만 돌려주고 던지지 않는다.
@Slf4j
@Component
public class WebhookClient {

    private final RestClient restClient;

    public WebhookClient(@Value("${webhook.connect-timeout-seconds:3}") long connectTimeoutSeconds,
                         @Value("${webhook.read-timeout-seconds:5}") long readTimeoutSeconds) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    // 성공(2xx)이면 true, 실패면 false. 외부 수신자 장애가 본 흐름을 막지 않도록 예외를 삼킨다.
    public boolean post(String url, Object body) {
        try {
            restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientException e) {
            log.warn("webhook 발송 실패: {}", e.getClass().getSimpleName());
            return false;
        }
    }
}
