package site.siyoung.beforeaibe.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import site.siyoung.beforeaibe.entity.enums.Network;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;

@Component
@RequiredArgsConstructor
public class InternalNetworkInterceptor implements HandlerInterceptor {

    private final NetworkResolver networkResolver;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod handlerMethod
                && isInternalOnly(handlerMethod)
                && networkResolver.resolve(request) != Network.INTERNAL) {
            throw new ApiException(ErrorCode.INTERNAL_NETWORK_ONLY);
        }
        return true;
    }

    private boolean isInternalOnly(HandlerMethod handlerMethod) {
        return handlerMethod.hasMethodAnnotation(InternalNetworkOnly.class)
                || handlerMethod.getBeanType().isAnnotationPresent(InternalNetworkOnly.class);
    }
}
