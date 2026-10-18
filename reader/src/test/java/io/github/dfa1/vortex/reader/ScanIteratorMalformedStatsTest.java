package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.LayoutId;
import io.github.dfa1.vortex.reader.layout.Layout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.foreign.MemorySegment;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/// A malformed stats segment found by the `scanMalformedFile` fuzz target (#493): it escaped the
/// scan as a raw JDK exception instead of degrading to "no stats".
@ExtendWith(MockitoExtension.class)
class ScanIteratorMalformedStatsTest {

    private static final ColumnName COLUMN = ColumnName.of("v");
    private static final DType.Struct SCHEMA = new DType.Struct(List.of(COLUMN), List.of(DType.I64), false);

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
}
