package site.siyoung.beforeaibe.dto.request;

import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;

import java.util.UUID;

// 요청 생성·분석 재시도·Plan 수정·취소의 응답 (API 명세서 4.1, 4.2)
public record RequestStatusResponse(UUID id, RequestStatus status) {

    public static RequestStatusResponse from(Request request) {
        return new RequestStatusResponse(request.getId(), request.getStatus());
    }
}
