package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.ArraySegments;
import io.github.dfa1.vortex.core.array.VarBinArray;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;

import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/// Shared scan-and-collect helpers for writer round-trip tests. Materialise each
/// chunk's values into a heap container before the chunk's arena closes; the
/// returned arrays/lists outlive the scan lifecycle.
final class VortexReads {

    private static final ValueLayout.OfInt LE_INT =
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LE_LONG =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private VortexReads() {
    }

    static int[] readAllInts(VortexReader vf, String col) {
        var collected = new ArrayList<Integer>();
        try (var iter = vf.scan(ScanOptions.all())) {
            iter.forEachRemaining(c -> {
                Array arr = c.column(col);
                for (long i = 0; i < arr.length(); i++) {
                    collected.add(ArraySegments.of(arr).get(LE_INT, i * Integer.BYTES));
                }
            });
        }
        return collected.stream().mapToInt(Integer::intValue).toArray();
    }

    static long[] readAllLongs(VortexReader vf, String col) {
        var collected = new ArrayList<Long>();
        try (var iter = vf.scan(ScanOptions.all())) {
            iter.forEachRemaining(c -> {
                Array arr = c.column(col);
                for (long i = 0; i < arr.length(); i++) {
                    collected.add(ArraySegments.of(arr).get(LE_LONG, i * Long.BYTES));
                }
            });
        }
        return collected.stream().mapToLong(Long::longValue).toArray();
    }

    static List<String> readAllStrings(VortexReader vf, String col) {
        var collected = new ArrayList<String>();
        try (var iter = vf.scan(ScanOptions.all())) {
            iter.forEachRemaining(c -> {
                VarBinArray vb = (VarBinArray) c.column(col);
                for (long i = 0; i < vb.length(); i++) {
                    collected.add(vb.getString(i));
                }
            });
        }
        return collected;
    }
}
