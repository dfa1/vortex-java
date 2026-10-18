package io.github.dfa1.vortex.fuzz;

import java.lang.foreign.Arena;
import java.util.function.Supplier;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.DecimalArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FixedSizeListArray;
import io.github.dfa1.vortex.reader.array.Float16Array;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.ListArray;
import io.github.dfa1.vortex.reader.array.ListViewArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MapArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.reader.array.StructArray;
import io.github.dfa1.vortex.reader.array.UnionArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.array.VariantArray;

/// Reads every value of a decoded chunk, so a fuzz target reaches the code that only runs on value
/// access: `Lazy*` arrays decode when read, not when the chunk is built, and the reader's security
/// contract holds there too.
///
/// Each leaf is read twice over, through the two decode paths it has: bulk
/// [Array#materialize(java.lang.foreign.SegmentAllocator)] and the per-row getters, which are separate
/// code in the lazy arrays. Every column is walked again limited to half its rows, for row-range
/// slicing (where a buffer-alignment abort once hid). A [VortexException] ends only the walk of the
/// array that threw, so one rejected column does not hide the others from the fuzzer.
final class ArrayWalker {

    private ArrayWalker() {
    }

    /// Reads every value of every column of `chunk`, whole and limited to half its rows.
    ///
    /// @param chunk the decoded chunk
    /// @param arena allocator for materialized values
    static void walk(Chunk chunk, Arena arena) {
        for (Chunk.Column column : chunk.columns().values()) {
            Array array = column.array();
            walk(array, arena);
            walk(() -> Array.limited(array, array.length() / 2), arena);
        }
    }

    private static void walk(Supplier<Array> array, Arena arena) {
        try {
            walk(array.get(), arena);
        } catch (VortexException _) {
            // rejected as malformed: the contract holds
        }
    }

    private static void walk(Array array, Arena arena) {
        switch (array) {
            case MaskedArray masked -> {
                walk(masked::validity, arena);
                walk(masked::inner, arena);
            }
            case StructArray struct -> {
                for (int i = 0; i < struct.fieldCount(); i++) {
                    int field = i;
                    walk(() -> struct.field(field), arena);
                }
            }
            case ListArray list -> {
                walk(list::offsets, arena);
                walk(list::elements, arena);
            }
            case ListViewArray list -> {
                walk(list::offsets, arena);
                walk(list::sizes, arena);
                walk(list::elements, arena);
            }
            case FixedSizeListArray list -> walk(list::elements, arena);
            case MapArray map -> walk(map::entries, arena);
            case UnionArray union -> {
                walk(union::typeIds, arena);
                for (int i = 0; i < union.variantCount(); i++) {
                    int variant = i;
                    walk(() -> union.variant(variant), arena);
                }
            }
            case VariantArray variant -> {
                walk(variant::coreStorage, arena);
                if (variant.shredded() != null) {
                    walk(variant::shredded, arena);
                }
            }
            default -> leaf(array, arena);
        }
    }

    private static void leaf(Array array, Arena arena) {
        try {
            array.materialize(arena);
        } catch (VortexException _) {
            // rejected as malformed: the per-row path below is separate code, so still walk it
        }
        long rows = array.length();
        switch (array) {
            case LongArray a -> {
                for (long i = 0; i < rows; i++) {
                    a.getLong(i);
                }
            }
            case IntArray a -> {
                for (long i = 0; i < rows; i++) {
                    a.getInt(i);
                }
            }
            case ShortArray a -> {
                for (long i = 0; i < rows; i++) {
                    a.getShort(i);
                }
            }
            case ByteArray a -> {
                for (long i = 0; i < rows; i++) {
                    a.getByte(i);
                }
            }
            case DoubleArray a -> {
                for (long i = 0; i < rows; i++) {
                    a.getDouble(i);
                }
            }
            case FloatArray a -> {
                for (long i = 0; i < rows; i++) {
                    a.getFloat(i);
                }
            }
            case Float16Array a -> {
                for (long i = 0; i < rows; i++) {
                    a.getFloat(i);
                }
            }
            case BoolArray a -> {
                for (long i = 0; i < rows; i++) {
                    a.getBoolean(i);
                }
            }
            case DecimalArray a -> {
                for (long i = 0; i < rows; i++) {
                    a.getDecimal(i);
                }
            }
            case VarBinArray a -> {
                for (long i = 0; i < rows; i++) {
                    a.getBytes(i);
                }
            }
            default -> {
                // NullArray, UnknownArray, GenericArray: nothing per row beyond materialize
            }
        }
    }
}
