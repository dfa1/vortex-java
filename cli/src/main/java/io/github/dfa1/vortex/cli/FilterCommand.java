package io.github.dfa1.vortex.cli;

import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.csv.CsvExporter;
import io.github.dfa1.vortex.csv.ExportOptions;
import io.github.dfa1.vortex.csv.RowPredicate;
import io.github.dfa1.vortex.reader.RowFilter;
import io.github.dfa1.vortex.reader.ScanOptions;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FilterCommand {

    private static final Pattern EXPR = Pattern.compile(
            "^(\\w[\\w.]*?)\\s*(!=|>=|<=|==|>|<|=)\\s*(.+)$");

    private FilterCommand() {
    }

    static int run(String[] args) {
        if (args.length < 3) {
            System.err.println("usage: filter <file.vortex> <expr>  (e.g. \"price >= 100\")");
            return ExitStatus.USAGE_ERROR;
        }
        Path path = Path.of(args[1]);
        if (!Files.exists(path)) {
            System.err.println("file not found: " + path);
            return ExitStatus.FILE_NOT_FOUND;
        }
        String expr = String.join(" ", Arrays.asList(args).subList(2, args.length));
        RowFilter filter;
        try {
            filter = parseFilter(expr);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            return ExitStatus.USAGE_ERROR;
        }
        ScanOptions scanOptions = new ScanOptions(List.of(), filter, ScanOptions.NO_LIMIT);
        RowPredicate rowPred = toRowPredicate(filter);
        try {
            Writer stdout = new OutputStreamWriter(System.out, StandardCharsets.UTF_8);
            CsvExporter.exportCsvFiltered(path, stdout, ExportOptions.defaults(), scanOptions, rowPred);
            stdout.flush();
            return ExitStatus.OK;
        } catch (IOException e) {
            System.err.println("error: " + e.getMessage());
            return ExitStatus.ERROR;
        }
    }

    private static RowFilter parseFilter(String expr) {
        Matcher m = EXPR.matcher(expr.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("invalid filter expression: \"" + expr
                                                       + "\"  expected: col op value  (op: >, >=, <, <=, =, ==, !=)");
        }
        String col = m.group(1);
        Comparable<?> value = parseValue(m.group(3).trim());
        return switch (m.group(2)) {
            case ">" -> RowFilter.gt(col, value);
            case ">=" -> RowFilter.gte(col, value);
            case "<" -> RowFilter.lt(col, value);
            case "<=" -> RowFilter.lte(col, value);
            case "=", "==" -> RowFilter.eq(col, value);
            case "!=" -> RowFilter.neq(col, value);
            default -> throw new IllegalArgumentException("unknown operator: " + m.group(2));
        };
    }

    private static Comparable<?> parseValue(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException ignored) {
        }
        if ("true".equalsIgnoreCase(raw)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return Boolean.FALSE;
        }
        return raw;
    }

    private static RowPredicate toRowPredicate(RowFilter filter) {
        return switch (filter) {
            case RowFilter.Gt(var col, var val) -> (chunk, rowIdx) -> compareValue(chunk.column(col), rowIdx, val) > 0;
            case RowFilter.Gte(var col, var val) ->
                    (chunk, rowIdx) -> compareValue(chunk.column(col), rowIdx, val) >= 0;
            case RowFilter.Lt(var col, var val) -> (chunk, rowIdx) -> compareValue(chunk.column(col), rowIdx, val) < 0;
            case RowFilter.Lte(var col, var val) ->
                    (chunk, rowIdx) -> compareValue(chunk.column(col), rowIdx, val) <= 0;
            case RowFilter.Eq(var col, var val) ->
                    (chunk, rowIdx) -> compareValue(chunk.column(col), rowIdx, (Comparable<?>) val) == 0;
            case RowFilter.Neq(var col, var val) ->
                    (chunk, rowIdx) -> compareValue(chunk.column(col), rowIdx, (Comparable<?>) val) != 0;
            case RowFilter.And(var filters) -> {
                RowPredicate[] preds = filters.stream().map(FilterCommand::toRowPredicate).toArray(RowPredicate[]::new);
                yield (chunk, rowIdx) -> {
                    for (RowPredicate p : preds) {
                        if (!p.test(chunk, rowIdx)) {
                            return false;
                        }
                    }
                    return true;
                };
            }
        };
    }

    private static int compareValue(Array arr, long rowIdx, Comparable<?> value) {
        return switch (arr) {
            case LongArray la -> compareNumeric(la.getLong(rowIdx), value);
            case IntArray ia -> compareNumeric(ia.getInt(rowIdx), value);
            case ShortArray sa -> compareNumeric(sa.getShort(rowIdx), value);
            case ByteArray ba -> compareNumeric(ba.getByte(rowIdx), value);
            case DoubleArray da -> compareDouble(da.getDouble(rowIdx), value);
            case FloatArray fa -> compareDouble(fa.getFloat(rowIdx), value);
            case BoolArray ba -> Boolean.compare(ba.getBoolean(rowIdx), (Boolean) value);
            case VarBinArray va -> {
                String v = va.getString(rowIdx);
                yield v.compareTo((String) value);
            }
            default -> throw new IllegalArgumentException(
                    "filter not supported for column type: " + arr.getClass().getSimpleName());
        };
    }

    private static int compareNumeric(long colVal, Comparable<?> value) {
        if (value instanceof Long l) {
            return Long.compare(colVal, l);
        }
        return Double.compare(colVal, (Double) value);
    }

    private static int compareDouble(double colVal, Comparable<?> value) {
        if (value instanceof Long l) {
            return Double.compare(colVal, l);
        }
        return Double.compare(colVal, (Double) value);
    }
}
