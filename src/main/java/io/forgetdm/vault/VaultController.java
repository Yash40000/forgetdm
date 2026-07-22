package io.forgetdm.vault;

import io.forgetdm.config.ForgeProps;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin visibility into the masking-key source. Under {@code /api/security/**}, so it is gated by
 * {@code security.admin} in AccessControlFilter. Never returns the secret or the Vault token.
 */
@RestController
@RequestMapping("/api/security/vault")
public class VaultController {

    private final ForgeProps props;
    private final VaultClient vault;
    private final MaskingSecretResolver resolver;

    public VaultController(ForgeProps props, VaultClient vault, MaskingSecretResolver resolver) {
        this.props = props;
        this.vault = vault;
        this.resolver = resolver;
    }

    /** Where the masking key comes from, and (if Vault) whether the server is reachable/unsealed. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        ForgeProps.Vault cfg = props.getVault();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", cfg.isEnabled());
        out.put("address", cfg.isEnabled() ? cfg.getAddress() : null);
        out.put("kvMount", cfg.getKvMount());
        out.put("kvVersion", cfg.getKvVersion());
        out.put("path", cfg.getPath());
        out.put("field", cfg.getField());
        out.put("failClosed", cfg.isFailClosed());
        out.put("tokenConfigured", cfg.getToken() != null && !cfg.getToken().isBlank());

        if (cfg.isEnabled()) {
            VaultClient.Health h = vault.health();
            Map<String, Object> health = new LinkedHashMap<>();
            health.put("reachable", h.reachable());
            health.put("sealed", h.sealed());
            health.put("initialized", h.initialized());
            health.put("version", h.version());
            if (h.error() != null) health.put("error", h.error());
            out.put("health", health);
        }

        // Report the effective source without ever exposing the secret value.
        MaskingSecretResolver.Resolved r = resolver.resolve();
        out.put("maskingKeySource", r.source().name());
        out.put("detail", r.detail());
        return out;
    }
}
