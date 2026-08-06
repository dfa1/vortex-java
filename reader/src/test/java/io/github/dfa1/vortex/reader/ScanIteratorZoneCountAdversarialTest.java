package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.LayoutId;
import io.github.dfa1.vortex.reader.layout.Layout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/// A `vortex.stats` (zoned) layout's zone-map table row count is its own layout metadata field
/// (`statsFlat.rowCount()`), never bounds-checked at parse time or cross-checked against the
/// data layout's actual chunk count (see [ScanIterator#columnZoneStats] Javadoc — the two are
/// deliberately decoupled). [ScanIterator] previously cast that attacker-controlled row count
/// straight to `int` to size an `ArrayList`: a negative value threw a raw
/// `IllegalArgumentException` and a value just over `Integer.MAX_VALUE` wrapped to negative on
/// the cast, both instead of the documented "fall back to per-chunk stats" behavior.
@ExtendWith(MockitoExtension.class)
class ScanIteratorZoneCountAdversarialTest {

    private static final ColumnName COLUMN = ColumnName.of("v");
    private static final DType.Struct SCHEMA = new DType.Struct(List.of(COLUMN), List.of(DType.I64), false);

    @Mock
    private VortexHandle file;

    @ParameterizedTest
    @ValueSource(longs = {-1L, Long.MIN_VALUE, ((long) Integer.MAX_VALUE) + 1L, Long.MAX_VALUE})
    void corruptZoneCount_fallsBackInsteadOfCrashing(long corruptZoneCount) {
        // Given — a one-chunk file whose zone-map table declares a corrupt row count
        Layout root = rootLayout(corruptZoneCount);
        Footer footer = new Footer(List.of(), List.of(),
                List.of(new SegmentSpec(0, 0, (byte) 0, CompressionScheme.NONE),
                        new SegmentSpec(0, 0, (byte) 0, CompressionScheme.NONE)),
                List.of());
        given(file.dtype()).willReturn(SCHEMA);
        given(file.layout()).willReturn(root);
        given(file.footer()).willReturn(footer);

        // When
        List<ArrayStats> result;
        try (ScanIterator sut = new ScanIterator(file, ScanOptions.columns("v"))) {
            result = sut.columnZoneStats("v");
        }

        // Then — degrades to the per-chunk fallback (one empty entry per chunk), no raw exception
        assertThat(result).hasSize(1);
        assertThat(result.getFirst()).isEqualTo(ArrayStats.empty());
    }

    @Test
    void plausibleZoneCount_isNotRejected() {
        // Given — a small, legitimate-looking zone count on an otherwise-corrupt (headerless)
        // stats segment, which still degrades gracefully once decoding is attempted
        Layout root = rootLayout(1L);
        Footer footer = new Footer(List.of(), List.of(),
                List.of(new SegmentSpec(0, 0, (byte) 0, CompressionScheme.NONE),
                        new SegmentSpec(0, 0, (byte) 0, CompressionScheme.NONE)),
                List.of());
        given(file.dtype()).willReturn(SCHEMA);
        given(file.layout()).willReturn(root);
        given(file.footer()).willReturn(footer);

        // When
        List<ArrayStats> result;
        try (ScanIterator sut = new ScanIterator(file, ScanOptions.columns("v"))) {
            result = sut.columnZoneStats("v");
        }

        // Then — the guard only rejects implausible counts; this one reaches the normal decode
        // path (which itself falls back gracefully on the segment's missing content)
        assertThat(result).hasSize(1);
    }

    /// Builds `Struct(v) -> Zoned[Flat(data, empty segment 0), Flat(stats, rowCount=zoneCount,
    /// segment 1)]`. The data flat's zero-length segment makes the per-chunk fallback resolve to
    /// [ArrayStats#empty()] without needing real FlatBuffer bytes.
    private static Layout rootLayout(long zoneCount) {
        Layout dataFlat = new Layout(LayoutId.FLAT, 5, null, List.of(), List.of(0));
        Layout statsFlat = new Layout(LayoutId.FLAT, zoneCount, minStatBitset(), List.of(), List.of(1));
        Layout zoned = new Layout(LayoutId.STATS, 5, null, List.of(dataFlat, statsFlat), List.of());
        return new Layout(LayoutId.STRUCT, 5, null, List.of(zoned), List.of());
    }

    /// `vortex.stats` metadata: 4-byte zone length (unused here) + a bitset with the `MIN` bit
    /// (ordinal 4) set, so [io.github.dfa1.vortex.reader.layout.ZonedStatsSchema#statsTableDtype]
    /// resolves a non-empty schema and the code under test proceeds past its early-return guards.
    private static MemorySegment minStatBitset() {
        MemorySegment seg = Arena.ofAuto().allocate(5);
        seg.set(ValueLayout.JAVA_BYTE, 4, (byte) 0x10);
        return seg;
    }
}
