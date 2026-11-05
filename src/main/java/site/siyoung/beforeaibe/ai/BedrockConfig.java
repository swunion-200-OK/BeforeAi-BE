package site.siyoung.beforeaibe.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClientBuilder;

// bedrock.enabled=true일 때만 Bedrock 클라이언트를 등록한다.
// 자격증명은 기본 제공자 체인(EC2 인스턴스 IAM Role 등)을 쓰고, 리전은 설정이 있으면 지정·없으면 기본 체인.
@Configuration
@ConditionalOnProperty(name = "bedrock.enabled", havingValue = "true")
public class BedrockConfig {

    @Bean
    BedrockRuntimeClient bedrockRuntimeClient(@Value("${bedrock.region:}") String region) {
        BedrockRuntimeClientBuilder builder = BedrockRuntimeClient.builder();
        if (region != null && !region.isBlank()) {
            builder.region(Region.of(region.trim()));
        }
        return builder.build();
    }
}
