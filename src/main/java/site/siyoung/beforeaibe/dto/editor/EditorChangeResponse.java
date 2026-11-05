package site.siyoung.beforeaibe.dto.editor;

import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.enums.RequestSource;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;

import java.util.UUID;

// POST /projects/{projectId}/editor/changes 응답 (202). 에디터 변경으로 만든 요청의 식별자·출처·상태 (API 명세서 3.8)
public record EditorChangeResponse(UUID requestId, RequestSource source, RequestStatus status) {

    public static EditorChangeResponse from(Request request) {
        return new EditorChangeResponse(request.getId(), request.getSource(), request.getStatus());
    }
}
