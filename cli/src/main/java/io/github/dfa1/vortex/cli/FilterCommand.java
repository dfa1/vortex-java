package io.github.dfa1.vortex.cli;

import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
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

@SuppressWarnings("java:S106") // CLI command: stdout is the intended output channel
final class FilterCommand {

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
        } catch (IOException | io.github.dfa1.vortex.core.VortexException e) {
            // VortexException is unchecked but surfaces user-facing failures (e.g. unknown
            // column on a typo'd filter); catching it here keeps the CLI from dumping a
            // stack trace and lets shell pipelines branch on the exit code.
            System.err.println("error: " + e.getMessage());
            return ExitStatus.ERROR;
        }
    }

    private static RowFilter parseFilter(String expr) {
        String trimmed = expr.trim();
        int opStart = indexOfOperator(trimmed);
        if (opStart <= 0) {
            throw invalid(expr);
        }
        int opEnd = operatorEnd(trimmed, opStart);
        String op = trimmed.substring(opStart, opEnd);
        String col = trimmed.substring(0, opStart).stripTrailing();
        String rawValue = trimmed.substring(opEnd).stripLeading();
        if (col.isEmpty() || !isValidColumnName(col) || rawValue.isEmpty()) {
            throw invalid(expr);
        }
        Comparable<?> value = parseValue(rawValue);
        return switch (op) {
            case ">" -> RowFilter.gt(col, value);
            case ">=" -> RowFilter.gte(col, value);
            case "<" -> RowFilter.lt(col, value);
            case "<=" -> RowFilter.lte(col, value);
            case "=", "==" -> RowFilter.eq(col, value);
            case "!=" -> RowFilter.neq(col, value);
            default -> throw new IllegalArgumentException("unknown operator: " + op);
        };
    }

    /// Scan-based parsing replaces the previous regex
    /// `^(\w[\w.]*)\s*(!=|>=|<=|==|>|<|=)\s*(.+)$` which Sonar flagged as a
    /// potential regex-DoS (S5852). Linear scan, no backtracking, no engine surface.
    private static int indexOfOperator(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '!' || c == '=' || c == '>' || c == '<') {
                return i;
            }
        }
        return -1;
    }

    /// Returns the index one past the operator: 2 chars for `!=, >=, <=, ==`,
    /// 1 char for the single-character operators `>, <, =`.
    private static int operatorEnd(String s, int start) {
        if (start + 1 < s.length() && s.charAt(start + 1) == '=') {
            return start + 2;
        }
        return start + 1;
    }

    private static boolean isValidColumnName(String s) {
        if (!Character.isLetterOrDigit(s.charAt(0)) && s.charAt(0) != '_') {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '.') {
                return false;
            }
        }
        return true;
    }

    private static IllegalArgumentException invalid(String expr) {
        return new IllegalArgumentException("invalid filter expression: \"" + expr
                + "\"  expected: col op value  (op: >, >=, <, <=, =, ==, !=)");
    }

    private static Comparable<?> parseValue(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException _) { // not this type; try the next
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException _) { // not this type; try the next
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
            case RowFilter.IsNull(var col) -> (chunk, rowIdx) -> isRowNull(chunk.column(col), rowIdx);
            case RowFilter.IsNotNull(var col) -> (chunk, rowIdx) -> !isRowNull(chunk.column(col), rowIdx);
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

    // Only a masked column carries nulls; an unmasked array is null-free, so every row is non-null.
    private static boolean isRowNull(Array arr, long rowIdx) {
        return arr instanceof MaskedArray masked && !masked.isValid(rowIdx);
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
