package site.siyoung.beforeaibe.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.dto.common.CursorPageResponse;
import site.siyoung.beforeaibe.dto.project.SpecChangeRequestAcceptResponse;
import site.siyoung.beforeaibe.dto.project.SpecChangeRequestCreateRequest;
import site.siyoung.beforeaibe.dto.project.SpecChangeRequestRejectRequest;
import site.siyoung.beforeaibe.dto.project.SpecChangeRequestResponse;
import site.siyoung.beforeaibe.dto.project.SpecResponse;
import site.siyoung.beforeaibe.entity.Project;
import site.siyoung.beforeaibe.entity.SpecChangeRequest;
import site.siyoung.beforeaibe.entity.SpecVersion;
import site.siyoung.beforeaibe.entity.enums.SpecChangeRequestStatus;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;
import site.siyoung.beforeaibe.repository.SpecChangeRequestRepository;
import site.siyoung.beforeaibe.repository.UserRepository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

// 기획서 수정 요청 (EMP-05, DEP-07). 제안만으로는 기획서가 바뀌지 않고, 반영하면 content가 새 기획서 버전이 됨
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SpecChangeRequestService {

    private final ProjectService projectService;
    private final ProjectSpecService projectSpecService;
    private final SpecChangeRequestRepository specChangeRequestRepository;
    private final UserRepository userRepository;

    @Transactional
    public SpecChangeRequestResponse create(UUID projectId, UUID userId, SpecChangeRequestCreateRequest request) {
        Project project = projectService.getProject(projectId);
        SpecChangeRequest saved = specChangeRequestRepository.save(SpecChangeRequest.builder()
                .project(project)
                .requester(userRepository.getReferenceById(userId))
                .content(request.content())
                .reason(request.reason())
                .status(SpecChangeRequestStatus.OPEN)
                .build());
        return SpecChangeRequestResponse.from(saved);
    }

    // status가 null이면 모든 상태를 최신순으로 조회
    public CursorPageResponse<SpecChangeRequestResponse> list(UUID projectId, SpecChangeRequestStatus status,
                                                              String cursor, int limit) {
        projectService.getProject(projectId);
        // 다음 페이지가 있는지 알기 위해 1개 더 조회
        Limit fetchLimit = Limit.of(limit + 1);
        List<SpecChangeRequest> changeRequests;
        if (cursor == null || cursor.isBlank()) {
            changeRequests = specChangeRequestRepository.findPage(projectId, status, fetchLimit);
        } else {
            Cursor decoded = Cursor.decode(cursor);
            changeRequests = specChangeRequestRepository.findPageAfter(
                    projectId, status, decoded.createdAt(), decoded.id(), fetchLimit);
        }

        boolean hasNext = changeRequests.size() > limit;
        List<SpecChangeRequest> page = hasNext ? changeRequests.subList(0, limit) : changeRequests;
        String nextCursor = hasNext ? Cursor.of(page.getLast()).encode() : null;
        return new CursorPageResponse<>(page.stream().map(SpecChangeRequestResponse::from).toList(), nextCursor);
    }

    @Transactional
    public SpecChangeRequestAcceptResponse accept(UUID projectId, UUID crId, UUID userId) {
        // 프로젝트 행을 잠가 같은 요청을 동시에 반영·반려해도 한 번만 처리되게 함
        Project project = projectService.getManageableProjectForUpdate(projectId, userId);
        SpecChangeRequest changeRequest = findOpen(projectId, crId);
        changeRequest.accept();
        // 수정 요청의 사유를 새 버전의 수정 사유로 남김
        SpecVersion spec = projectSpecService.appendVersion(
                project, changeRequest.getContent(), changeRequest.getReason(), null, userId);
        return new SpecChangeRequestAcceptResponse(SpecChangeRequestResponse.from(changeRequest), SpecResponse.from(spec));
    }

    @Transactional
    public SpecChangeRequestResponse reject(UUID projectId, UUID crId, UUID userId,
                                            SpecChangeRequestRejectRequest request) {
        projectService.getManageableProjectForUpdate(projectId, userId);
        SpecChangeRequest changeRequest = findOpen(projectId, crId);
        changeRequest.reject(request.reason());
        return SpecChangeRequestResponse.from(changeRequest);
    }

    // 이미 반영·반려된 요청은 다시 처리할 수 없음
    private SpecChangeRequest findOpen(UUID projectId, UUID crId) {
        SpecChangeRequest changeRequest = specChangeRequestRepository.findByIdAndProjectId(crId, projectId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "기획서 수정 요청을 찾을 수 없습니다"));
        if (changeRequest.getStatus() != SpecChangeRequestStatus.OPEN) {
            throw new ApiException(ErrorCode.INVALID_STATE, "이미 처리된 수정 요청입니다");
        }
        return changeRequest;
    }

    // 목록 커서: 마지막 항목의 (createdAt, id)를 Base64URL로 감싼 값. 클라이언트는 내용을 해석하지 않고 그대로 돌려보냄
    private record Cursor(Instant createdAt, UUID id) {

        static Cursor of(SpecChangeRequest changeRequest) {
            return new Cursor(changeRequest.getCreatedAt(), changeRequest.getId());
        }

        static Cursor decode(String value) {
            try {
                String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
                String[] parts = decoded.split("\\|", 2);
                return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
            } catch (RuntimeException e) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "cursor 값이 올바르지 않습니다");
            }
        }

        String encode() {
            String raw = createdAt + "|" + id;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }
    }
}
