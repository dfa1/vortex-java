package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.proto.ProtoPType;
import io.github.dfa1.vortex.core.proto.ProtoRunEndMetadata;
import io.github.dfa1.vortex.core.testing.TestSegments;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunEndEncodingDecoderTest {

    private static final RunEndEncodingDecoder SUT = new RunEndEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(
            SUT, new PrimitiveEncodingDecoder(), new BoolEncodingDecoder());

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

    /// Non-monotonic run-ends don't crash: [RunEndArrays#findRun] narrows a binary-search
    /// range purely from `lo`/`hi`, never from the comparison outcome's validity, so every
    /// probed index stays within `[0, numRuns)` regardless of ordering. Locks in behavior
    /// the binary search already guarantees rather than adding a redundant guard.
    @Test
    void nonMonotonicRunEnds_decodesWithoutCrashing() {
        // Given — ends [5, 2, 8] are not non-decreasing
        MemorySegment[] segs = {
                u8Bytes(5, 2, 8),
                TestSegments.leInts(10, 20, 30)
        };
        ArrayNode endsNode = primitiveNode(0);
        ArrayNode valuesNode = primitiveNode(1);

        // When
        Array result = decode(DType.I32, PType.U8, 3, 0, 8, segs, endsNode, valuesNode);

        // Then — every row resolves to some in-bounds run value, no raw exception escapes
        IntArray ints = (IntArray) result;
        for (int i = 0; i < 8; i++) {
            assertThat(ints.getInt(i)).isIn(10, 20, 30);
        }
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
