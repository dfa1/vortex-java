package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.fbs.FbsArray;
import io.github.dfa1.vortex.core.fbs.FbsArrayNode;
import io.github.dfa1.vortex.core.fbs.FbsArrayStats;
import io.github.dfa1.vortex.core.fbs.FbsBuilder;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.ArrayStats;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.core.model.LayoutId;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.reader.layout.Layout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.foreign.MemorySegment;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/// A malformed stats segment found by the `scanMalformedFile` fuzz target (#493): it escaped the
/// scan as a raw JDK exception instead of degrading to "no stats".
@ExtendWith(MockitoExtension.class)
class ScanIteratorMalformedStatsTest {

    private static final ColumnName COLUMN = ColumnName.of("v");
    private static final DType.Struct SCHEMA = new DType.Struct(List.of(COLUMN), List.of(DTypes.I64), false);

    @Mock
    private VortexHandle file;

    @Test
    void statsSegmentWithCorruptFlatBuffer_degradesToEmptyStats() {
        // Given — a flat whose segment frames a 4-byte FlatBuffer (the trailing length is sane)
        // whose root offset points far outside it; FlatBuffer navigation used to escape as a raw
        // IndexOutOfBoundsException although malformed stats are documented to read as "no stats"
        MemorySegment seg = MemorySegment.ofArray(new byte[8]);
        seg.set(VortexFormat.LE_INT, 0, 1_000_000);
        seg.set(VortexFormat.LE_INT, 4, 4);
        Layout flat = new Layout(LayoutId.FLAT, 5, null, List.of(), List.of(0));
        given(file.dtype()).willReturn(SCHEMA);
        given(file.layout()).willReturn(new Layout(LayoutId.STRUCT, 5, null, List.of(flat), List.of()));
        given(file.footer()).willReturn(new Footer(List.of(), List.of(),
                List.of(new SegmentSpec(0, 8, (byte) 0, CompressionScheme.NONE)), List.of()));
        given(file.rawSegment(any())).willReturn(seg);

        // When
        List<ArrayStats> result;
        try (ScanIterator sut = new ScanIterator(file, ScanOptions.all())) {
            result = sut.chunkStats(COLUMN);
        }

        // Then
        assertThat(result).containsExactly(ArrayStats.empty());
    }

    // The flat node's stats scalar is dtype-blind on the wire: only the column's dtype turns Rust's
    // little-endian decimal bytes into a number, so the dtype must reach the parser (#710).
    @Test
    void decimalColumn_readsTheBoundsOfAFlatNodeAsBigDecimals() {
        // Given
        DType.Decimal decimal = new DType.Decimal((byte) 9, (byte) 2, false);
        given(file.dtype()).willReturn(new DType.Struct(List.of(COLUMN), List.of(decimal), false));
        given(file.layout()).willReturn(new Layout(LayoutId.STRUCT, 5, null,
                List.of(new Layout(LayoutId.FLAT, 5, null, List.of(), List.of(0))), List.of()));
        MemorySegment seg = flatSegmentWithBounds(
                ProtoScalarValue.ofBytesValue(new byte[]{(byte) 0x9C, (byte) 0xFF}).encode(),   // -100 as an i16
                ProtoScalarValue.ofBytesValue(new byte[]{99, 0}).encode());
        given(file.footer()).willReturn(new Footer(List.of(), List.of(),
                List.of(new SegmentSpec(0, seg.byteSize(), (byte) 0, CompressionScheme.NONE)), List.of()));
        given(file.rawSegment(any())).willReturn(seg);

        // When
        List<ArrayStats> result;
        try (ScanIterator sut = new ScanIterator(file, ScanOptions.all())) {
            result = sut.chunkStats(COLUMN);
        }

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().min()).isEqualTo(new BigDecimal("-1.00"));
        assertThat(result.getFirst().max()).isEqualTo(new BigDecimal("0.99"));
    }

    @Test
    void decimalColumn_withABoundThatIsNotBytes_degradesToEmptyStats() {
        // Given — an NPE here would escape a scan, chunkStats and columnStats alike
        DType.Decimal decimal = new DType.Decimal((byte) 9, (byte) 2, false);
        given(file.dtype()).willReturn(new DType.Struct(List.of(COLUMN), List.of(decimal), false));
        given(file.layout()).willReturn(new Layout(LayoutId.STRUCT, 5, null,
                List.of(new Layout(LayoutId.FLAT, 5, null, List.of(), List.of(0))), List.of()));
        MemorySegment seg = flatSegmentWithBounds(ProtoScalarValue.ofStringValue("1.5").encode(), null);
        given(file.footer()).willReturn(new Footer(List.of(), List.of(),
                List.of(new SegmentSpec(0, seg.byteSize(), (byte) 0, CompressionScheme.NONE)), List.of()));
        given(file.rawSegment(any())).willReturn(seg);

        // When
        List<ArrayStats> result;
        try (ScanIterator sut = new ScanIterator(file, ScanOptions.all())) {
            result = sut.chunkStats(COLUMN);
        }

        // Then
        assertThat(result).containsExactly(ArrayStats.empty());
    }

    /// A data-less flat segment: the array FlatBuffer (one node, no buffers) then its 4-byte length,
    /// the root node carrying `min`/`max` as its stats.
    private static MemorySegment flatSegmentWithBounds(byte[] min, byte[] max) {
        FbsBuilder fbb = new FbsBuilder(128);
        int minOff = min == null ? 0 : FbsArrayStats.createMinVector(fbb, min);
        int maxOff = max == null ? 0 : FbsArrayStats.createMaxVector(fbb, max);
        FbsArrayStats.startFbsArrayStats(fbb);
        if (min != null) {
            FbsArrayStats.addMin(fbb, minOff);
        }
        if (max != null) {
            FbsArrayStats.addMax(fbb, maxOff);
        }
        int statsOff = FbsArrayStats.endFbsArrayStats(fbb);
        int nodeOff = FbsArrayNode.createFbsArrayNode(fbb, 0, 0, 0, 0, statsOff);
        FbsArray.finishFbsArrayBuffer(fbb, FbsArray.createFbsArray(fbb, nodeOff, 0));
        byte[] flatBuffer = fbb.sizedByteArray();
        MemorySegment seg = MemorySegment.ofArray(new byte[flatBuffer.length + 4]);
        MemorySegment.copy(MemorySegment.ofArray(flatBuffer), 0, seg, 0, flatBuffer.length);
        seg.set(VortexFormat.LE_INT, flatBuffer.length, flatBuffer.length);
        return seg;
    }
}
