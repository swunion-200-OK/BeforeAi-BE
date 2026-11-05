package site.siyoung.beforeaibe.entity.value;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

// 승인자가 작업을 이해하도록 AI가 풀어 쓴 설명. 표시 전용이라 Plan 해시·서명·웹쉘 전달에 넣지 않음
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskExplanation(
        String summary,
        List<Change> changes,
        List<String> impact,
        String undo
) {

    // AI가 항목을 생략해도 프론트는 배열로 읽으므로 빈 배열로 맞춤. 시연 캐시도 이 생성자를 거침
    public TaskExplanation {
        changes = changes == null ? List.of() : changes;
        impact = impact == null ? List.of() : impact;
    }

    // 어디가 무엇으로 바뀌는지 한 줄. before/after는 알 때만 채움
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Change(
            String where,
            String what,
            String before,
            String after
    ) {
    }
}
