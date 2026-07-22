# Encrypted TimeFlow staging

ForgeTDM can encrypt every compressed TimeFlow chunk before it reaches the staging pool. The
envelope uses AES-256-GCM with a fresh 96-bit nonce per write. The chunk's SHA-256 content address is
authenticated as additional data, so copied, renamed, or modified ciphertext fails authentication.

## Enable it

Set this environment variable before starting ForgeTDM:

```powershell
$env:FORGETDM_STAGING_ENCRYPT = "true"
```

The encryption key is domain-separated and derived from the effective ForgeTDM masking secret. If
HashiCorp Vault is enabled, that secret comes from Vault; otherwise it comes from
`FORGETDM_MASKING_SECRET`. Production deployments should enable Vault with fail-closed behavior.

The pool status endpoint (`GET /api/virtualization/pool`) and the Virtualization UI report
`encryptedAtRest: true` / `AES-256-GCM`, so operators can verify the effective state.

## Existing snapshots

- Encrypted mode can read legacy gzip chunks without a migration outage.
- When existing content is written again, its legacy chunk is atomically upgraded to the encrypted
  envelope without changing its hash or manifest reference.
- Encrypted chunks require encrypted mode and the original key. Turning encryption off does not
  silently expose or misread them.

## Key lifecycle

The current envelope identifies a key mismatch and fails closed. Do not rotate the Vault masking
secret while snapshots encrypted by the old key remain in the pool. A production rotation must
either retain the old key during a controlled re-encryption or expire those snapshots first.

## Security boundary

This protects extracted source data at rest in the TimeFlow staging pool. Data is plaintext briefly
inside the ForgeTDM process while it is compressed, masked, or materialized, so host hardening,
process access controls, TLS, and target-database encryption remain required.

Focused verification:

```powershell
mvn -Dtest=ChunkStoreEncryptionTest test
```
