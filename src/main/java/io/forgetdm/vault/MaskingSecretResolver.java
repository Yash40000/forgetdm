package io.forgetdm.vault;

import io.forgetdm.config.ForgeProps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves the effective masking key/salt at startup: from HashiCorp Vault when configured
 * (RFP §3.2.3), otherwise from the local {@code forgetdm.masking-secret} property.
 *
 * <p>If Vault is enabled but unreachable: {@code failClosed=true} aborts startup (prod-safe —
 * never silently mask with the wrong key); otherwise it logs and falls back to the local secret.
 * The resolved secret value is never logged.
 */
@Component
public class MaskingSecretResolver {

    private static final Logger log = LoggerFactory.getLogger(MaskingSecretResolver.class);

    public enum Source { VAULT, LOCAL, LOCAL_FALLBACK }

    public record Resolved(String secret, Source source, String detail) {}

    private final ForgeProps props;
    private final VaultClient vault;

    public MaskingSecretResolver(ForgeProps props, VaultClient vault) {
        this.props = props;
        this.vault = vault;
    }

    public Resolved resolve() {
        ForgeProps.Vault v = props.getVault();
        if (v == null || !v.isEnabled()) {
            return new Resolved(props.getMaskingSecret(), Source.LOCAL, "forgetdm.masking-secret property");
        }
        try {
            String secret = vault.readMaskingSecret();
            if (secret == null || secret.isBlank()) {
                throw new IllegalStateException("Vault field '" + v.getField() + "' is empty");
            }
            String detail = "HashiCorp Vault " + v.getKvMount() + "/" + v.getPath() + "#" + v.getField();
            log.info("Masking key sourced from {}", detail);
            return new Resolved(secret, Source.VAULT, detail);
        } catch (RuntimeException e) {
            if (v.isFailClosed()) {
                throw new IllegalStateException(
                        "forgetdm.vault.enabled=true with fail-closed, but the masking key could not be read from Vault: "
                                + e.getMessage(), e);
            }
            log.warn("Vault masking key unavailable ({}); falling back to local secret. "
                    + "Set forgetdm.vault.fail-closed=true to make this fatal.", e.getMessage());
            return new Resolved(props.getMaskingSecret(), Source.LOCAL_FALLBACK,
                    "Vault unreachable (" + e.getMessage() + "); using local secret");
        }
    }
}
