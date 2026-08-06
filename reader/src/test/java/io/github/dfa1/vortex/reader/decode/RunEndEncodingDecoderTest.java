package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.proto.ProtoPType;
import io.github.dfa1.vortex.core.proto.ProtoRunEndMetadata;
import io.github.dfa1.vortex.core.proto.ProtoVarBinMetadata;
import io.github.dfa1.vortex.core.testing.TestSegments;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.array.VarBinRunEndArray;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunEndEncodingDecoderTest {

    private static final RunEndEncodingDecoder SUT = new RunEndEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(
            SUT, new PrimitiveEncodingDecoder(), new BoolEncodingDecoder(), new VarBinEncodingDecoder(),
            new NullEncodingDecoder());

    @Test
    void encodingId_isVortexRunEnd() {
        // Given / When / Then
        assertThat(SUT.encodingId()).isEqualTo(EncodingId.VORTEX_RUNEND);
    }

    /// A nullable run-value must null every ROW in its run, not expand to a filler value.
    /// Mirrors uci-online-retail `customerid` (u16?): a null run previously emitted the FoR
    /// base (#225). Row validity is the run-end bool over the same ends (Rust
    /// `ValidityVTable<RunEnd>`), so valid runs stay correct while the null run's rows go null.
    @Test
    void nullRun_nullsAllRowsInRunAndKeepsValidRunsCorrect() {
        // Given — ends [2,3,5] over values [10,20,30] with run 1 (value 20) null.
        // Rows 0..1 -> 10 (valid), row 2 -> 20 (null run), rows 3..4 -> 30 (valid).
        MemorySegment[] segs = {
                u8Bytes(2, 3, 5),                    // run ends
                TestSegments.leInts(10, 20, 30),     // run values
                boolBitmap(true, false, true)        // run-value validity: run 1 null
        };
        ArrayNode endsNode = primitiveNode(0);
        ArrayNode valuesNode = maskedPrimitiveNode(1, 2);

        // When
        Array result = decode(nullableI32(), PType.U8, 3, 0, 5, segs, endsNode, valuesNode);

        // Then
        MaskedArray masked = assertMasked(result);
        assertValidity(masked, true, true, false, true, true);
        assertIntValues(masked, 10, 10, 20, 30, 30);
    }

    /// No-regression: a non-nullable values child must NOT produce a [MaskedArray].
    @Test
    void nonNullableValues_returnsPlainArray() {
        // Given — ends [2,4] over values [10,20], no validity child
        MemorySegment[] segs = {
                u8Bytes(2, 4),
                TestSegments.leInts(10, 20)
        };
        ArrayNode endsNode = primitiveNode(0);
        ArrayNode valuesNode = primitiveNode(1);

        // When
        Array result = decode(DType.I32, PType.U8, 2, 0, 4, segs, endsNode, valuesNode);

        // Then
        assertThat(result).isInstanceOf(IntArray.class).isNotInstanceOf(MaskedArray.class);
        IntArray ints = (IntArray) result;
        assertThat(ints.getInt(0)).isEqualTo(10);
        assertThat(ints.getInt(3)).isEqualTo(20);
    }

    /// A crafted zero num_runs previously decoded "successfully" into a lazy array backed by
    /// an empty ends/values child, then threw a raw exception (AIOOBE, or ArithmeticException
    /// via `% elementCount`) on the first row read instead of failing here as a
    /// [VortexException] (ADR 0003).
    @Test
    void zeroRuns_withNonEmptyRowCount_throwsVortexException() {
        // Given — no run-ends/values segments needed: the check short-circuits before either
        // child is decoded
        ArrayNode endsNode = primitiveNode(0);
        ArrayNode valuesNode = primitiveNode(0);

        // When / Then
        assertThatThrownBy(() -> decode(DType.I32, PType.U8, 0, 0, 5, new MemorySegment[0], endsNode, valuesNode))
                .isInstanceOf(io.github.dfa1.vortex.core.error.VortexException.class)
                .hasMessageContaining("zero runs");
    }

    @Test
    void negativeNumRuns_throwsVortexException() {
        // Given — num_runs decodes to -1 (a valid varint on the wire)
        ArrayNode endsNode = primitiveNode(0);
        ArrayNode valuesNode = primitiveNode(0);

        // When / Then
        assertThatThrownBy(() -> decode(DType.I32, PType.U8, -1, 0, 5, new MemorySegment[0], endsNode, valuesNode))
                .isInstanceOf(io.github.dfa1.vortex.core.error.VortexException.class)
                .hasMessageContaining("negative num_runs");
    }

    /// `ends` must be strictly increasing (spec: `encoding-format/dict-runend-sparse.md`
    /// §RunEnd — a *writer* requirement the reference reader doesn't itself enforce, so "a
    /// conformant reader SHOULD validate ... itself"). Previously undetected: the binary search
    /// stays in-bounds regardless of ordering, so this silently resolved rows against the wrong
    /// run instead of failing.
    @Test
    void nonMonotonicRunEnds_throwsVortexException() {
        // Given — ends [5, 2, 8] are not strictly increasing
        MemorySegment[] segs = {
                u8Bytes(5, 2, 8),
                TestSegments.leInts(10, 20, 30)
        };
        ArrayNode endsNode = primitiveNode(0);
        ArrayNode valuesNode = primitiveNode(1);

        // When / Then
        assertThatThrownBy(() -> decode(DType.I32, PType.U8, 3, 0, 8, segs, endsNode, valuesNode))
                .isInstanceOf(io.github.dfa1.vortex.core.error.VortexException.class)
                .hasMessageContaining("strictly increasing");
    }

    /// The last run-end must cover the full requested window (`ends[numRuns-1] >= offset + n`).
    /// Previously undetected: the binary search saturates at the last run and silently repeats
    /// its value for every row past where the ends actually stop covering.
    @Test
    void lastRunEndBelowRowCount_throwsVortexException() {
        // Given — ends [2, 3] cover only 3 rows but n=5 is requested
        MemorySegment[] segs = {
                u8Bytes(2, 3),
                TestSegments.leInts(10, 20)
        };
        ArrayNode endsNode = primitiveNode(0);
        ArrayNode valuesNode = primitiveNode(1);

        // When / Then
        assertThatThrownBy(() -> decode(DType.I32, PType.U8, 2, 0, 5, segs, endsNode, valuesNode))
                .isInstanceOf(io.github.dfa1.vortex.core.error.VortexException.class)
                .hasMessageContaining("does not cover");
    }

    /// A slice's `ends[0]` must be at least `offset` (spec: "when `offset != 0`, `ends[0] >=
    /// offset`"). Below that, row 0 of the window would resolve to a run that ends before the
    /// window even starts.
    @Test
    void firstRunEndBelowOffset_throwsVortexException() {
        // Given — offset=10 but ends[0]=5 < offset
        MemorySegment[] segs = {
                u8Bytes(5, 20),
                TestSegments.leInts(10, 20)
        };
        ArrayNode endsNode = primitiveNode(0);
        ArrayNode valuesNode = primitiveNode(1);

        // When / Then
        assertThatThrownBy(() -> decode(DType.I32, PType.U8, 2, 10, 5, segs, endsNode, valuesNode))
                .isInstanceOf(io.github.dfa1.vortex.core.error.VortexException.class)
                .hasMessageContaining("< offset");
    }

    /// A sliced window whose trailing run legitimately extends past `offset + n` must decode
    /// normally — the spec's coverage requirement is `>=`, not `==`; only a run boundary that
    /// falls short of the window is invalid.
    @Test
    void trailingRunPastWindow_decodesNormally() {
        // Given — ends [2, 5, 10] over values [1, 2, 3]; window offset=2, n=5 (rows 2..7), the
        // spec's own worked example
        MemorySegment[] segs = {
                u8Bytes(2, 5, 10),
                TestSegments.leInts(1, 2, 3)
        };
        ArrayNode endsNode = primitiveNode(0);
        ArrayNode valuesNode = primitiveNode(1);

        // When
        Array result = decode(DType.I32, PType.U8, 3, 2, 5, segs, endsNode, valuesNode);

        // Then
        IntArray ints = (IntArray) result;
        assertThat(ints.getInt(0)).isEqualTo(2);
        assertThat(ints.getInt(1)).isEqualTo(2);
        assertThat(ints.getInt(2)).isEqualTo(2);
        assertThat(ints.getInt(3)).isEqualTo(3);
        assertThat(ints.getInt(4)).isEqualTo(3);
    }

    /// A Utf8 runend column must decode to the lazy [VarBinRunEndArray], not to an expanded
    /// [io.github.dfa1.vortex.reader.array.VarBinOffsetArray]. The old eager path allocated and
    /// copied `sum(runLength * strLen)` bytes plus an `(n + 1) * 4` offsets table — for the one
    /// encoding whose whole purpose is that those rows repeat (#334). Asserting the concrete
    /// type is the point: correct values alone would also pass on the eager path.
    @Test
    void utf8Values_decodeLazilyWithoutExpanding() {
        // Given — ends [2,3,5] over values ["aa","b","cccc"]; rows 0..1 "aa", 2 "b", 3..4 "cccc"
        MemorySegment[] segs = {
                u8Bytes(2, 3, 5),                        // run ends
                TestSegments.leInts(0, 2, 3, 7),         // value offsets (4 entries for 3 runs)
                MemorySegment.ofArray("aabcccc".getBytes(StandardCharsets.UTF_8))
        };
        ArrayNode endsNode = primitiveNode(0);
        ArrayNode valuesNode = varBinNode(1, 2);

        // When
        Array result = decode(DType.UTF8, PType.U8, 3, 0, 5, segs, endsNode, valuesNode);

        // Then
        assertThat(result).isInstanceOf(VarBinRunEndArray.class);
        VarBinArray strings = (VarBinArray) result;
        assertThat(strings.length()).isEqualTo(5L);
        assertThat(strings.getString(0)).isEqualTo("aa");
        assertThat(strings.getString(1)).isEqualTo("aa");
        assertThat(strings.getString(2)).isEqualTo("b");
        assertThat(strings.getString(3)).isEqualTo("cccc");
        assertThat(strings.getString(4)).isEqualTo("cccc");
    }

    /// The values child comes from an untrusted file and need not decode to a `VarBinArray`
    /// even when the dtype is Utf8: an entirely-null run column arrives as `vortex.null` and
    /// decodes to [io.github.dfa1.vortex.reader.array.NullArray] (the #269 shape). Casting it
    /// straight to `VarBinArray` (as the eager path did) made that a raw ClassCastException,
    /// which ADR 0003 forbids.
    @Test
    void utf8WithNonVarBinValuesChild_throwsVortexException() {
        // Given — dtype says Utf8 but the values child is vortex.null
        MemorySegment[] segs = {u8Bytes(2, 5)};
        ArrayNode endsNode = primitiveNode(0);
        ArrayNode valuesNode = new ArrayNode(EncodingId.VORTEX_NULL, null, new ArrayNode[0], new int[]{});

        // When / Then
        assertThatThrownBy(() -> decode(DType.UTF8, PType.U8, 2, 0, 5, segs, endsNode, valuesNode))
                .isInstanceOf(io.github.dfa1.vortex.core.error.VortexException.class)
                .hasMessageContaining("expected a VarBin values child");
    }

    private static Array decode(DType dtype, PType endsPtype, long numRuns, long offset, long n,
            MemorySegment[] segs, ArrayNode endsNode, ArrayNode valuesNode) {
        MemorySegment meta = MemorySegment.ofArray(
                new ProtoRunEndMetadata(ProtoPType.valueOf(endsPtype.name()), numRuns, offset).encode());
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_RUNEND, meta,
                new ArrayNode[]{endsNode, valuesNode}, new int[]{});
        DecodeContext ctx = new DecodeContext(node, dtype, n, segs, REGISTRY, Arena.ofAuto());
        return SUT.decode(ctx);
    }

    private static DType nullableI32() {
        return new DType.Primitive(PType.I32, true);
    }

    private static MaskedArray assertMasked(Array result) {
        assertThat(result).isInstanceOf(MaskedArray.class);
        return (MaskedArray) result;
    }

    private static void assertValidity(MaskedArray masked, boolean... expected) {
        for (int i = 0; i < expected.length; i++) {
            assertThat(masked.isValid(i)).as("valid row %d", i).isEqualTo(expected[i]);
        }
    }

    private static void assertIntValues(MaskedArray masked, int... expected) {
        IntArray inner = (IntArray) masked.inner();
        for (int i = 0; i < expected.length; i++) {
            assertThat(inner.getInt(i)).as("row %d", i).isEqualTo(expected[i]);
        }
    }

    private static ArrayNode primitiveNode(int bufferIndex) {
        return new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{bufferIndex});
    }

    /// A `vortex.varbin` node: one primitive child holding the I32 offsets, one data buffer.
    private static ArrayNode varBinNode(int offsetsBufIndex, int bytesBufIndex) {
        MemorySegment meta = MemorySegment.ofArray(
                new ProtoVarBinMetadata(ProtoPType.valueOf(PType.I32.name())).encode());
        return new ArrayNode(EncodingId.VORTEX_VARBIN, meta,
                new ArrayNode[]{primitiveNode(offsetsBufIndex)}, new int[]{bytesBufIndex});
    }

    /// A primitive node whose single `vortex.bool` validity child makes
    /// [PrimitiveEncodingDecoder] surface it as a [MaskedArray].
    private static ArrayNode maskedPrimitiveNode(int dataBufIndex, int validityBufIndex) {
        ArrayNode validity = new ArrayNode(EncodingId.VORTEX_BOOL, null, new ArrayNode[0],
                new int[]{validityBufIndex});
        return new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[]{validity},
                new int[]{dataBufIndex});
    }

    private static MemorySegment u8Bytes(int... values) {
        byte[] a = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            a[i] = (byte) values[i];
        }
        return MemorySegment.ofArray(a);
    }

    private static MemorySegment boolBitmap(boolean... valid) {
        byte[] bytes = new byte[(valid.length + 7) / 8];
        for (int i = 0; i < valid.length; i++) {
            if (valid[i]) {
                bytes[i >>> 3] |= (byte) (1 << (i & 7));
            }
        }
        return MemorySegment.ofArray(bytes);
    }
}
