---
name: proto-compat-audit
description: Audit Java proto definitions and encoding metadata field tags against the Rust spiraldb/vortex reference implementation. Run whenever upstream vortex evolves. No formal spec exists — Rust source is ground truth.
---

## Overview

This skill audits three things:

1. `dtype.proto` + `scalar.proto` field-by-field against upstream `spiraldb/vortex`
2. Every encoding metadata message in `encodings.proto` against the Rust `from_proto`/`to_proto` impls
3. Integration test assertions — must check actual decoded values, not just row counts

Proto3 silently returns defaults (0 / "" / false) on tag mismatch. Wrong tag = wrong decode = silent corruption.
Rust reference is at `https://github.com/spiraldb/vortex`. Fetch via `gh api repos/spiraldb/vortex/contents/<path>`.

---

## Step 1 — Sync dtype.proto and scalar.proto

Fetch upstream versions:

```
gh api repos/spiraldb/vortex/contents/vortex-proto/proto/dtype.proto --jq '.content' | base64 -d
gh api repos/spiraldb/vortex/contents/vortex-proto/proto/scalar.proto --jq '.content' | base64 -d
```

Diff against local `core/src/main/proto/dtype.proto` and `core/src/main/proto/scalar.proto`.

Check for:
- New fields added upstream (new field numbers we don't handle)
- Removed or renumbered fields (tag mismatch → silent zero)
- New enum values in `PType` or `DType.oneof`
- Changes to `ScalarValue.oneof`

If diverged: update local `.proto`, regenerate (`./mvnw generate-sources -pl core -P regenerate-sources`), update any Java code that reads the affected fields, add a test.

---

## Step 2 — Audit encoding metadata field tags

`encodings.proto` has no upstream `.proto` counterpart. Field numbers were assigned manually.
Rust source of truth: `vortex-encodings/<encoding>/src/` — look for `MetadataAny`, `from_proto`, `to_proto`, or `Metadata` structs with protobuf derives.

Upstream proto path for reference (may not exist for all): `gh api repos/spiraldb/vortex/contents/vortex-encodings/<name>/src/`.

For each message below, verify field tag numbers match what Rust serializes. Silent corruption risk ranked high→low:

| Message | Key fields | Corruption if wrong |
|---|---|---|
| `RLEMetadata` | `values_len`(1), `indices_len`(2), `indices_ptype`(3) | empty/wrong-typed decode |
| `RunEndMetadata` | `ends_ptype`(1), `num_runs`(2), `offset`(3) | no data |
| `ALPMetadata` | `exp_e`(1), `exp_f`(2) | all zeroes |
| `BitPackedMetadata` | `bit_width`(1), `offset`(2) | garbage unpack |
| `DictMetadata` | `values_len`(1), `codes_ptype`(2) | empty dict |
| `ALPRDMetadata` | `right_bit_width`(1), `dict_len`(2), `dict`(3), `left_parts_ptype`(4) | wrong reconstruction |
| `PcoMetadata` | `header`(1), `chunks`(2) | full decode failure |
| `SparseMetadata` | `patches`(1) | missing patches |
| `DateTimePartsMetadata` | `days_ptype`(1), `seconds_ptype`(2), `subseconds_ptype`(3) | wrong datetime |
| `DeltaMetadata` | `deltas_len`(1), `offset`(2) | wrong deltas |
| `ZstdMetadata` | `dictionary_size`(1), `frames`(2) | wrong sizes |
| `DictMetadata` | `is_nullable_codes`(3), `all_values_referenced`(4) | wrong nullability |
| `VarBinMetadata` | `offsets_ptype`(1) | wrong offsets |
| `FSSTMetadata` | `uncompressed_lengths_ptype`(1), `codes_offsets_ptype`(2) | wrong string decode |
| `SequenceMetadata` | `base`(1), `multiplier`(2) | wrong sequence |

For any mismatch: fix field number in `encodings.proto`, regenerate, update decoder, add regression test with a pinned Rust-produced fixture that exercises the field.

---

## Step 3 — Strengthen integration test assertions

File: `integration/src/test/java/.../RustWritesJavaReadsIntegrationTest.java`

### Tests that only check rowCount — upgrade to value assertions:

**`jniWriter_nullableColumn_decodesWithoutError`** — currently checks rowCount + dtype only.
Add: verify non-null positions have correct values (`i % 5 != 0` → value == i), verify null positions return appropriate null indicator.

**`jniWriter_javaReader_fewUniqueF64Values`** — sum check is weak (values that sum correctly can still be wrong).
Add: assert first N decoded values match exactly `[1.1, 2.2, 3.3, 1.1, 2.2, ...]`.

### S3 `javaDecodeMatchesJni` tests — add pinned golden values:

Tests at `s3_pcoVortex_javaDecodeMatchesJni`, `s3_tpchLineitem_javaDecodeMatchesJni`, `s3_tpchOrders_javaDecodeMatchesJni`, `s3_clickbenchHits5k_javaDecodeMatchesJni` compare Java vs JNI — both could be wrong together.

Add at least one **pinned golden value per test**: a specific row index and its expected value, derived independently (e.g., from the Rust CLI `vortex inspect` or by reading the file with the Rust binary separately). This anchors the test to ground truth, not just Java-JNI consistency.

---

## Step 4 — Unit-level metadata round-trip tests

For each encoding that uses protobuf metadata, add or extend the unit test class with a `Metadata` nested class:

```java
@Nested
class Metadata {
    @Test
    void roundTrips() {
        // Given — encode known data
        EncodeResult result = new FooEncoding().encode(dtype, data);
        // When — parse metadata bytes
        FooMetadataProto meta = FooMetadataProto.parseFrom(result.node().metadata());
        // Then — assert every non-default field
        assertThat(meta.getBitWidth()).isEqualTo(expectedBitWidth);
        assertThat(meta.getOffset()).isEqualTo(expectedOffset);
    }
}
```

Encodings requiring this test (add if missing): `AlpEncoding`, `AlpRdEncoding`, `BitpackedEncoding`, `DateTimePartsEncoding`, `DeltaEncoding`, `DictEncoding`, `FsstEncoding`, `PcoEncoding`, `RleEncoding`, `RunEndEncoding`, `SparseEncoding`, `VarBinEncoding`, `ZstdEncoding`.

---

## Completion criteria

- [ ] `dtype.proto` and `scalar.proto` match upstream (or divergences are documented with a reason)
- [ ] Every encoding metadata message field tag verified against Rust source
- [ ] No integration test asserts only `rowCount` — all assert actual values
- [ ] Every metadata-using encoding has a unit-level metadata round-trip test
- [ ] `./mvnw verify` passes
