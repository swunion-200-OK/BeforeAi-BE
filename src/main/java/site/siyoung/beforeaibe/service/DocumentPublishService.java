package site.siyoung.beforeaibe.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.entity.Document;
import site.siyoung.beforeaibe.entity.DocumentVersion;
import site.siyoung.beforeaibe.entity.Project;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.Task;
import site.siyoung.beforeaibe.entity.enums.TaskType;
import site.siyoung.beforeaibe.repository.DocumentRepository;
import site.siyoung.beforeaibe.repository.DocumentVersionRepository;
import site.siyoung.beforeaibe.repository.ProjectRepository;

// DOC 작업이 성공하면 문서 버전을 쌓음 (CORE-06·07). 문서 롤백 Plan의 DOC 작업도 같은 경로라
// 복원한 본문이 새 버전으로 쌓이고 현재 버전이 그 버전으로 옮겨짐(기존 버전 행은 수정하지 않음)
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentPublishService {

    private final ProjectRepository projectRepository;
    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository documentVersionRepository;

    // 호출한 이벤트 처리 트랜잭션에 참여함. 저장할 수 없는 작업(본문 없음 등)은 경고만 남기고 넘어가
    // 작업 성공 처리가 실패하지 않게 함
    @Transactional
    public void publish(Request request, Task task) {
        if (task.getType() != TaskType.DOC) {
            return;
        }
        String body = task.getContent() == null ? null : task.getContent().body();
        if (body == null || body.isBlank()) {
            log.warn("문서 본문이 없어 버전을 저장하지 않습니다: requestId={}, taskId={}", request.getId(), task.getId());
            return;
        }
        String path = task.getTarget();
        if (path == null || path.isBlank()) {
            log.warn("문서 경로가 없어 버전을 저장하지 않습니다: requestId={}, taskId={}", request.getId(), task.getId());
            return;
        }

        // 요청 잠금은 요청 단위라 같은 문서를 다루는 다른 요청과 겹칠 수 있음. 프로젝트 행을 잠가
        // 문서 생성(unique)과 버전 번호 발급을 프로젝트 단위로 직렬화함
        Project project = projectRepository.findByIdForUpdate(request.getProjectId()).orElse(null);
        if (project == null) {
            log.warn("프로젝트를 찾을 수 없어 문서 버전을 저장하지 않습니다: requestId={}", request.getId());
            return;
        }
        Document document = documentRepository.findByProject_IdAndPath(project.getId(), path)
                .orElseGet(() -> documentRepository.save(Document.builder()
                        .project(project)
                        .title(task.getTitle() == null || task.getTitle().isBlank() ? path : task.getTitle())
                        .path(path)
                        .currentVersion(0)
                        .build()));

        int next = documentVersionRepository.findByDocument_IdOrderByVersionDesc(document.getId(), Limit.of(1))
                .stream().findFirst().map(DocumentVersion::getVersion).orElse(0) + 1;
        documentVersionRepository.save(DocumentVersion.builder()
                .document(document)
                .version(next)
                .body(body)
                .request(request)
                .build());
        document.changeCurrentVersion(next);
    }
}
