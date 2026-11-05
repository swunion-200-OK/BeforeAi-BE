package site.siyoung.beforeaibe.ai;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import site.siyoung.beforeaibe.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

// bedrock.enabled=true 에서 컨텍스트가 정상 기동하는지 검증.
// (ObjectMapper 미주입·applicationTaskExecutor 소실 회귀를 막는다. 실제 Bedrock 호출은 없음)
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "bedrock.enabled=true",
        "bedrock.region=ap-southeast-1",
        "bedrock.model-id=apac.anthropic.claude-sonnet-5-5"
})
class BedrockEnabledContextTest {

    @Autowired
    ApplicationContext context;

    @Test
    void contextLoadsWithBedrockEnabled() {
        // BedrockAiAnalyzer 가 활성, Noop 은 비활성
        assertThat(context.getBeansOfType(AiAnalyzer.class)).hasSize(1);
        assertThat(context.containsBean("bedrockAiAnalyzer")).isTrue();
        // 기본 TaskExecutor 가 복원되어 AuditExportService 등이 주입받을 수 있어야 함
        assertThat(context.containsBean("applicationTaskExecutor")).isTrue();
    }
}
