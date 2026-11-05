package site.siyoung.beforeaibe.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.dto.project.ProjectOwnersUpdateRequest;
import site.siyoung.beforeaibe.dto.common.CursorPageResponse;
import site.siyoung.beforeaibe.dto.project.ProjectCreateRequest;
import site.siyoung.beforeaibe.dto.project.ProjectResponse;
import site.siyoung.beforeaibe.entity.Department;
import site.siyoung.beforeaibe.entity.Project;
import site.siyoung.beforeaibe.entity.ProjectOwner;
import site.siyoung.beforeaibe.entity.SpecVersion;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.Role;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;
import site.siyoung.beforeaibe.repository.DepartmentRepository;
import site.siyoung.beforeaibe.repository.ProjectOwnerRepository;
import site.siyoung.beforeaibe.repository.ProjectRepository;
import site.siyoung.beforeaibe.repository.SpecVersionRepository;
import site.siyoung.beforeaibe.repository.UserRepository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

// 프로젝트 목록·상세·생성 (3.3, ADM-08)
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectService {

    private static final int MIN_OWNERS = 2;
    private static final int INITIAL_SPEC_VERSION = 1;

    private final ProjectRepository projectRepository;
    private final ProjectOwnerRepository projectOwnerRepository;
    private final DepartmentRepository departmentRepository;
    private final SpecVersionRepository specVersionRepository;
    private final UserRepository userRepository;

    public CursorPageResponse<ProjectResponse> list(String cursor, int limit) {
        // 다음 페이지가 있는지 알기 위해 1개 더 조회
        Limit fetchLimit = Limit.of(limit + 1);
        List<Project> projects;
        if (cursor == null || cursor.isBlank()) {
            projects = projectRepository.findAllByOrderByCreatedAtDescIdDesc(fetchLimit);
        } else {
            Cursor decoded = Cursor.decode(cursor);
            projects = projectRepository.findPageAfter(decoded.createdAt(), decoded.id(), fetchLimit);
        }

        boolean hasNext = projects.size() > limit;
        List<Project> page = hasNext ? projects.subList(0, limit) : projects;
        String nextCursor = hasNext ? Cursor.of(page.getLast()).encode() : null;

        Map<UUID, List<User>> owners = ownersByProjectId(page.stream().map(Project::getId).toList());
        List<ProjectResponse> items = page.stream()
                .map(project -> ProjectResponse.of(project, owners.getOrDefault(project.getId(), List.of())))
                .toList();
        return new CursorPageResponse<>(items, nextCursor);
    }

    public ProjectResponse get(UUID projectId) {
        Project project = getProject(projectId);
        return ProjectResponse.of(project, ownersByProjectId(List.of(projectId)).getOrDefault(projectId, List.of()));
    }

    // 프로젝트와 담당자, 초기 기획서(version 1)를 한 번에 만듦
    @Transactional
    public ProjectResponse create(UUID userId, ProjectCreateRequest request) {
        User creator = findUser(userId);
        if (creator.getRole() != Role.ADMIN) {
            throw new ApiException(ErrorCode.FORBIDDEN_ROLE);
        }

        // 같은 사람을 두 번 넣은 경우는 한 명으로 셈
        Set<UUID> ownerIds = new LinkedHashSet<>(request.ownerIds());
        if (ownerIds.size() < MIN_OWNERS) {
            throw new ApiException(ErrorCode.OWNER_MINIMUM, "담당자는 2명 이상이어야 합니다");
        }

        Department department = departmentRepository.findById(request.departmentId())
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR, "존재하지 않는 부서입니다"));

        Map<UUID, User> usersById = userRepository.findAllById(ownerIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        if (usersById.size() != ownerIds.size()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "존재하지 않는 담당자가 있습니다");
        }
        // 요청에 적힌 순서대로 담당자를 등록
        List<User> owners = ownerIds.stream().map(usersById::get).toList();
        if (owners.stream().anyMatch(owner -> !Objects.equals(owner.getDepartmentId(), department.getId()))) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "담당자는 프로젝트 담당 부서 소속이어야 합니다");
        }

        Project project = Project.builder()
                .name(request.name())
                .department(department)
                .repository(request.repository())
                .build();
        owners.forEach(project::addOwner);
        projectRepository.save(project);

        specVersionRepository.save(SpecVersion.builder()
                .project(project)
                .version(INITIAL_SPEC_VERSION)
                .body(request.initialSpec().body())
                .createdBy(creator)
                .build());

        return ProjectResponse.of(project, owners);
    }

    // 담당자 교체. 빠지는 사람·새 사람만 바꿔 (project, user) unique 위반을 피함.
    // 이미 만들어진 Plan의 승인 후보는 생성 시점 값이라 바뀌지 않음
    @Transactional
    public ProjectResponse replaceOwners(UUID userId, UUID projectId, ProjectOwnersUpdateRequest request) {
        Project project = getManageableProjectForUpdate(projectId, userId);
        Set<UUID> ownerIds = new LinkedHashSet<>(request.ownerIds());
        if (ownerIds.size() < MIN_OWNERS) {
            throw new ApiException(ErrorCode.OWNER_MINIMUM, "담당자는 2명 이상이어야 합니다");
        }
        Map<UUID, User> usersById = userRepository.findAllById(ownerIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        if (usersById.size() != ownerIds.size()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "존재하지 않는 담당자가 있습니다");
        }
        List<User> owners = ownerIds.stream().map(usersById::get).toList();
        if (owners.stream().anyMatch(owner -> !Objects.equals(owner.getDepartmentId(), project.getDepartment().getId()))) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "담당자는 프로젝트 담당 부서 소속이어야 합니다");
        }

        List<User> current = project.getOwners().stream().map(ProjectOwner::getUser).toList();
        current.stream().filter(owner -> !ownerIds.contains(owner.getId())).forEach(project::removeOwner);
        Set<UUID> currentIds = current.stream().map(User::getId).collect(Collectors.toSet());
        owners.stream().filter(owner -> !currentIds.contains(owner.getId())).forEach(project::addOwner);
        return ProjectResponse.of(project, owners);
    }

    public Project getProject(UUID projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "프로젝트를 찾을 수 없습니다"));
    }

    // 기획서 수정·복원, 수정 요청 반영·반려 권한: ADMIN 또는 프로젝트 담당 부서의 DEPT_MANAGER.
    // 새 버전 번호가 겹치지 않도록 프로젝트 행을 잠그므로 호출한 쪽 트랜잭션 안에서만 씀
    @Transactional(propagation = Propagation.MANDATORY)
    public Project getManageableProjectForUpdate(UUID projectId, UUID userId) {
        Project project = projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "프로젝트를 찾을 수 없습니다"));
        User user = findUser(userId);
        boolean departmentManager = user.getRole() == Role.DEPT_MANAGER
                && project.getDepartment().getId().equals(user.getDepartmentId());
        if (user.getRole() != Role.ADMIN && !departmentManager) {
            throw new ApiException(ErrorCode.FORBIDDEN_ROLE);
        }
        return project;
    }

    private Map<UUID, List<User>> ownersByProjectId(List<UUID> projectIds) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        return projectOwnerRepository.findWithUserByProjectIdIn(projectIds).stream()
                .collect(Collectors.groupingBy(owner -> owner.getProject().getId(),
                        Collectors.mapping(ProjectOwner::getUser, Collectors.toList())));
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
    }

    // 목록 커서: 마지막 항목의 (createdAt, id)를 Base64URL로 감싼 값. 클라이언트는 내용을 해석하지 않고 그대로 돌려보냄
    private record Cursor(Instant createdAt, UUID id) {

        static Cursor of(Project project) {
            return new Cursor(project.getCreatedAt(), project.getId());
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
