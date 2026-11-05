package site.siyoung.beforeaibe.dto.editor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

// POST /projects/{projectId}/editor/changes 요청: 조회 당시 기준 커밋 + 변경 목적 + 파일별 diff (API 명세서 3.8)
public record EditorChangeRequest(
        @NotBlank String baseCommit,
        @NotBlank String description,
        @NotEmpty @Valid List<FileChange> files
) {
}
