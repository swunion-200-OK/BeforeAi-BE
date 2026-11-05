package site.siyoung.beforeaibe.dto.project;

public record SpecChangeRequestAcceptResponse(
        SpecChangeRequestResponse changeRequest,
        SpecResponse spec
) {
}
