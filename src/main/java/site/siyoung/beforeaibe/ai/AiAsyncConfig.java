package site.siyoung.beforeaibe.ai;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

// AI 분석을 요청 스레드와 분리해 돌리기 위한 전용 executor.
@Configuration
@EnableAsync
public class AiAsyncConfig {

    public static final String EXECUTOR = "aiAnalyzerExecutor";

    @Bean(EXECUTOR)
    Executor aiAnalyzerExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ai-analyzer-");
        executor.initialize();
        return executor;
    }
}
