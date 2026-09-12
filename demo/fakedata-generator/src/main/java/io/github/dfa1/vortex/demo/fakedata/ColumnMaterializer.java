package io.github.dfa1.vortex.demo.fakedata;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;

import java.util.Arrays;
import java.util.Random;

/// Turns a [ColumnDescriptor]'s [GeneratorSpec] into a fully materialized column array, ready to
/// hand to a Vortex `Chunk` (`long[]`, `int[]`, ..., `double[]`, `String[]`, or `boolean[]`,
/// matching the column's declared [DType]).
final class ColumnMaterializer {

    private ColumnMaterializer() {
    }

    /// Fails fast at parse time if `generator` cannot produce values of `dtype`.
    ///
    /// @param dtype      the column's declared type
    /// @param generator  the generator to check
    /// @param descriptor the original descriptor string, for the error message
    /// @throws IllegalArgumentException if the combination is invalid
    static void validateCompatible(DType dtype, GeneratorSpec generator, String descriptor) {
        boolean numeric = dtype instanceof DType.Primitive;
        boolean ok = switch (generator) {
            case GeneratorSpec.Series _ -> numeric;
            case GeneratorSpec.Range _ -> numeric;
            case GeneratorSpec.Normal _ -> numeric;
            case GeneratorSpec.EnumLabels _ -> dtype instanceof DType.Utf8;
            case GeneratorSpec.RandomBool _ -> dtype instanceof DType.Bool;
            case GeneratorSpec.Constant(String literal) -> isValidConstant(dtype, literal);
        };
        if (!ok) {
            throw new IllegalArgumentException(
                    "generator incompatible with declared type in descriptor '%s'".formatted(descriptor));
        }
    }

    private static boolean isValidConstant(DType dtype, String literal) {
        if (dtype instanceof DType.Utf8) {
            return true;
        }
        if (dtype instanceof DType.Bool) {
            return literal.equalsIgnoreCase("true") || literal.equalsIgnoreCase("false");
        }
        try {
            Double.parseDouble(literal);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /// Materializes `rows` values for `column`.
    ///
    /// @param column the column to generate
    /// @param rows   number of rows to generate
    /// @param random shared random source (consumed in column-declaration order for determinism)
    /// @return the typed array (`long[]`/`int[]`/.../`double[]`, `String[]`, or `boolean[]`)
    static Object materialize(ColumnDescriptor column, int rows, Random random) {
        DType dtype = column.dtype();
        GeneratorSpec generator = column.generator();
        if (dtype instanceof DType.Utf8) {
            return materializeUtf8(generator, rows, random);
        }
        if (dtype instanceof DType.Bool) {
            return materializeBool(generator, rows, random);
        }
        PType ptype = ((DType.Primitive) dtype).ptype();
        double[] values = materializeNumeric(generator, rows, random);
        return narrow(ptype, values);
    }

    private static double[] materializeNumeric(GeneratorSpec generator, int rows, Random random) {
        double[] values = new double[rows];
        switch (generator) {
            case GeneratorSpec.Series(double start, double step) -> {
                for (int i = 0; i < rows; i++) {
                    values[i] = start + i * step;
                }
            }
            case GeneratorSpec.Range(double min, double max) -> {
                for (int i = 0; i < rows; i++) {
                    values[i] = min + random.nextDouble() * (max - min);
                }
            }
            case GeneratorSpec.Normal(double mean, double stddev) -> {
                for (int i = 0; i < rows; i++) {
                    values[i] = mean + random.nextGaussian() * stddev;
                }
            }
            case GeneratorSpec.Constant(String literal) -> Arrays.fill(values, Double.parseDouble(literal));
            case GeneratorSpec.EnumLabels _, GeneratorSpec.RandomBool _ ->
                    throw new IllegalStateException("unreachable: validated at parse time");
        }
        return values;
    }

    private static Object narrow(PType ptype, double[] values) {
        return switch (ptype) {
            case I64, U64 -> {
                long[] out = new long[values.length];
                for (int i = 0; i < values.length; i++) {
                    out[i] = Math.round(values[i]);
                }
                yield out;
            }
            case I32, U32 -> {
                int[] out = new int[values.length];
                for (int i = 0; i < values.length; i++) {
                    out[i] = (int) Math.round(values[i]);
                }
                yield out;
            }
            case I16, U16 -> {
                short[] out = new short[values.length];
                for (int i = 0; i < values.length; i++) {
                    out[i] = (short) Math.round(values[i]);
                }
                yield out;
            }
            case I8, U8 -> {
                byte[] out = new byte[values.length];
                for (int i = 0; i < values.length; i++) {
                    out[i] = (byte) Math.round(values[i]);
                }
                yield out;
            }
            case F64 -> values;
            case F32 -> {
                float[] out = new float[values.length];
                for (int i = 0; i < values.length; i++) {
                    out[i] = (float) values[i];
                }
                yield out;
            }
            case F16 -> throw new IllegalArgumentException("f16 is not a supported generator column type");
        };
    }

    private static String[] materializeUtf8(GeneratorSpec generator, int rows, Random random) {
        String[] out = new String[rows];
        switch (generator) {
            case GeneratorSpec.EnumLabels(String prefix, int count) -> {
                int width = String.valueOf(count - 1).length();
                String format = prefix + "%0" + width + "d";
                for (int i = 0; i < rows; i++) {
                    out[i] = format.formatted(random.nextInt(count));
                }
            }
            case GeneratorSpec.Constant(String literal) -> Arrays.fill(out, literal);
            case GeneratorSpec.Series _, GeneratorSpec.Range _, GeneratorSpec.Normal _, GeneratorSpec.RandomBool _ ->
                    throw new IllegalStateException("unreachable: validated at parse time");
        }
        return out;
    }

    private static boolean[] materializeBool(GeneratorSpec generator, int rows, Random random) {
        boolean[] out = new boolean[rows];
        switch (generator) {
            case GeneratorSpec.RandomBool() -> {
                for (int i = 0; i < rows; i++) {
                    out[i] = random.nextBoolean();
                }
            }
            case GeneratorSpec.Constant(String literal) -> Arrays.fill(out, Boolean.parseBoolean(literal));
            case GeneratorSpec.Series _, GeneratorSpec.Range _, GeneratorSpec.Normal _, GeneratorSpec.EnumLabels _ ->
                    throw new IllegalStateException("unreachable: validated at parse time");
        }
        return out;
    }
}
