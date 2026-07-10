package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.fbs.FbsArraySpec;
import io.github.dfa1.vortex.core.fbs.FbsBuilder;
import io.github.dfa1.vortex.core.fbs.FbsDType;
import io.github.dfa1.vortex.core.fbs.FbsFooter;
import io.github.dfa1.vortex.core.fbs.FbsLayout;
import io.github.dfa1.vortex.core.fbs.FbsLayoutSpec;
import io.github.dfa1.vortex.core.fbs.FbsNull;
import io.github.dfa1.vortex.core.fbs.FbsStruct;
import io.github.dfa1.vortex.core.fbs.FbsType;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Drives the struct-dtype guards in `PostscriptParser.convertDType` with crafted dtype
/// FlatBuffers through the package-private [PostscriptParser#parseBlobs] — no file needed.
///
/// The duplicate-name and arity-desync inputs are files the reference writer refuses to produce
/// ("StructLayout must have unique field names") or that no writer produces at all, so they only
/// arrive crafted or corrupt — untrusted input must fail as [VortexException], never flow into
/// the name-keyed Chunk maps where a duplicate silently drops a column. Blank names are
/// wire-legal and accepted on both read and write paths. Control characters are rejected on
/// both paths (they break CSV/JSON/SQL downstream); the read path wraps the error as a
/// [VortexException] with field-index context rather than leaking a raw IllegalArgumentException.
class PostscriptParserDTypeGuardsTest {

    @Test
    void convertDType_duplicateStructFieldNames_throwsVortexException() {
        // Given — a dtype blob whose root struct declares two fields named "dup"
        MemorySegment dtype = structDType(new String[]{"dup", "dup"}, 2);

        // When / Then — footer/layout fixtures hoisted so only the subject call is in the lambda
        MemorySegment footer = minimalFooter();
        MemorySegment layout = flatLayout();
        assertThatThrownBy(() -> PostscriptParser.parseBlobs(footer, layout, dtype))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("duplicate field name in file schema: dup");
    }

    @Test
    void convertDType_structNamesDtypesArityMismatch_throwsVortexException() {
        // Given — a dtype blob declaring two field names but only one field type
        MemorySegment dtype = structDType(new String[]{"a", "b"}, 1);

        // When / Then — footer/layout fixtures hoisted so only the subject call is in the lambda
        MemorySegment footer = minimalFooter();
        MemorySegment layout = flatLayout();
        assertThatThrownBy(() -> PostscriptParser.parseBlobs(footer, layout, dtype))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("names/dtypes length mismatch");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", " ", "   "})
    void convertDType_blankFieldName_isAccepted(String name) {
        // Given — a file schema carrying a blank field name (wire-legal; the reference
        // implementation produces it, e.g. an empty name at field index 0 in
        // uci-electricityloaddiagrams20112014). The read path must not reject it (#255).
        MemorySegment dtype = structDType(new String[]{name}, 1);

        // When
        MemorySegment footer = minimalFooter();
        MemorySegment layout = flatLayout();
        PostscriptParser.ParsedFile result = PostscriptParser.parseBlobs(footer, layout, dtype);

        // Then — the blank name round-trips into the parsed struct dtype verbatim
        assertThat(result.dtype()).isInstanceOfSatisfying(DType.Struct.class, struct ->
                assertThat(struct.fieldNames()).containsExactly(new ColumnName(name)));
    }

    @Test
    void convertDType_controlCharacterFieldName_throwsVortexException() {
        // Given — control characters in column names break downstream consumers (CSV/JSON/SQL);
        // ColumnName rejects them on both the read and write paths. The read path wraps the
        // rejection as VortexException with field-index context (#255).
        MemorySegment dtype = structDType(new String[]{"a\nb"}, 1);

        // When / Then — footer/layout fixtures hoisted so only the subject call is in the lambda
        MemorySegment footer = minimalFooter();
        MemorySegment layout = flatLayout();
        assertThatThrownBy(() -> PostscriptParser.parseBlobs(footer, layout, dtype))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("U+000A");
    }

    @Test
    void convertFooter_blankArraySpec_throwsVortexException() {
        // Given — a footer whose array spec dictionary carries a blank encoding id (a zero-length
        // FlatBuffer string). Parsing now happens once at the footer boundary, so the blank guard
        // that used to live in the per-node decoder is exercised here.
        MemorySegment footer = footerWithArraySpec("");
        MemorySegment layout = flatLayout();
        MemorySegment dtype = structDType(new String[]{"a"}, 1);

        // When / Then
        assertThatThrownBy(() -> PostscriptParser.parseBlobs(footer, layout, dtype))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("blank encoding id at array spec index 0");
    }

    // ── FlatBuffer builders ─────────────────────────────────────────────────────

    private static MemorySegment footerWithArraySpec(String arraySpec) {
        var fbb = new FbsBuilder(256);
        int asv = FbsFooter.createArraySpecsVector(fbb, new int[]{
                FbsArraySpec.createFbsArraySpec(fbb, fbb.createString(arraySpec))});
        int lsv = FbsFooter.createLayoutSpecsVector(fbb, new int[]{
                FbsLayoutSpec.createFbsLayoutSpec(fbb, fbb.createString("vortex.flat"))});
        FbsFooter.startSegmentSpecsVector(fbb, 0);
        int ssv = fbb.endVector();
        int footOff = FbsFooter.createFbsFooter(fbb, asv, lsv, ssv, 0, 0);
        fbb.finish(footOff);
        return fbb.dataSegment();
    }

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
        int namesVec = FbsStruct.createNamesVector(fbb, nameOffsets);
        int dtypesVec = FbsStruct.createDtypesVector(fbb, fieldOffsets);
        int struct = FbsStruct.createFbsStruct(fbb, namesVec, dtypesVec, false);
        int root = FbsDType.createFbsDType(fbb, FbsType.FbsStruct, struct);
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
