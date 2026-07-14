# Masking lookups

ForgeTDM supports two Optim-style lookup functions from Masking Studio, masking policies, DataScope, and mainframe field maps.

## Direct lookup

`DIRECT_LOOKUP` performs an exact source-to-replacement match. It fails when a source key is absent unless the rule explicitly selects another behavior.

- Inline mappings: `CHK=>EVERYDAY|SAV=>RESERVE`
- PostgreSQL catalog: `@lookup:direct:demo.account-tier`
- Options example: `NOT_FOUND=ERROR;TRIM=BOTH;CASE=UPPER;CACHE=ON`
- Composite key example: `SOURCE=region,tier;JOIN=~`
- Not-found actions: `ERROR`, `PRESERVE`, `NULL`, `REDACT`, or `DEFAULT` with `DEFAULT=value`

## Hash lookup

`HASH_LOOKUP` hashes the source into a contiguous `1..N` lookup row. The same source, lookup, secret, and seed produce the same replacement across tables and runs.

- Inline rows: `1=>Olivia|2=>Liam|3=>Ava`
- PostgreSQL catalog: `@lookup:hash:demo.us-first-names`
- Options example: `SEED=7;TRIM=BOTH;CASE=UPPER;CACHE=ON`
- Use `NOCACHE` or `CACHE=OFF` only when each call must read the latest catalog values.
- Optional `SOURCE=column` or `SOURCE=column1,column2;JOIN=~` hashes other fields from the current row.

### Multiple-column destination

A single hashed row can populate several destination columns (Optim's `dest=(...)`/`values=(...)` form). Give each lookup row several replacement columns separated by `VCOLSEP` (default `~`), then put a `HASH_LOOKUP` rule on each destination column that selects its slice with `VALUE=n` (1-based):

- Multi-column rows: `1=>Olivia~Johnson|2=>Liam~Smith|3=>Ava~Brown`
- First-name column rule options: `SOURCE=first_name,last_name;VALUE=1`
- Last-name column rule options: `SOURCE=first_name,last_name;VALUE=2`
- Custom separator: add `VCOLSEP=#` and use `1=>Olivia#Johnson`

All sibling rules that share the same `SOURCE`, `SEED`, and lookup rows hash to the **same** row, so the generated first/last name (and any other columns) come from one coherent record. Reserved rows (`-1`/`-2`/`-3`) may also carry multiple columns. `VALUE` absent — or `VALUE=1` on a single-value row — is the original single-column behavior, so existing lookups are unaffected. `VALUE=n` beyond the row's column count fails closed.

Optim-compatible reserved keys are supported:

| Key | Source condition |
| --- | --- |
| `-1` | `NULL` |
| `-2` | Spaces only |
| `-3` | Zero-length string |

When a reserved row is absent, specify `NULL=PRESERVE`, `SPACES=PRESERVE`, or `ZERO_LEN=PRESERVE` if passthrough is intentional. Otherwise the function fails closed.

## Local demo data

Migration `V53__masking_lookup_catalog.sql` creates `masking_lookup_values` and loads:

- `@lookup:hash:demo.us-first-names`: 20 US replacement names plus reserved rows.
- `@lookup:direct:demo.account-tier`: account-code replacements.

The Masking Studio **Sample hash lookup** action loads the PostgreSQL-backed example automatically.
