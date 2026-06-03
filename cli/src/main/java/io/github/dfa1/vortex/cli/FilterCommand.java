package io.github.dfa1.vortex.cli;

import io.github.dfa1.vortex.core.array.BoolArray;
import io.github.dfa1.vortex.core.array.ByteArray;
import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.FloatArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.ShortArray;
import io.github.dfa1.vortex.core.array.VarBinArray;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.csv.CsvExporter;
import io.github.dfa1.vortex.csv.ExportOptions;
import io.github.dfa1.vortex.csv.RowPredicate;
import io.github.dfa1.vortex.scan.RowFilter;
import io.github.dfa1.vortex.scan.ScanOptions;

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

    private FilterCommand() {
    }

    private enum Op { GT, GTE, LT, LTE, EQ }

    private record ParsedFilter(String column, Op op, Comparable<?> value) {}

    private static final Pattern EXPR = Pattern.compile(
            "^(\\w[\\w.]*?)\\s*(>=|<=|==|>|<|=)\\s*(.+)$");

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
        ParsedFilter pf;
        try {
            pf = parseFilter(expr);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            return ExitStatus.USAGE_ERROR;
        }
        ScanOptions scanOptions = new ScanOptions(List.of(), toRowFilter(pf), ScanOptions.NO_LIMIT);
        RowPredicate rowPred = toRowPredicate(pf);
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

    private static ParsedFilter parseFilter(String expr) {
        Matcher m = EXPR.matcher(expr.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("invalid filter expression: \"" + expr
                    + "\"  expected: col op value  (op: >, >=, <, <=, =, ==)");
        }
        String col = m.group(1);
        Op op = switch (m.group(2)) {
            case ">"  -> Op.GT;
            case ">=" -> Op.GTE;
            case "<"  -> Op.LT;
            case "<=" -> Op.LTE;
            case "=", "==" -> Op.EQ;
            default -> throw new IllegalArgumentException("unknown operator: " + m.group(2));
        };
        Comparable<?> value = parseValue(m.group(3).trim());
        return new ParsedFilter(col, op, value);
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

    /// Zone-map RowFilter for chunk pruning. Conservative for strict ops (> / <):
    /// uses Gte/Lte which may keep one extra chunk at the boundary, but row-level
    /// predicate corrects that.
    private static RowFilter toRowFilter(ParsedFilter pf) {
        return switch (pf.op()) {
            case GT, GTE -> RowFilter.gte(pf.column(), pf.value());
            case LT, LTE -> RowFilter.lte(pf.column(), pf.value());
            case EQ      -> RowFilter.eq(pf.column(), pf.value());
        };
    }

    private static RowPredicate toRowPredicate(ParsedFilter pf) {
        return (chunk, rowIdx) -> {
            Array arr = chunk.column(pf.column());
            int cmp = compareValue(arr, rowIdx, pf.value());
            return switch (pf.op()) {
                case GT  -> cmp > 0;
                case GTE -> cmp >= 0;
                case LT  -> cmp < 0;
                case LTE -> cmp <= 0;
                case EQ  -> cmp == 0;
            };
        };
    }

    private static int compareValue(Array arr, long rowIdx, Comparable<?> value) {
        return switch (arr) {
            case LongArray la  -> compareNumeric(la.getLong(rowIdx), value);
            case IntArray ia   -> compareNumeric(ia.getInt(rowIdx), value);
            case ShortArray sa -> compareNumeric(sa.getShort(rowIdx), value);
            case ByteArray ba  -> compareNumeric(ba.getByte(rowIdx), value);
            case DoubleArray da -> compareDouble(da.getDouble(rowIdx), value);
            case FloatArray fa  -> compareDouble(fa.getFloat(rowIdx), value);
            case BoolArray ba  -> Boolean.compare(ba.getBoolean(rowIdx), (Boolean) value);
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
