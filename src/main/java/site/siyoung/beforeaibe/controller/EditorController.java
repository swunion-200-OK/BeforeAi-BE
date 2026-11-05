package site.siyoung.beforeaibe.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import site.siyoung.beforeaibe.dto.editor.EditorChangeRequest;
import site.siyoung.beforeaibe.dto.editor.EditorChangeResponse;
import site.siyoung.beforeaibe.dto.editor.FileContentResponse;
import site.siyoung.beforeaibe.dto.editor.FileListResponse;
import site.siyoung.beforeaibe.security.AuthUser;
import site.siyoung.beforeaibe.security.InternalNetworkOnly;
import site.siyoung.beforeaibe.service.EditorService;

import java.util.UUID;

// 웹 코드 에디터 (API 명세서 3.8, CORE-05). 세 엔드포인트 모두 권한=전체, 망=내부
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
@RequiredArgsConstructor
public class EditorController {

    private final EditorService editorService;

    // #1 저장소 디렉터리 목록 + 기준 커밋 (읽기 전용)
    @InternalNetworkOnly
    @GetMapping("/files")
    public FileListResponse files(@PathVariable UUID projectId,
                                  @RequestParam(required = false) String path,
                                  @RequestParam(required = false) String ref) {
        return editorService.listFiles(projectId, path, ref);
    }

    // #2 저장소 파일 내용 (읽기 전용)
    @InternalNetworkOnly
    @GetMapping("/files/content")
    public FileContentResponse content(@PathVariable UUID projectId,
                                       @RequestParam String path,
                                       @RequestParam(required = false) String ref) {
        return editorService.readFile(projectId, path, ref);
    }

    // #3 에디터 변경을 source=EDITOR 요청으로 저장. 기준 커밋이 바뀌었으면 409 EDIT_CONFLICT
    @InternalNetworkOnly
    @PostMapping("/editor/changes")
    public ResponseEntity<EditorChangeResponse> changes(@AuthenticationPrincipal AuthUser authUser,
                                                        @PathVariable UUID projectId,
                                                        @Valid @RequestBody EditorChangeRequest request) {
        return ResponseEntity.accepted().body(editorService.createChange(authUser.id(), projectId, request));
    }
}
