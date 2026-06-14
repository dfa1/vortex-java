package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;

import java.util.ArrayList;
import java.util.List;

/// Shared scan-and-collect helpers for writer round-trip tests. Materialise each
/// chunk's values into a heap container before the chunk's arena closes; the
/// returned arrays/lists outlive the scan lifecycle.
final class VortexReads {

    private VortexReads() {
    }

    static int[] readAllInts(VortexReader vf, String col) {
        var collected = new ArrayList<Integer>();
        try (var iter = vf.scan(ScanOptions.all())) {
            iter.forEachRemaining(c -> {
                ((IntArray) c.column(col)).forEachInt(collected::add);
            });
        }
        return collected.stream().mapToInt(Integer::intValue).toArray();
    }

    static long[] readAllLongs(VortexReader vf, String col) {
        var collected = new ArrayList<Long>();
        try (var iter = vf.scan(ScanOptions.all())) {
            iter.forEachRemaining(c -> {
                ((LongArray) c.column(col)).forEachLong(collected::add);
            });
        }
        return collected.stream().mapToLong(Long::longValue).toArray();
    }

    static double[] readAllDoubles(VortexReader vf, String col) {
        var collected = new ArrayList<Double>();
        try (var iter = vf.scan(ScanOptions.all())) {
            iter.forEachRemaining(c -> {
                DoubleArray arr = (DoubleArray) c.column(col);
                arr.forEachDouble(collected::add);
            });
        }
        return collected.stream().mapToDouble(Double::doubleValue).toArray();
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
