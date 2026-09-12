package io.github.dfa1.vortex.demo.fakedata;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/// Generates a Vortex file of synthetic data from a list of [ColumnDescriptor]s. The library
/// entry point behind [FakeDataGeneratorCli]; callable directly by other code (e.g. a demo
/// module) without shelling out to the CLI.
public final class FakeDataGenerator {

    private FakeDataGenerator() {
    }

    /// @param columns   column descriptors, in schema order
    /// @param rows      total number of rows to generate
    /// @param seed      seed for the shared random source (deterministic across runs)
    /// @param sortBy    column to sort all rows by (ascending), or `null` for generation order
    /// @param chunkSize rows per written chunk
    /// @param cascading write compression cascade depth (see `WriteOptions#cascading`)
    /// @param out       destination path; created or truncated
    /// @throws IOException if writing fails
    public static void generate(List<ColumnDescriptor> columns, int rows, long seed, String sortBy,
            int chunkSize, int cascading, Path out) throws IOException {
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("at least one column descriptor is required");
        }

        Random random = new Random(seed);
        Object[] columnArrays = new Object[columns.size()];
        for (int c = 0; c < columns.size(); c++) {
            columnArrays[c] = ColumnMaterializer.materialize(columns.get(c), rows, random);
        }

        if (sortBy != null) {
            int sortColumnIndex = indexOf(columns, sortBy);
            int[] permutation = sortPermutation(columnArrays[sortColumnIndex], rows);
            for (int c = 0; c < columns.size(); c++) {
                columnArrays[c] = permute(columnArrays[c], permutation);
            }
        }

        DType.Struct schema = buildSchema(columns);
        // globalDict defeats zone-map pruning on Utf8 columns (see the CLAUDE.md/README of
        // vortex-java's own reader module): the whole point of a fakedata tool feeding demos
        // that showcase pruning/partial fetches is to keep per-chunk stats meaningful.
        WriteOptions options = WriteOptions.cascading(cascading).withGlobalDict(false);
        try (FileChannel channel = FileChannel.open(out, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
             VortexWriter writer = VortexWriter.create(channel, schema, options)) {
            for (int start = 0; start < rows; start += chunkSize) {
                int n = Math.min(chunkSize, rows - start);
                writer.writeChunk(sliceChunk(columns, columnArrays, start, n));
            }
        }
    }

    private static DType.Struct buildSchema(List<ColumnDescriptor> columns) {
        DType.StructBuilder builder = DType.structBuilder();
        for (ColumnDescriptor col : columns) {
            builder.field(col.name(), col.dtype());
        }
        return builder.build();
    }

    private static Map<ColumnName, Object> sliceChunk(List<ColumnDescriptor> columns, Object[] columnArrays,
            int start, int n) {
        Map<ColumnName, Object> chunk = new LinkedHashMap<>();
        for (int c = 0; c < columns.size(); c++) {
            chunk.put(columns.get(c).name(), slice(columnArrays[c], start, n));
        }
        return chunk;
    }

    private static int indexOf(List<ColumnDescriptor> columns, String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().value().equals(name)) {
                return i;
            }
        }
        throw new IllegalArgumentException("--sort-by column '" + name + "' is not one of the declared columns");
    }

    private static int[] sortPermutation(Object array, int rows) {
        Integer[] indices = new Integer[rows];
        for (int i = 0; i < rows; i++) {
            indices[i] = i;
        }
        Comparator<Integer> byValue = switch (array) {
            case long[] a -> Comparator.comparingLong(i -> a[i]);
            case int[] a -> Comparator.comparingInt(i -> a[i]);
            case short[] a -> Comparator.comparingInt(i -> a[i]);
            case byte[] a -> Comparator.comparingInt(i -> a[i]);
            case double[] a -> Comparator.comparingDouble(i -> a[i]);
            case float[] a -> Comparator.comparingDouble(i -> a[i]);
            case String[] a -> Comparator.comparing(i -> a[i]);
            case boolean[] a -> Comparator.comparingInt(i -> a[i] ? 1 : 0);
            default -> throw new IllegalStateException("unsupported array type: " + array.getClass());
        };
        Arrays.sort(indices, byValue);
        int[] out = new int[rows];
        for (int i = 0; i < rows; i++) {
            out[i] = indices[i];
        }
        return out;
    }

    private static Object permute(Object array, int[] permutation) {
        int n = permutation.length;
        return switch (array) {
            case long[] a -> {
                long[] out = new long[n];
                for (int i = 0; i < n; i++) {
                    out[i] = a[permutation[i]];
                }
                yield out;
            }
            case int[] a -> {
                int[] out = new int[n];
                for (int i = 0; i < n; i++) {
                    out[i] = a[permutation[i]];
                }
                yield out;
            }
            case short[] a -> {
                short[] out = new short[n];
                for (int i = 0; i < n; i++) {
                    out[i] = a[permutation[i]];
                }
                yield out;
            }
            case byte[] a -> {
                byte[] out = new byte[n];
                for (int i = 0; i < n; i++) {
                    out[i] = a[permutation[i]];
                }
                yield out;
            }
            case double[] a -> {
                double[] out = new double[n];
                for (int i = 0; i < n; i++) {
                    out[i] = a[permutation[i]];
                }
                yield out;
            }
            case float[] a -> {
                float[] out = new float[n];
                for (int i = 0; i < n; i++) {
                    out[i] = a[permutation[i]];
                }
                yield out;
            }
            case String[] a -> {
                String[] out = new String[n];
                for (int i = 0; i < n; i++) {
                    out[i] = a[permutation[i]];
                }
                yield out;
            }
            case boolean[] a -> {
                boolean[] out = new boolean[n];
                for (int i = 0; i < n; i++) {
                    out[i] = a[permutation[i]];
                }
                yield out;
            }
            default -> throw new IllegalStateException("unsupported array type: " + array.getClass());
        };
    }

    private static Object slice(Object array, int start, int n) {
        return switch (array) {
            case long[] a -> Arrays.copyOfRange(a, start, start + n);
            case int[] a -> Arrays.copyOfRange(a, start, start + n);
            case short[] a -> Arrays.copyOfRange(a, start, start + n);
            case byte[] a -> Arrays.copyOfRange(a, start, start + n);
            case double[] a -> Arrays.copyOfRange(a, start, start + n);
            case float[] a -> Arrays.copyOfRange(a, start, start + n);
            case String[] a -> Arrays.copyOfRange(a, start, start + n);
            case boolean[] a -> Arrays.copyOfRange(a, start, start + n);
            default -> throw new IllegalStateException("unsupported array type: " + array.getClass());
        };
    }
}
