# HashiCorp Vault-sourced masking key (RFP §3.2.3)

The deterministic masking key/salt can be pulled directly from **HashiCorp Vault** instead of the
local `forgetdm.masking-secret` property, centralising key custody. HMAC-SHA256 determinism is
unchanged — only the *source* of the key moves to Vault.

**Off by default** (`forgetdm.vault.enabled=false`) → the masking key still comes from the local
property, so enabling this is opt-in and existing behaviour is untouched.

## How it works

At startup, `MaskingSecretResolver` resolves the effective key:

- Vault disabled → local `forgetdm.masking-secret` (source `LOCAL`).
- Vault enabled + reachable → read `field` from `mount/path` via the KV engine (source `VAULT`).
- Vault enabled + unreachable → `fail-closed=true` aborts startup (prod-safe); otherwise falls back
  to the local secret (source `LOCAL_FALLBACK`) with a warning.

The resolved secret is never logged; the Vault token is never logged or returned by any API.

## Configuration

Environment variables (Spring relaxed binding) or `application.yml`:

```
FORGETDM_VAULT_ENABLED=true
FORGETDM_VAULT_ADDRESS=http://127.0.0.1:8200
FORGETDM_VAULT_TOKEN=<vault-token>
FORGETDM_VAULT_KV_MOUNT=secret          # KV mount (default: secret)
FORGETDM_VAULT_KV_VERSION=2             # 1 or 2 (default: 2)
FORGETDM_VAULT_PATH=forgetdm/masking    # secret path under the mount
FORGETDM_VAULT_FIELD=maskingSecret      # key holding the salt
FORGETDM_VAULT_FAIL_CLOSED=true         # recommended in production
FORGETDM_VAULT_NAMESPACE=               # Vault Enterprise namespace (optional)
```

## Rotation note

Deterministic masking means the same input + same key → same output, which is what preserves
referential integrity across tables/systems/runs. **Rotating the salt re-keys masking** — data
masked before and after a rotation will not match. In production, pin a KV version and only rotate
as part of a planned full re-mask. Vault gives you the custody, rotation, and audit of the key; the
determinism contract is the operator's to manage.

## Verify (local Vault dev server)

```bash
# 1. start a throwaway Vault
vault server -dev            # prints a Root Token and unseal status
export VAULT_ADDR=http://127.0.0.1:8200
export VAULT_TOKEN=<root-token-from-output>

# 2. write the masking secret (KV v2, default 'secret' mount)
vault kv put secret/forgetdm/masking maskingSecret='super-secret-salt-value'

# 3. point ForgeTDM at it and restart
setx FORGETDM_VAULT_ENABLED true
setx FORGETDM_VAULT_ADDRESS http://127.0.0.1:8200
setx FORGETDM_VAULT_TOKEN <root-token>
#   (restart the backend so the env is picked up)
```

Then, as an admin:

```
GET /api/security/vault/status
→ { "enabled": true, "health": { "reachable": true, "sealed": false, ... },
    "maskingKeySource": "VAULT",
    "detail": "HashiCorp Vault secret/forgetdm/masking#maskingSecret" }
```

A masking run now derives from the Vault-held salt. Stop Vault and (with `fail-closed=false`) the
status flips to `LOCAL_FALLBACK`; with `fail-closed=true` the app refuses to start.
