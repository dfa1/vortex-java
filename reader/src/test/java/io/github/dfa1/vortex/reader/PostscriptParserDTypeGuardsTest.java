package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.fbs.FbsArraySpec;
import io.github.dfa1.vortex.core.fbs.FbsBuilder;
import io.github.dfa1.vortex.core.fbs.FbsDType;
import io.github.dfa1.vortex.core.fbs.FbsFooter;
import io.github.dfa1.vortex.core.fbs.FbsLayout;
import io.github.dfa1.vortex.core.fbs.FbsLayoutSpec;
import io.github.dfa1.vortex.core.fbs.FbsNull;
import io.github.dfa1.vortex.core.fbs.FbsStruct_;
import io.github.dfa1.vortex.core.fbs.FbsType;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Drives the struct-dtype guards in `PostscriptParser.convertDType` with crafted dtype
/// FlatBuffers through the package-private [PostscriptParser#parseBlobs] — no file needed.
///
/// Both inputs are files the reference writer refuses to produce ("StructLayout must have
/// unique field names") or that no writer produces at all (names/dtypes arity desync), so they
/// only arrive crafted or corrupt — and untrusted input must fail as [VortexException], never
/// flow into the name-keyed Chunk maps where a duplicate silently drops a column.
class PostscriptParserDTypeGuardsTest {

    @Test
    void convertDType_duplicateStructFieldNames_throwsVortexException() {
        // Given — a dtype blob whose root struct declares two fields named "dup"
        MemorySegment dtype = structDType(new String[]{"dup", "dup"}, 2);

        // When / Then
        assertThatThrownBy(() -> PostscriptParser.parseBlobs(minimalFooter(), flatLayout(), dtype))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("duplicate field name in file schema: dup");
    }

    @Test
    void convertDType_structNamesDtypesArityMismatch_throwsVortexException() {
        // Given — a dtype blob declaring two field names but only one field type
        MemorySegment dtype = structDType(new String[]{"a", "b"}, 1);

        // When / Then
        assertThatThrownBy(() -> PostscriptParser.parseBlobs(minimalFooter(), flatLayout(), dtype))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("names/dtypes length mismatch");
    }

    // ── FlatBuffer builders ─────────────────────────────────────────────────────

    /// Builds a struct dtype blob with the given field names and `dtypeCount` null-typed fields.
    private static MemorySegment structDType(String[] names, int dtypeCount) {
        var fbb = new FbsBuilder(256);
        int[] fieldOffsets = new int[dtypeCount];
        for (int i = 0; i < dtypeCount; i++) {
            int nul = FbsNull.createFbsNull(fbb);
            fieldOffsets[i] = FbsDType.createFbsDType(fbb, FbsType.FbsNull, nul);
        }
        int[] nameOffsets = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            nameOffsets[i] = fbb.createString(names[i]);
        }
        int namesVec = FbsStruct_.createNamesVector(fbb, nameOffsets);
        int dtypesVec = FbsStruct_.createDtypesVector(fbb, fieldOffsets);
        int struct = FbsStruct_.createFbsStruct_(fbb, namesVec, dtypesVec, false);
        int root = FbsDType.createFbsDType(fbb, FbsType.FbsStruct_, struct);
        fbb.finish(root);
        return fbb.dataSegment();
    }

    private static MemorySegment minimalFooter() {
        var fbb = new FbsBuilder(256);
        int asv = FbsFooter.createArraySpecsVector(fbb, new int[]{
                FbsArraySpec.createFbsArraySpec(fbb, fbb.createString("vortex.primitive"))});
        int lsv = FbsFooter.createLayoutSpecsVector(fbb, new int[]{
                FbsLayoutSpec.createFbsLayoutSpec(fbb, fbb.createString("vortex.flat"))});
        FbsFooter.startSegmentSpecsVector(fbb, 0);
        int ssv = fbb.endVector();
        int footOff = FbsFooter.createFbsFooter(fbb, asv, lsv, ssv, 0, 0);
        fbb.finish(footOff);
        return fbb.dataSegment();
    }

    private static MemorySegment flatLayout() {
        var fbb = new FbsBuilder(128);
        int segV = FbsLayout.createSegmentsVector(fbb, new long[]{0});
        int off = FbsLayout.createFbsLayout(fbb, 0, 1L, 0, 0, segV);
        FbsLayout.finishFbsLayoutBuffer(fbb, off);
        return fbb.dataSegment();
    }
}
