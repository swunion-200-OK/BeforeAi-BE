package site.siyoung.beforeaibe.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.dto.common.CursorPageResponse;
import site.siyoung.beforeaibe.dto.project.SpecResponse;
import site.siyoung.beforeaibe.dto.project.SpecRestoreRequest;
import site.siyoung.beforeaibe.dto.project.SpecUpdateRequest;
import site.siyoung.beforeaibe.entity.Project;
import site.siyoung.beforeaibe.entity.SpecVersion;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;
import site.siyoung.beforeaibe.repository.SpecVersionRepository;
import site.siyoung.beforeaibe.repository.UserRepository;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

// 기획서 조회·수정·버전 이력·복원 (5.8). 기존 버전은 고치지 않고 항상 새 버전을 쌓음
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectSpecService {

    private final ProjectService projectService;
    private final SpecVersionRepository specVersionRepository;
    private final UserRepository userRepository;

    public SpecResponse getCurrent(UUID projectId) {
        projectService.getProject(projectId);
        return specVersionRepository.findTopByProjectIdOrderByVersionDesc(projectId)
                .map(SpecResponse::from)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "기획서를 찾을 수 없습니다"));
    }

    @Transactional
    public SpecResponse update(UUID projectId, UUID userId, SpecUpdateRequest request) {
        Project project = projectService.getManageableProjectForUpdate(projectId, userId);
        return SpecResponse.from(appendVersion(project, request.body(), request.reason(), null, userId));
    }

    // 최신 버전부터 내려가며 조회
    public CursorPageResponse<SpecResponse> versions(UUID projectId, String cursor, int limit) {
        projectService.getProject(projectId);
        // 다음 페이지가 있는지 알기 위해 1개 더 조회
        Limit fetchLimit = Limit.of(limit + 1);
        List<SpecVersion> versions;
        if (cursor == null || cursor.isBlank()) {
            versions = specVersionRepository.findByProjectIdOrderByVersionDesc(projectId, fetchLimit);
        } else {
            versions = specVersionRepository.findByProjectIdAndVersionLessThanOrderByVersionDesc(
                    projectId, decodeCursor(cursor), fetchLimit);
        }

        boolean hasNext = versions.size() > limit;
        List<SpecVersion> page = hasNext ? versions.subList(0, limit) : versions;
        String nextCursor = hasNext ? encodeCursor(page.getLast().getVersion()) : null;
        return new CursorPageResponse<>(page.stream().map(SpecResponse::from).toList(), nextCursor);
    }

    // 지정한 버전의 내용으로 새 버전을 만듦
    @Transactional
    public SpecResponse restore(UUID projectId, int version, UUID userId, SpecRestoreRequest request) {
        Project project = projectService.getManageableProjectForUpdate(projectId, userId);
        SpecVersion target = specVersionRepository.findByProjectIdAndVersion(projectId, version)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "기획서 버전을 찾을 수 없습니다"));
        String reason = request == null ? null : request.reason();
        return SpecResponse.from(appendVersion(project, target.getBody(), reason, version, userId));
    }

    // 현재 가장 큰 version + 1로 저장. 동시 요청이 같은 번호를 받지 않도록 호출한 쪽에서 프로젝트 행을 잠근 상태여야 함
    @Transactional(propagation = Propagation.MANDATORY)
    public SpecVersion appendVersion(Project project, String body, String reason, Integer restoredFromVersion,
                                     UUID userId) {
        int nextVersion = specVersionRepository.findTopByProjectIdOrderByVersionDesc(project.getId())
                .map(current -> current.getVersion() + 1)
                .orElse(1);
        return specVersionRepository.save(SpecVersion.builder()
                .project(project)
                .version(nextVersion)
                .body(body)
                .reason(reason)
                .restoredFromVersion(restoredFromVersion)
                .createdBy(userRepository.getReferenceById(userId))
                .build());
    }

    // 버전 이력 커서: 마지막으로 받은 version을 Base64URL로 감싼 값
    private static String encodeCursor(int version) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.valueOf(version).getBytes(StandardCharsets.UTF_8));
    }

    private static int decodeCursor(String value) {
        try {
            return Integer.parseInt(new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "cursor 값이 올바르지 않습니다");
        }
    }
}
