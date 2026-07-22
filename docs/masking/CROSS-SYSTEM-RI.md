# Cross-system deterministic referential integrity (RFP §3.2.3)

The same source value must scramble to the same target value across every platform, so joins that
span Temenos, TSYS PRIME, FIS CORTEX and FIS AG Quantum still resolve after masking.

## How ForgeTDM guarantees it

Masking is a **pure deterministic function** of `(value, field-salt, project-key)`:

```
masked = f(value, salt, key)
```

- `key` is the project masking secret — now sourced from **HashiCorp Vault** (§3.2.3 key custody).
- `salt` names the logical field (e.g. `customer.number`).
- No shared cross-reference table is needed: identical inputs always produce identical outputs,
  on any engine, in any table, across runs.

So the referential-integrity rule is simply: **configure the same field-salt for the same logical
field in every connector's masking policy.** Then `CUSTOMER.NUMBER` masks the same in Temenos, PRIME,
CORTEX and AG Quantum automatically.

## Live proof (2026-07-21, HMAC/FPE engine via `/api/policies/preview`)

Customer number `554321`, function `FORMAT_PRESERVE`, salt `customer.number`, masked as it would be
in each of the four systems:

| System | Masked CUSTOMER.NUMBER |
|---|---|
| Temenos T24 | `435288` |
| TSYS PRIME | `435288` |
| FIS CORTEX | `435288` |
| FIS AG Quantum | `435288` |

- **Identical across all four systems** ✅ (referential integrity holds).
- **Format-preserving** ✅ 6 digits → 6 digits (matches the RFP's `554321 → 998112` shape).
- Different value `554322` → `499272` (distinct; no collision).
- Different field-salt `account.number` → `108622` (salt-scoped — different logical fields don't
  collapse together).

This is the same masking engine the provisioning pipeline uses for every target, so what preview
shows is exactly what lands in each system.

## Notes

- The engine also offers `TOKENIZE` (irreversible HMAC-SHA256) for non-format-preserving tokens, and
  `CREDIT_CARD` (BIN + Luhn) for card RI; the same determinism/RI property applies.
- Rotating the Vault key re-keys all masking (breaks cross-run RI by design) — rotate only as part of
  a planned full re-mask. See [VAULT-KEY.md](VAULT-KEY.md).
