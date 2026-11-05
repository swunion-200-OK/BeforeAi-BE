package site.siyoung.beforeaibe.dto.project;

import site.siyoung.beforeaibe.entity.Project;
import site.siyoung.beforeaibe.entity.User;

import java.util.List;
import java.util.UUID;

public record ProjectResponse(
        UUID id,
        String name,
        UUID departmentId,
        String repository,
        List<OwnerResponse> owners
) {

    public static ProjectResponse of(Project project, List<User> owners) {
        return new ProjectResponse(project.getId(), project.getName(), project.getDepartment().getId(),
                project.getRepository(), owners.stream().map(OwnerResponse::from).toList());
    }
}
