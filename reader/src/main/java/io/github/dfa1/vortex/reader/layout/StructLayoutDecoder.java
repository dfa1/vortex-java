package io.github.dfa1.vortex.reader.layout;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.LayoutId;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.StructArray;

import java.util.ArrayList;
import java.util.List;

/// Built-in decoder for the `vortex.struct` layout — one child per column, all spanning the same
/// full row range. Used to decode a struct column nested under the root struct (the root itself is
/// expanded column-by-column by the scan and is never routed here).
///
/// A struct layout carries a leading `validity` child when its dtype is nullable, mirroring the
/// Rust reference (`StructReader::try_new` inserts a `Bool` validity child at index 0 when
/// `layout.dtype.is_nullable()`). Field children then follow one-to-one with the struct's fields;
/// when validity is present it masks every field array so per-row nullness of the struct as a whole
/// is honored, matching [io.github.dfa1.vortex.reader.decode.StructEncodingDecoder].
final class StructLayoutDecoder implements LayoutDecoder {

    @Override
    public LayoutId layoutId() {
        return LayoutId.STRUCT;
    }

    @Override
    public Array decode(LayoutDecodeContext ctx, Layout layout, DType dtype) {
        if (!(dtype instanceof DType.Struct structDtype)) {
            throw new VortexException("vortex.struct layout requires a struct dtype, got " + dtype);
        }
        List<DType> fieldTypes = structDtype.fieldTypes();
        int nfields = fieldTypes.size();
        List<Layout> children = layout.children();
        boolean hasValidity = children.size() == nfields + 1;
        if (children.size() != nfields && !hasValidity) {
            throw new VortexException("vortex.struct layout has " + children.size()
                    + " children but the struct dtype has " + nfields + " fields");
        }
        int fieldOffset = hasValidity ? 1 : 0;

        BoolArray validity = null;
        if (hasValidity) {
            Array decoded = ctx.decodeChild(children.getFirst(), DType.BOOL);
            if (!(decoded instanceof BoolArray boolArray)) {
                throw new VortexException("vortex.struct validity decoded to unexpected type: "
                        + decoded.getClass().getSimpleName());
            }
            validity = boolArray;
        }

        List<Array> fields = new ArrayList<>(nfields);
        for (int i = 0; i < nfields; i++) {
            Array field = ctx.decodeChild(children.get(fieldOffset + i), fieldTypes.get(i));
            fields.add(validity != null ? new MaskedArray(field, validity) : field);
        }
        return new StructArray(structDtype, layout.rowCount(), fields);
    }
}
