package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.fbs.FbsBuilder;
import io.github.dfa1.vortex.fbs.FbsArraySpec;
import io.github.dfa1.vortex.fbs.FbsLayoutSpec;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;

class PostscriptParserBigSegmentTest {

    private static ByteBuffer buildMinimalFooter(long segOffset, long segLength) {
        var fbb = new FbsBuilder(256);

        // Empty array_specs + layout_specs vectors (required tables, no entries).
        int asv = io.github.dfa1.vortex.fbs.FbsFooter.createArraySpecsVector(fbb, new int[]{
                FbsArraySpec.createFbsArraySpec(fbb, fbb.createString("vortex.flat"))
        });
        int lsv = io.github.dfa1.vortex.fbs.FbsFooter.createLayoutSpecsVector(fbb, new int[]{
                FbsLayoutSpec.createFbsLayoutSpec(fbb, fbb.createString("vortex.flat"))
        });

        // One segment_spec with the big length.
        io.github.dfa1.vortex.fbs.FbsFooter.startSegmentSpecsVector(fbb, 1);
        io.github.dfa1.vortex.fbs.FbsSegmentSpec.createFbsSegmentSpec(fbb, segOffset, segLength, 6, 0, 0);
        int ssv = fbb.endVector();

        int off = io.github.dfa1.vortex.fbs.FbsFooter.createFbsFooter(fbb, asv, lsv, ssv, 0, 0);
        fbb.finish(off);
        return fbb.dataSegment().asByteBuffer().order(java.nio.ByteOrder.LITTLE_ENDIAN);
    }

    @Test
    void convertFooter_preservesSegmentLengthAbove2GB() {
        // Given — a FlatBuffer Footer whose only segment_spec advertises a uint32 length
        // of 0xC000_0000 (3,221,225,472 bytes ≈ 3 GB). This value is negative when
        // interpreted as a signed int — the old code path truncated it silently.
        long bigLength = 0xC000_0000L;
        long bigOffset = 0x1_0000_0000L; // 4 GB into the file
        ByteBuffer fbsFooterBytes = buildMinimalFooter(bigOffset, bigLength);
        io.github.dfa1.vortex.fbs.FbsFooter fbsFooter =
                io.github.dfa1.vortex.fbs.FbsFooter.getRootAsFbsFooter(java.lang.foreign.MemorySegment.ofBuffer(fbsFooterBytes));

        // When
        Footer footer = PostscriptParser.convertFooter(fbsFooter);

        // Then
        assertThat(footer.segmentSpecs()).hasSize(1);
        SegmentSpec spec = footer.segmentSpecs().getFirst();
        assertThat(spec.offset()).isEqualTo(bigOffset);
        assertThat(spec.length())
                .as("u32 segment length above Integer.MAX_VALUE must not be truncated")
                .isEqualTo(bigLength);
        assertThat(spec.length()).isGreaterThan(Integer.MAX_VALUE);
    }
}
