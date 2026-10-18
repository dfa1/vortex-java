package io.github.dfa1.vortex.reader;

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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

/// A nullable root struct's layout leads with its validity child, as Rust writes it and as open
/// checks it: the scan must map field `i` to child `i + 1`. Mapping by raw index read column `a`
/// from the validity child, `b` from `a`'s data, and ran off the field list on the last child.
@ExtendWith(MockitoExtension.class)
class ScanIteratorNullableRootTest {

    private static final ColumnName A = ColumnName.of("a");
    private static final ColumnName B = ColumnName.of("b");

    @Mock
    private VortexHandle file;

    @Test
    void columnsSkipTheRootValidityChild() {
        // Given — layout children [validity (segment 0), a (segment 1), b (segment 2)]
        DType.Struct schema = new DType.Struct(List.of(A, B), List.of(DType.I64, DType.I64), true);
        List<Layout> children = List.of(flat(0), flat(1), flat(2));
        SegmentSpec bSegment = new SegmentSpec(200, 8, (byte) 0, CompressionScheme.NONE);
        given(file.dtype()).willReturn(schema);
        given(file.layout()).willReturn(new Layout(LayoutId.STRUCT, 5, null, children, List.of()));
        given(file.footer()).willReturn(new Footer(List.of(), List.of(), List.of(
                new SegmentSpec(0, 8, (byte) 0, CompressionScheme.NONE),
                new SegmentSpec(100, 8, (byte) 0, CompressionScheme.NONE),
                bSegment), List.of()));
        given(file.rawSegment(any())).willReturn(MemorySegment.ofArray(new byte[8]));

        // When
        try (ScanIterator sut = new ScanIterator(file, ScanOptions.all())) {
            sut.chunkStats(B);
        }

        // Then — column b's stats come from b's own segment
        then(file).should().rawSegment(bSegment);
    }

    private static Layout flat(int segment) {
        return new Layout(LayoutId.FLAT, 5, null, List.of(), List.of(segment));
    }
}
