package site.siyoung.beforeaibe.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import site.siyoung.beforeaibe.dto.admin.CreateDepartmentRequest;
import site.siyoung.beforeaibe.dto.admin.DepartmentView;
import site.siyoung.beforeaibe.security.InternalNetworkOnly;
import site.siyoung.beforeaibe.service.DepartmentService;

// 부서 생성 (ADM-02). ADMIN + 내부망
@RestController
@RequestMapping("/api/v1/admin/departments")
@RequiredArgsConstructor
@InternalNetworkOnly
public class AdminDepartmentController {

    private final DepartmentService departmentService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DepartmentView create(@Valid @RequestBody CreateDepartmentRequest request) {
        return departmentService.create(request);
    }
}
