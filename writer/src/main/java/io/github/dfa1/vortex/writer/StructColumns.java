package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// A struct column is written as Rust's `vortex.struct` layout: each field is its own column,
/// chunked and compressed on its own, under a validity child when the struct is nullable. The
/// writer's per-column machinery (repartition, global dictionaries, zone maps, segment sizes) is
/// keyed by [ColumnName], so each field of a struct, at any depth, is a *leaf column* named
/// `parent/field`; a struct with no fields stays a leaf, written as one array.
///
/// Rust zones the fields of a struct only while no struct above them is nullable.
final class StructColumns {

    private final DType.Struct leafSchema;
    private final Set<ColumnName> unzoned;
    private final Map<ColumnName, ColumnName> owners;

    /// @param schema the file's schema
    /// @throws IllegalArgumentException if a leaf's `parent/field` name equals another column's
    StructColumns(DType.Struct schema) {
        List<ColumnName> names = new ArrayList<>();
        List<DType> types = new ArrayList<>();
        this.unzoned = new LinkedHashSet<>();
        this.owners = new LinkedHashMap<>();
        for (int i = 0; i < schema.fieldNames().size(); i++) {
            flatten(schema.fieldNames().get(i), schema.fieldNames().get(i), schema.fieldTypes().get(i), true, names, types);
        }
        this.leafSchema = new DType.Struct(names, types, false);
    }

    /// Whether `dtype` is written as a struct layout rather than as one array.
    static boolean isLayoutStruct(DType dtype) {
        return dtype instanceof DType.Struct struct && !struct.fieldNames().isEmpty();
    }

    /// The leaf column of `field` in the struct column `parent`.
    static ColumnName fieldKey(ColumnName parent, ColumnName field) {
        return ColumnName.of(parent.value() + "/" + field.value());
    }

    /// The columns actually written, one per leaf in schema order, with their dtypes.
    DType.Struct leafSchema() {
        return leafSchema;
    }

    /// The leaves that carry no zone map: those under a nullable struct.
    Set<ColumnName> unzoned() {
        return unzoned;
    }

    /// The top-level column a leaf belongs to, for attributing its bytes to that column.
    ColumnName owner(ColumnName leaf) {
        return owners.getOrDefault(leaf, leaf);
    }

    private void flatten(ColumnName column, ColumnName key, DType dtype, boolean zoned, List<ColumnName> names, List<DType> types) {
        if (!isLayoutStruct(dtype)) {
            names.add(key);
            owners.put(key, column);
            types.add(dtype);
            if (!zoned) {
                unzoned.add(key);
            }
            return;
        }
        DType.Struct struct = (DType.Struct) dtype;
        for (int i = 0; i < struct.fieldNames().size(); i++) {
            flatten(column, fieldKey(key, struct.fieldNames().get(i)), struct.fieldTypes().get(i),
                    zoned && !struct.nullable(), names, types);
        }
    }
}
