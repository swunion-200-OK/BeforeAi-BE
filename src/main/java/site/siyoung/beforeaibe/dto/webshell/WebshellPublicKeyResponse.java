package site.siyoung.beforeaibe.dto.webshell;

public record WebshellPublicKeyResponse(
        KeyInfo encryption,
        KeyInfo signing
) {

    public record KeyInfo(String algorithm, String publicKey, String fingerprint) {
    }
}
