package site.siyoung.beforeaibe.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import site.siyoung.beforeaibe.dto.admin.CreateDepartmentRequest;
import site.siyoung.beforeaibe.dto.admin.DepartmentView;
import site.siyoung.beforeaibe.dto.common.CursorPageResponse;
import site.siyoung.beforeaibe.entity.Department;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;
import site.siyoung.beforeaibe.repository.DepartmentRepository;

// 부서 조회(전체)·생성(ADMIN). 목록은 이름순 정렬
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DepartmentService {

    private final DepartmentRepository departmentRepository;

    public CursorPageResponse<DepartmentView> list(String cursor, int limit) {
        Limit fetchLimit = Limit.of(limit + 1);
        List<Department> departments;
        if (cursor == null || cursor.isBlank()) {
            departments = departmentRepository.findByOrderByNameAscIdAsc(fetchLimit);
        } else {
            NameCursor decoded = NameCursor.decode(cursor);
            departments = departmentRepository.findPageAfter(decoded.name(), decoded.id(), fetchLimit);
        }

        boolean hasNext = departments.size() > limit;
        List<Department> page = hasNext ? departments.subList(0, limit) : departments;
        String nextCursor = hasNext
                ? new NameCursor(page.getLast().getName(), page.getLast().getId()).encode()
                : null;
        return new CursorPageResponse<>(page.stream().map(DepartmentView::from).toList(), nextCursor);
    }

    @Transactional
    public DepartmentView create(CreateDepartmentRequest request) {
        if (departmentRepository.existsByName(request.name())) {
            throw new ApiException(ErrorCode.INVALID_STATE, "이미 존재하는 부서 이름입니다");
        }
        Department saved = departmentRepository.save(Department.builder().name(request.name()).build());
        return DepartmentView.from(saved);
    }

    // 이름순 목록 커서: (name, id)
    private record NameCursor(String name, UUID id) {

        static NameCursor decode(String value) {
            try {
                String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
                String[] parts = decoded.split("\\|", 2);
                return new NameCursor(parts[0], UUID.fromString(parts[1]));
            } catch (RuntimeException e) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "cursor 값이 올바르지 않습니다");
            }
        }

        String encode() {
            String raw = name + "|" + id;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }
    }
}
