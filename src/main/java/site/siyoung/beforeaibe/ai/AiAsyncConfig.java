package site.siyoung.beforeaibe.ai;

import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

// AI 분석을 요청 스레드와 분리해 돌리기 위한 전용 executor.
//
// 주의: 사용자가 Executor/TaskExecutor 빈을 직접 정의하면 Spring Boot 의 기본
// 'applicationTaskExecutor' 자동 구성이 비활성화된다. 그러면 그 빈을 @Qualifier로
// 요구하는 다른 코드(AuditExportService 등)가 깨지므로, Boot 기본 빈도 함께 복원한다.
@Configuration
@EnableAsync
public class AiAsyncConfig {

    public static final String EXECUTOR = "aiAnalyzerExecutor";

    // AI 분석 전용 executor
    @Bean(EXECUTOR)
    ThreadPoolTaskExecutor aiAnalyzerExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ai-analyzer-");
        executor.initialize();
        return executor;
    }

    // Spring Boot 기본 TaskExecutor 복원. 다른 빈이 @Qualifier("applicationTaskExecutor")로 주입받는다.
    @Bean("applicationTaskExecutor")
    ThreadPoolTaskExecutor applicationTaskExecutor(ThreadPoolTaskExecutorBuilder builder) {
        return builder.build();
    }
}
