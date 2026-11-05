package site.siyoung.beforeaibe.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClientBuilder;

import java.time.Duration;

// bedrock.enabled=true일 때만 Bedrock 클라이언트를 등록한다.
// 자격증명은 기본 제공자 체인(EC2 인스턴스 IAM Role 등)을 쓰고, 리전은 설정이 있으면 지정·없으면 기본 체인.
@Configuration
@ConditionalOnProperty(name = "bedrock.enabled", havingValue = "true")
public class BedrockConfig {

    @Bean
    BedrockRuntimeClient bedrockRuntimeClient(@Value("${bedrock.region:}") String region,
                                              @Value("${bedrock.timeout-seconds:120}") long timeoutSeconds) {
        // 비스트리밍 호출은 생성이 끝날 때까지 응답이 없을 수 있어, SDK 기본 읽기 타임아웃(30초)이면 긴 응답이 끊김
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        BedrockRuntimeClientBuilder builder = BedrockRuntimeClient.builder()
                .httpClientBuilder(ApacheHttpClient.builder().socketTimeout(timeout))
                .overrideConfiguration(config -> config.apiCallAttemptTimeout(timeout));
        if (region != null && !region.isBlank()) {
            builder.region(Region.of(region.trim()));
        }
        return builder.build();
    }
}
