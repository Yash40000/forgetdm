# Temenos T24 multi-value / sub-value masking (RFP §3.2.1)

Temenos (jBASE/Pick lineage) stores nested variable-length arrays inside a single physical column,
delimited by control marks:

| Mark | Byte | Latin-1 char | Role |
|---|---|---|---|
| FM  | 0xFE (254) | þ | Field mark |
| VM  | 0xFD (253) | ý | Value mark (multi-value) |
| SVM | 0xFC (252) | ü | Sub-value mark |
| TM  | 0xFB (251) | û | Text mark (deepest) |

## What was built

`io.forgetdm.core.temenos.TemenosCodec` parses this structure and, critically, provides
`mapLeaves(value, fn)` which rebuilds the string with the **exact same marks in the exact same
positions**, transforming only the leaf text between them. Empty segments are preserved, so the
value count and sub-value count never shift and adjacent sub-values never move (the RFP's "Structure
Preservation" requirement).

It is wired into `MaskingEngine.mask(...)`: when a value contains Temenos marks, the engine masks
**each sub-value in place** and re-injects the delimiters. This makes **every** mask function
(FORMAT_PRESERVE, FIRST_NAME, CREDIT_CARD, TOKENIZE, …) Temenos-aware for free — no new function or
rule flag needed. A value with no marks is treated as a single leaf (unchanged behaviour).

Because masking is deterministic per sub-value, the same physical sub-value masks to the same output
across positions, tables and runs — referential integrity is preserved at the sub-value level.

### Example

```
NAME field  "John" ýVM "Alexander"
  → FIRST_NAME mask →  "Mohammed" ýVM "Ahmed"
```

The VM mark stays in place; the field still has exactly two values.

## Encoding note

The marks are the raw bytes 0xFE/0xFD/0xFC/0xFB. Read through JDBC with an ISO-8859-1 (Latin-1)
column encoding they surface as `þ ý ü û`, which is what the codec matches. Sources that expose
Temenos data under a different charset should be registered so the column decodes to Latin-1 (or the
mark constants adjusted); this is the standard T24 extract convention.

## Tests

- `TemenosCodecTest` — mark detection, mark/position/empty-segment preservation, FM/VM/SVM
  hierarchy, parse↔format round-trip, value counts.
- `TemenosMaskingTest` — engine masks each multi-value/sub-value in place, preserves structure and
  field count, is deterministic per sub-value, and leaves plain (unmarked) values on the normal path.

Run: `mvn test -Dtest=TemenosCodecTest,TemenosMaskingTest`.

## Not yet covered

- **Addressed masking** (mask only `NAME.2`, not `NAME.1`) — the codec exposes `parse`/`format` and
  positional structure to support it, but the rule model doesn't yet carry a value/sub-value index.
- **Charset auto-detection** per source; today Latin-1 decoding is assumed for marked columns.
