package site.siyoung.beforeaibe.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.siyoung.beforeaibe.dto.webshell.WebshellPublicKeyResponse;
import site.siyoung.beforeaibe.security.InternalNetworkOnly;
import site.siyoung.beforeaibe.service.WebshellPublicKeyService;

@RestController
@RequestMapping("/api/v1/webshell")
@RequiredArgsConstructor
public class WebshellController {

    private final WebshellPublicKeyService webshellPublicKeyService;

    // 공개키를 바꿔치기할 수 없도록 내부망에서만 내려 줌
    @InternalNetworkOnly
    @GetMapping("/public-key")
    public WebshellPublicKeyResponse publicKey() {
        return webshellPublicKeyService.getPublicKey();
    }
}
