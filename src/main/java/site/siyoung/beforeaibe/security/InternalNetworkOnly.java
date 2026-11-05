package site.siyoung.beforeaibe.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// 명세 표의 망 열이 "내부"인 API에 붙임. 외부망에서 호출하면 403 INTERNAL_NETWORK_ONLY
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface InternalNetworkOnly {
}
