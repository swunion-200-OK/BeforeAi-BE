package site.siyoung.beforeaibe.service;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.dto.common.CursorPageResponse;
import site.siyoung.beforeaibe.dto.document.DocumentResponse;
import site.siyoung.beforeaibe.dto.document.DocumentVersionResponse;
import site.siyoung.beforeaibe.dto.document.DocumentVersionSummaryResponse;
import site.siyoung.beforeaibe.dto.document.TaskDocumentResponse;
import site.siyoung.beforeaibe.entity.Document;
import site.siyoung.beforeaibe.entity.DocumentVersion;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.Task;
import site.siyoung.beforeaibe.entity.enums.TaskStatus;
import site.siyoung.beforeaibe.entity.enums.TaskType;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;
import site.siyoung.beforeaibe.repository.DocumentRepository;
import site.siyoung.beforeaibe.repository.DocumentVersionRepository;
import site.siyoung.beforeaibe.repository.PlanRepository;
import site.siyoung.beforeaibe.support.CreatedAtCursor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// 문서 목록·버전 조회와 DOC 작업 문서 diff (3.7, CORE-06·07). 조회만 하며 문서를 적용·복원하지 않음
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DocumentService {

    // unified diff에서 바뀐 줄 앞뒤로 보여 줄 줄 수 (git diff 기본값과 같음)
    private static final int DIFF_CONTEXT_LINES = 3;

    private final ProjectService projectService;
    private final RequestService requestService;
    private final PlanRepository planRepository;
    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository documentVersionRepository;

    public CursorPageResponse<DocumentResponse> list(UUID projectId, String cursor, int limit) {
        projectService.getProject(projectId);
        // 다음 페이지가 있는지 알기 위해 1개 더 조회
        Limit fetchLimit = Limit.of(limit + 1);
        List<Document> documents;
        if (cursor == null || cursor.isBlank()) {
            documents = documentRepository.findByProject_IdOrderByCreatedAtDescIdDesc(projectId, fetchLimit);
        } else {
            CreatedAtCursor decoded = CreatedAtCursor.decode(cursor);
            documents = documentRepository.findPageAfter(projectId, decoded.createdAt(), decoded.id(), fetchLimit);
        }

        boolean hasNext = documents.size() > limit;
        List<Document> page = hasNext ? documents.subList(0, limit) : documents;
        String nextCursor = hasNext
                ? new CreatedAtCursor(page.getLast().getCreatedAt(), page.getLast().getId()).encode()
                : null;
        return new CursorPageResponse<>(page.stream().map(DocumentResponse::from).toList(), nextCursor);
    }

    // 최신 버전부터 내려가며 조회
    public CursorPageResponse<DocumentVersionSummaryResponse> versions(UUID documentId, String cursor, int limit) {
        getDocument(documentId);
        Limit fetchLimit = Limit.of(limit + 1);
        List<DocumentVersion> versions;
        if (cursor == null || cursor.isBlank()) {
            versions = documentVersionRepository.findByDocument_IdOrderByVersionDesc(documentId, fetchLimit);
        } else {
            versions = documentVersionRepository.findByDocument_IdAndVersionLessThanOrderByVersionDesc(
                    documentId, decodeVersionCursor(cursor), fetchLimit);
        }

        boolean hasNext = versions.size() > limit;
        List<DocumentVersion> page = hasNext ? versions.subList(0, limit) : versions;
        String nextCursor = hasNext ? encodeVersionCursor(page.getLast().getVersion()) : null;
        return new CursorPageResponse<>(page.stream().map(DocumentVersionSummaryResponse::from).toList(), nextCursor);
    }

    public DocumentVersionResponse version(UUID documentId, int version) {
        getDocument(documentId);
        return documentVersionRepository.findByDocument_IdAndVersion(documentId, version)
                .map(DocumentVersionResponse::from)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "문서 버전을 찾을 수 없습니다"));
    }

    // 기준 문서: 이 작업이 이미 적용됐으면 그 직전 버전, 아니면 현재 버전, 문서가 없으면 빈 문서
    public TaskDocumentResponse taskDocument(UUID userId, UUID requestId, UUID taskId) {
        Request request = requestService.getViewableRequest(requestId, userId);
        Task task = findRequestTask(request, taskId);
        if (task.getType() != TaskType.DOC) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "문서 작업이 아닙니다");
        }

        String body = task.getContent() == null || task.getContent().body() == null ? "" : task.getContent().body();
        Optional<Document> document = documentRepository.findByProject_IdAndPath(request.getProjectId(),
                task.getTarget());
        String baseBody = document.map(found -> baseBody(found, request.getId(), task)).orElse("");
        return new TaskDocumentResponse(document.map(Document::getId).orElse(null), task.getId(), body,
                unifiedDiff(task.getTarget(), baseBody, body));
    }

    private Document getDocument(UUID documentId) {
        return documentRepository.findById(documentId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "문서를 찾을 수 없습니다"));
    }

    // 현재 Plan뿐 아니라 이 요청의 지난 Plan(삭제 반영·재시도 전)의 작업도 조회할 수 있음
    private Task findRequestTask(Request request, UUID taskId) {
        return planRepository.findByRequest_IdOrderByCreatedAtAscIdAsc(request.getId()).stream()
                .flatMap(plan -> plan.getTasks().stream())
                .filter(task -> task.getId().equals(taskId))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "작업을 찾을 수 없습니다"));
    }

    // 한 요청이 일반 작업과 롤백 작업으로 버전을 여러 개 만들 수 있으므로, 요청이 아니라 작업 기준으로 적용 여부를 판단함.
    // 그렇지 않으면 승인 전 롤백 작업의 diff가 비거나 틀려 승인자가 실제 변경을 볼 수 없음
    private String baseBody(Document document, UUID requestId, Task task) {
        Optional<DocumentVersion> applied = task.getStatus() == TaskStatus.SUCCEEDED
                ? findAppliedVersion(document, requestId, task)
                : Optional.empty();
        // 이 작업이 첫 버전을 만들었다면 직전 버전이 없으므로 빈 문서가 기준
        Optional<DocumentVersion> base = applied.isPresent()
                ? documentVersionRepository.findFirstByDocument_IdAndVersionLessThanOrderByVersionDesc(
                        document.getId(), applied.get().getVersion())
                : documentVersionRepository.findByDocument_IdAndVersion(document.getId(), document.getCurrentVersion());
        return base.map(DocumentVersion::getBody).orElse("");
    }

    // 버전 행에 작업 ID가 없으므로, 이 요청이 만든 버전 중 작업 본문과 같은 가장 최근 버전을 작업의 결과로 봄
    private Optional<DocumentVersion> findAppliedVersion(Document document, UUID requestId, Task task) {
        String body = task.getContent() == null ? null : task.getContent().body();
        return documentVersionRepository.findByDocument_IdAndRequest_IdOrderByVersionDesc(document.getId(), requestId)
                .stream()
                .filter(version -> version.getBody().equals(body))
                .findFirst();
    }

    // 바뀐 내용이 없으면 빈 문자열
    private static String unifiedDiff(String path, String original, String revised) {
        List<String> originalLines = original.lines().toList();
        List<String> revisedLines = revised.lines().toList();
        Patch<String> patch = DiffUtils.diff(originalLines, revisedLines);
        List<String> lines = UnifiedDiffUtils.generateUnifiedDiff("a/" + path, "b/" + path, originalLines, patch,
                DIFF_CONTEXT_LINES);
        return lines.isEmpty() ? "" : String.join("\n", lines) + "\n";
    }

    // 버전 이력 커서: 마지막으로 받은 version을 Base64URL로 감싼 값
    private static String encodeVersionCursor(int version) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.valueOf(version).getBytes(StandardCharsets.UTF_8));
    }

    private static int decodeVersionCursor(String value) {
        try {
            return Integer.parseInt(new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "cursor 값이 올바르지 않습니다");
        }
    }
}
