package site.siyoung.beforeaibe.dto.project;

import com.fasterxml.jackson.annotation.JsonInclude;
import site.siyoung.beforeaibe.entity.SpecVersion;

import java.time.Instant;
import java.util.UUID;

// restoredFromVersion은 이전 버전을 복원해 만든 버전에만 있음
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SpecResponse(
        UUID projectId,
        int version,
        String body,
        Instant createdAt,
        UUID createdBy,
        Integer restoredFromVersion
) {

    public static SpecResponse from(SpecVersion specVersion) {
        return new SpecResponse(specVersion.getProject().getId(), specVersion.getVersion(), specVersion.getBody(),
                specVersion.getCreatedAt(), specVersion.getCreatedBy().getId(),
                specVersion.getRestoredFromVersion());
    }
}
