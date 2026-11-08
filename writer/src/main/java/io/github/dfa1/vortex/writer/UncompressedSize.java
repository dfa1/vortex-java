package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.writer.encode.FixedSizeListData;
import io.github.dfa1.vortex.writer.encode.ListData;
import io.github.dfa1.vortex.writer.encode.ListViewData;
import io.github.dfa1.vortex.writer.encode.NullableData;
import io.github.dfa1.vortex.writer.encode.StructData;

import java.nio.charset.StandardCharsets;
import java.util.List;

/// Rust's `uncompressed_size_in_bytes` of one batch: the bytes its rows take as a canonical array
/// (`vortex-array/src/aggregate_fn/fns/uncompressed_size_in_bytes`), the file statistic vortex-jni
/// writes per top-level field.
///
/// - fixed-width values take their width per row (a decimal its precision's width), bools a packed bit;
/// - UTF-8 and binary take a 16-byte view per row, plus each valid value too long to inline in it;
/// - a struct is the sum of its fields, a list the elements its valid lists use plus 16 bytes of
///   offset and size per row, a fixed-size list all of its elements, an extension its storage;
/// - every array adds its validity per [#validityBytes(long, long)].
final class UncompressedSize {

    /// Rust's `BinaryView`: 16 bytes, of which up to 12 hold the value inline.
    private static final int VIEW_BYTES = 16;
    private static final int MAX_INLINED_BYTES = 12;

    /// A list view's offset and size, a `u64` each per row.
    private static final int LIST_VIEW_BYTES = 16;

    private UncompressedSize() {
    }

    /// Whether Rust measures arrays of `dtype`: every dtype but one holding a variant or a union,
    /// which no carrier of this writer holds.
    ///
    /// @param dtype the column dtype
    /// @return `true` when [#of(DType, Object, int)] can size it
    static boolean supports(DType dtype) {
        return switch (dtype) {
            case DType.Variant _, DType.Union _ -> false;
            case DType.Extension ext -> supports(ext.storageDType());
            case DType.Struct s -> s.fieldTypes().stream().allMatch(UncompressedSize::supports);
            case DType.List l -> supports(l.elementType());
            case DType.FixedSizeList f -> supports(f.elementType());
            case DType.Map m -> supports(m.keyType()) && supports(m.valueType());
            default -> true;
        };
    }

    /// The size of one batch.
    ///
    /// @param dtype the column dtype, one [#supports(DType)] accepts
    /// @param data  the batch's carrier
    /// @param rows  the batch's row count
    /// @return its uncompressed size in bytes
    static long of(DType dtype, Object data, int rows) {
        return size(dtype, data, Rows.range(0, rows));
    }

    /// Rust's `validity_uncompressed_size_in_bytes`: nothing when every row is valid, and when none
    /// is, the one buffer of a `ConstantArray(false)` (the 2-byte proto `ScalarValue { bool_value:
    /// false }`); otherwise a packed bitmap.
    ///
    /// @param valid the valid rows
    /// @param rows  all rows
    /// @return the validity's size in bytes
    static long validityBytes(long valid, long rows) {
        if (valid == rows) {
            return 0;
        }
        if (valid == 0) {
            return 2;
        }
        return (rows + 7) / 8;
    }

    private static long size(DType dtype, Object data, Rows rows) {
        if (dtype instanceof DType.Extension ext) {
            return size(ext.storageDType(), data, rows);
        }
        if (dtype instanceof DType.Null) {
            // a null array has no buffer and no validity
            return 0;
        }
        boolean[] validity = data instanceof NullableData nd ? nd.validity() : null;
        Object values = data instanceof NullableData nd ? nd.values() : data;
        long n = rows.count();
        long bytes = switch (dtype) {
            case DType.Bool _ -> (n + 7) / 8;
            case DType.Primitive p -> n * p.ptype().byteSize();
            case DType.Decimal d -> n * d.byteWidth();
            case DType.Utf8 _, DType.Binary _ -> VIEW_BYTES * n + outOfLineBytes(values, validity, rows);
            case DType.Struct s -> fieldsBytes(s.fieldTypes(), ((StructData) values).fieldArrays(), rows);
            case DType.List l -> listBytes(values, validity, rows, (elements, used) -> size(l.elementType(), elements, used));
            // a map is a list of non-nullable key-value entries
            case DType.Map m -> listBytes(values, validity, rows, (entries, used) ->
                    fieldsBytes(List.of(m.keyType(), m.valueType()), ((StructData) entries).fieldArrays(), used));
            case DType.FixedSizeList f -> fixedSizeListBytes(f, (FixedSizeListData) values, rows);
            case DType.Null _, DType.Extension _, DType.Variant _, DType.Union _ ->
                    throw new IllegalStateException("no uncompressed size for " + dtype);
        };
        return bytes + validityBytes(validCount(validity, rows), n);
    }

    private static long fieldsBytes(List<DType> types, List<Object> fields, Rows rows) {
        long bytes = 0;
        for (int i = 0; i < types.size(); i++) {
            bytes += size(types.get(i), fields.get(i), rows);
        }
        return bytes;
    }

    /// Rust's `list_view_uncompressed_size_in_bytes`: the elements the valid lists use, as one slice
    /// when they tile one run of elements, else each counted as often as a list uses it.
    private static long listBytes(Object values, boolean[] validity, Rows rows, ElementSize elementSize) {
        long views = (long) LIST_VIEW_BYTES * rows.count();
        Object elements;
        long[] starts = new long[rows.count()];
        long[] sizes = new long[rows.count()];
        switch (values) {
            case ListData d -> {
                elements = d.elements();
                for (int i = 0; i < rows.count(); i++) {
                    int r = rows.at(i);
                    starts[i] = d.offsets()[r];
                    sizes[i] = d.offsets()[r + 1] - d.offsets()[r];
                }
            }
            case ListViewData d -> {
                elements = d.elements();
                for (int i = 0; i < rows.count(); i++) {
                    int r = rows.at(i);
                    starts[i] = d.offsets()[r];
                    sizes[i] = d.sizes()[r];
                }
            }
            default -> throw new IllegalStateException("not a list carrier: " + values.getClass().getSimpleName());
        }
        long start = Long.MAX_VALUE;
        long end = Long.MIN_VALUE;
        long count = 0;
        for (int i = 0; i < rows.count(); i++) {
            if (validity == null || validity[rows.at(i)]) {
                start = Math.min(start, starts[i]);
                end = Math.max(end, starts[i] + sizes[i]);
                count += sizes[i];
            }
        }
        if (start == Long.MAX_VALUE) {
            // no valid list
            return views;
        }
        Rows used;
        if (count == end - start) {
            used = Rows.range((int) start, (int) count);
        } else {
            int[] indices = new int[Math.toIntExact(count)];
            int k = 0;
            for (int i = 0; i < rows.count(); i++) {
                if (validity == null || validity[rows.at(i)]) {
                    for (long e = starts[i]; e < starts[i] + sizes[i]; e++) {
                        indices[k++] = (int) e;
                    }
                }
            }
            used = Rows.of(indices);
        }
        return views + elementSize.of(elements, used);
    }

    /// Rust's `fixed_size_list_uncompressed_size_in_bytes`: every element of the rows, null rows too.
    private static long fixedSizeListBytes(DType.FixedSizeList dtype, FixedSizeListData data, Rows rows) {
        int width = dtype.fixedSize();
        Rows elements;
        if (rows.indices() == null) {
            elements = Rows.range(rows.offset() * width, rows.count() * width);
        } else {
            int[] indices = new int[rows.count() * width];
            for (int i = 0; i < rows.count(); i++) {
                for (int k = 0; k < width; k++) {
                    indices[i * width + k] = rows.at(i) * width + k;
                }
            }
            elements = Rows.of(indices);
        }
        return size(dtype.elementType(), data.elements(), elements);
    }

    /// The bytes of the valid values longer than 12, which a VarBinView keeps out of its views
    /// (`BinaryView::MAX_INLINED_SIZE`).
    private static long outOfLineBytes(Object values, boolean[] validity, Rows rows) {
        long bytes = 0;
        for (int i = 0; i < rows.count(); i++) {
            int r = rows.at(i);
            if (validity != null && !validity[r]) {
                continue;
            }
            int length = switch (values) {
                case String[] a -> a[r] == null ? 0 : a[r].getBytes(StandardCharsets.UTF_8).length;
                case byte[][] a -> a[r] == null ? 0 : a[r].length;
                default -> throw new IllegalStateException("not a string carrier: " + values.getClass().getSimpleName());
            };
            if (length > MAX_INLINED_BYTES) {
                bytes += length;
            }
        }
        return bytes;
    }

    private static long validCount(boolean[] validity, Rows rows) {
        if (validity == null) {
            return rows.count();
        }
        long valid = 0;
        for (int i = 0; i < rows.count(); i++) {
            if (validity[rows.at(i)]) {
                valid++;
            }
        }
        return valid;
    }

    /// The size of a list's elements over the rows its valid lists use.
    @FunctionalInterface
    private interface ElementSize {
        long of(Object elements, Rows used);
    }

    /// The rows of a carrier an array holds: a run `offset..offset + count`, or the listed indices.
    private record Rows(int offset, int count, int[] indices) {

        static Rows range(int offset, int count) {
            return new Rows(offset, count, null);
        }

        static Rows of(int[] indices) {
            return new Rows(0, indices.length, indices);
        }

        int at(int i) {
            if (indices == null) {
                return offset + i;
            }
            return indices[i];
        }
    }
}
