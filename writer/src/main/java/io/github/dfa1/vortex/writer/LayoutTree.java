package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.fbs.FbsBuilder;
import io.github.dfa1.vortex.core.fbs.FbsFooter;
import io.github.dfa1.vortex.core.fbs.FbsLayout;
import io.github.dfa1.vortex.core.fbs.FbsLayoutSpec;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.LayoutId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.writer.GlobalDictWriter.DictColRef;
import io.github.dfa1.vortex.writer.VortexWriter.ChunkRef;
import io.github.dfa1.vortex.writer.ZoneMapWriter.ZoneMapRef;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;

/// The file's layout tree, and the footer's `layout_specs` list its nodes index into: every
/// layout node the writer emits is built here.
///
/// The root is a struct with one child per column: a chunked layout of flat segments (the flat
/// itself for one chunk), or the column's dict layout (several in a chunked layout), each wrapped in
/// its zone map when it has one.
final class LayoutTree {

    // Indices into the footer's layout_specs list, in the order specs() writes them
    private static final int FLAT = 0;
    private static final int CHUNKED = 1;
    private static final int STRUCT = 2;
    private static final int DICT = 3;
    private static final int ZONED = 4;

    private LayoutTree() {
    }

    /// The footer's `layout_specs` vector, in index order: FLAT, CHUNKED, STRUCT, DICT, then the
    /// zoned layout: Rust's `vortex.zoned` when the edition has it, else the legacy `vortex.stats`
    /// alias every reader accepts.
    static int specs(FbsBuilder fbb, LayoutId zoned) {
        int ls0 = FbsLayoutSpec.createFbsLayoutSpec(fbb, fbb.createString(LayoutId.FLAT.id()));
        int ls1 = FbsLayoutSpec.createFbsLayoutSpec(fbb, fbb.createString(LayoutId.CHUNKED.id()));
        int ls2 = FbsLayoutSpec.createFbsLayoutSpec(fbb, fbb.createString(LayoutId.STRUCT.id()));
        int ls3 = FbsLayoutSpec.createFbsLayoutSpec(fbb, fbb.createString(LayoutId.DICT.id()));
        int ls4 = FbsLayoutSpec.createFbsLayoutSpec(fbb, fbb.createString(zoned.id()));
        return FbsFooter.createLayoutSpecsVector(fbb, new int[]{ls0, ls1, ls2, ls3, ls4});
    }

    /// The serialized layout tree of a file with `schema`'s columns.
    ///
    /// @param chunks   each column's written chunks, for the columns not in `dicts`
    /// @param dicts    the columns written as global dictionaries
    /// @param zoneMaps the columns that carry a zone map
    static ByteBuffer build(DType.Struct schema, Map<ColumnName, List<ChunkRef>> chunks,
            Map<ColumnName, List<DictColRef>> dicts, Map<ColumnName, ZoneMapRef> zoneMaps) {
        var fbb = new FbsBuilder(256);
        int colCount = schema.fieldNames().size();

        int[] colLayouts = new int[colCount];
        long totalRows = 0;

        for (int c = 0; c < colCount; c++) {
            ColumnName colName = schema.fieldNames().get(c);
            List<DictColRef> refs = dicts.get(colName);
            if (refs != null) {
                long colRows = rows(refs);
                colLayouts[c] = zoned(fbb, zoneMaps.get(colName), dictLayout(fbb, refs), colRows);
                if (totalRows == 0) {
                    totalRows = colRows;
                }
            } else {
                List<ChunkRef> colChunks = chunks.get(colName);
                long colRows = 0;
                int[] flats = new int[colChunks.size()];
                for (int i = 0; i < colChunks.size(); i++) {
                    ChunkRef cr = colChunks.get(i);
                    int segV = FbsLayout.createSegmentsVector(fbb, new long[]{cr.segIdx()});
                    flats[i] = FbsLayout.createFbsLayout(fbb, FLAT, cr.rowCount(), 0, 0, segV);
                    colRows += cr.rowCount();
                }
                colLayouts[c] = zoned(fbb, zoneMaps.get(colName), chunked(fbb, flats, colRows), colRows);
                if (totalRows == 0) {
                    totalRows = colRows;
                }
            }
        }

        int rootChildV = FbsLayout.createChildrenVector(fbb, colLayouts);
        int rootLayout = FbsLayout.createFbsLayout(fbb, STRUCT, totalRows, 0, rootChildV, 0);
        FbsLayout.finishFbsLayoutBuffer(fbb, rootLayout);
        return fbb.dataSegment().asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    /// Wraps a column's data layout in a zoned layout when a zone-map was emitted for it (`zm`
    /// not `null`); otherwise returns the data layout unchanged.
    private static int zoned(FbsBuilder fbb, ZoneMapRef zm, int dataLayout, long colRows) {
        if (zm == null) {
            return dataLayout;
        }
        int zonesSegV = FbsLayout.createSegmentsVector(fbb, new long[]{zm.zonesSegIdx()});
        int zonesFlat = FbsLayout.createFbsLayout(fbb, FLAT, zm.nZones(), 0, 0, zonesSegV);
        int childV = FbsLayout.createChildrenVector(fbb, new int[]{dataLayout, zonesFlat});
        int metaV = FbsLayout.createMetadataVector(fbb, zm.metadata());
        return FbsLayout.createFbsLayout(fbb, ZONED, colRows, metaV, childV, 0);
    }

    /// A chunked layout of `children`, or the only child itself: Rust's `ChunkedLayoutStrategy`
    /// writes no chunked node around a single chunk.
    private static int chunked(FbsBuilder fbb, int[] children, long rows) {
        if (children.length == 1) {
            return children[0];
        }
        return FbsLayout.createFbsLayout(fbb, CHUNKED, rows, 0, FbsLayout.createChildrenVector(fbb, children), 0);
    }

    /// A column's dict layout: one dictionary is the column's layout; several are a chunked layout
    /// of them.
    private static int dictLayout(FbsBuilder fbb, List<DictColRef> refs) {
        int[] dictLayouts = new int[refs.size()];
        for (int i = 0; i < dictLayouts.length; i++) {
            dictLayouts[i] = dictColLayout(fbb, refs.get(i));
        }
        return chunked(fbb, dictLayouts, rows(refs));
    }

    private static long rows(List<DictColRef> refs) {
        long rows = 0;
        for (DictColRef ref : refs) {
            rows += ref.totalRows();
        }
        return rows;
    }

    private static int dictColLayout(FbsBuilder fbb, DictColRef ref) {
        // Build the codes layout: one Flat per original chunk, chunked when there are several
        int numChunks = ref.codesSegIdxes().size();
        int[] codesFlats = new int[numChunks];
        long totalCodesRows = 0;
        for (int j = 0; j < numChunks; j++) {
            long rowCount = ref.codesRowCounts().get(j);
            int segV = FbsLayout.createSegmentsVector(fbb, new long[]{ref.codesSegIdxes().get(j)});
            codesFlats[j] = FbsLayout.createFbsLayout(fbb, FLAT, rowCount, 0, 0, segV);
            totalCodesRows += rowCount;
        }
        int codesChunked = chunked(fbb, codesFlats, totalCodesRows);

        // Build values Flat layout
        int valSegV = FbsLayout.createSegmentsVector(fbb, new long[]{ref.valuesSegIdx()});
        int valuesFlat = FbsLayout.createFbsLayout(fbb, FLAT, ref.valuesLen(), 0, 0, valSegV);

        // DictLayoutMetadata proto (matches Rust): field 1 = codes_ptype (PType varint)
        byte[] metaBytes = dictLayoutMetadata(DictColumnState.CODES_PTYPE, ref.nullableCodes());
        int metaVec = metaBytes.length > 0 ? FbsLayout.createMetadataVector(fbb, metaBytes) : 0;

        // Dict layout: child[0]=values, child[1]=codes (matches Rust DictLayout child order)
        int[] dictChildren = {valuesFlat, codesChunked};
        int dictChildV = FbsLayout.createChildrenVector(fbb, dictChildren);
        return FbsLayout.createFbsLayout(fbb, DICT, totalCodesRows, metaVec, dictChildV, 0);
    }

    /// Rust's `DictLayoutMetadata`: field 1 `codes_ptype`, field 2 `is_nullable_codes`, field 3
    /// `all_values_referenced`. Rust's writer always sets fields 2 and 3 (field 3 as `false`: its
    /// `DictLayout::new` never claims every value is referenced), so the metadata matches
    /// vortex-jni's byte for byte.
    private static byte[] dictLayoutMetadata(PType codePType, boolean nullableCodes) {
        int ordinal = codePType.ordinal();
        // Fields 2 and 3, wire type 0 (varint): tags (2<<3)|0 = 0x10 and (3<<3)|0 = 0x18
        byte[] flags = {0x10, (byte) (nullableCodes ? 1 : 0), 0x18, 0};
        if (ordinal == 0) {
            // Proto3 omits default values; U8 ordinal=0 is the default
            return flags;
        }
        // Field 1, wire type 0 (varint): tag = (1<<3)|0 = 0x08
        return new byte[]{0x08, (byte) ordinal, flags[0], flags[1], flags[2], flags[3]};
    }
}
