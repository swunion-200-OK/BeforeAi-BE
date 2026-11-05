package site.siyoung.beforeaibe.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

// 문서 롤백 요청 (3.7 POST /requests/{requestId}/rollback). 필드는 컬렉션 예시를 따름.
// targetVersion은 모든 대상 문서에 같은 번호로 적용함. reason은 저장할 컬럼이 없어 아직 받기만 함
public record RollbackRequest(
        @NotEmpty List<@NotNull UUID> taskIds,
        @NotNull @Min(1) Integer targetVersion,
        String reason
) {
}
