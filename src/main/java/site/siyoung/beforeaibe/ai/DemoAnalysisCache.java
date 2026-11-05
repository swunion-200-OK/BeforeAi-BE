package site.siyoung.beforeaibe.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import site.siyoung.beforeaibe.ai.dto.AiTask;
import site.siyoung.beforeaibe.ai.dto.AnalysisResult;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

// 시연용 분석 캐시. 프롬프트가 정확히 같으면 실제 Bedrock으로 미리 분석해 저장한 결과를 돌려준다.
// 기본은 꺼져 있고, 파일 내용은 손으로 고치지 않고 다시 분석한 결과로만 바꾼다.
// 작업 ID·Plan 해시·승인자·금지 규칙 검사는 이후 저장 경로에서 요청마다 새로 처리되므로 서명 검증은 그대로다.
@Slf4j
@Component
public class DemoAnalysisCache {

    private final long delayMs;
    private final Map<String, List<AiTask>> entries;

    public DemoAnalysisCache(@Value("${ai.demo-cache.enabled:false}") boolean enabled,
                             @Value("${ai.demo-cache.delay-ms:1500}") long delayMs,
                             @Value("classpath:ai/demo-analysis-cache.json") Resource resource) {
        this.delayMs = delayMs;
        this.entries = enabled ? load(resource) : Map.of();
    }

    // 파일이 깨져도 앱 기동을 막지 않고 실시간 분석으로 돌아감
    private static Map<String, List<AiTask>> load(Resource resource) {
        try (InputStream input = resource.getInputStream()) {
            CacheFile file = new ObjectMapper().readValue(input, CacheFile.class);
            Map<String, List<AiTask>> loaded = file.entries().stream()
                    .collect(Collectors.toUnmodifiableMap(entry -> entry.prompt().strip(), Entry::tasks));
            log.info("시연 분석 캐시 켜짐: {}건", loaded.size());
            return loaded;
        } catch (IOException | RuntimeException e) {
            log.error("시연 분석 캐시를 읽지 못해 실시간 분석을 사용합니다", e);
            return Map.of();
        }
    }

    public Optional<AnalysisResult> find(String prompt) {
        if (prompt == null) {
            return Optional.empty();
        }
        List<AiTask> tasks = entries.get(prompt.strip());
        if (tasks == null) {
            return Optional.empty();
        }
        pause();
        return Optional.of(AnalysisResult.plan(tasks));
    }

    // 분석 중 화면이 잠깐 보이도록 기다림
    private void pause() {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record CacheFile(List<Entry> entries) {
    }

    private record Entry(String prompt, List<AiTask> tasks) {
    }
}
