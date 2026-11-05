package site.siyoung.beforeaibe.dto.editor;

import jakarta.validation.constraints.NotBlank;

// 에디터가 저장할 파일 1개의 변경: 경로 + unified diff patch (API 명세서 3.8)
public record FileChange(
        @NotBlank String path,
        @NotBlank String patch
) {
}
