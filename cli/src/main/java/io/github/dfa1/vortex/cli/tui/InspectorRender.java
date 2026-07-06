package io.github.dfa1.vortex.cli.tui;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.ExtensionId;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.DecimalArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.GenericArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.reader.array.StructArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.extension.DateExtensionDecoder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;

/// Pure rendering helpers for [VortexInspectorTui]. Stateless string formatters
/// extracted from the event loop so they can be unit-tested against in-memory
/// arrays without a terminal, an [IoWorker], or an encoded fixture file.
final class InspectorRender {

    private InspectorRender() {
    }

    /// Formats one array element for the detail/data preview.
    ///
    /// @param array    array holding the value
    /// @param i        element index
    /// @param declared declared logical type (drives extension rendering)
    /// @return formatted value, or an angle-bracketed fallback for unknown shapes
    static String formatValue(Array array, int i, DType declared) {
        if (declared instanceof DType.Extension ext
                && ExtensionId.parse(ext.extensionId())
                        .filter(id -> id == ExtensionId.VORTEX_DATE)
                        .isPresent()) {
            try {
                return DateExtensionDecoder.INSTANCE.decode(array, i).toString();
            } catch (RuntimeException _) {
                // fall through to generic rendering on shape mismatch
            }
        }
        return switch (array) {
            // Long/IntArray have no dtype-aware getter, so gate the unsigned rendering on the
            // ptype; a U64/U32 high-half value would otherwise show as a negative decimal.
            case LongArray a -> isUnsigned(a)
                    ? Long.toUnsignedString(a.getLong(i))
                    : Long.toString(a.getLong(i));
            case IntArray a -> isUnsigned(a)
                    ? Integer.toUnsignedString(a.getInt(i))
                    : Integer.toString(a.getInt(i));
            // Short/ByteArray.getInt already zero-extends U16/U8, so a signed-decimal print of the
            // widened int is the correct unsigned value.
            case ShortArray a -> Integer.toString(a.getInt(i));
            case ByteArray a -> Integer.toString(a.getInt(i));
            case DoubleArray a -> Double.toString(a.getDouble(i));
            case FloatArray a -> Float.toString(a.getFloat(i));
            case BoolArray a -> Boolean.toString(a.getBoolean(i));
            case VarBinArray a -> a.dtype() instanceof DType.Utf8
                    ? "\"" + a.getString(i) + "\""
                    : bytesToShortHex(a.getBytes(i));
            case GenericArray a when a.dtype() instanceof DType.Decimal ->
                    tryDecimal(a::getDecimal, a, i);
            case DecimalArray a -> tryDecimal(a::getDecimal, a, i);
            default -> "<" + array.getClass().getSimpleName() + " " + array.dtype() + ">";
        };
    }

    /// Formats one struct of decoded zone-map statistics into a single display row.
    ///
    /// @param arr       stats array (possibly wrapped in a [MaskedArray])
    /// @param statsDtype struct schema describing the stats fields
    /// @return one `"field=value, ..."` string per stats row
    static List<String> formatStatsArray(Array arr, DType.Struct statsDtype) {
        Array unwrapped = arr instanceof MaskedArray m ? m.inner() : arr;
        if (!(unwrapped instanceof StructArray sa)) {
            // A single-field stats table (e.g. NULL_COUNT only) is decoded to the bare field, not
            // a StructArray (the struct decoder collapses one-field structs). Render that one stat.
            if (statsDtype.fieldTypes().size() == 1) {
                String name = statsDtype.fieldNames().getFirst().value();
                DType fdtype = statsDtype.fieldTypes().getFirst();
                int rowCount = (int) arr.length();
                List<String> rows = new ArrayList<>(rowCount);
                for (int row = 0; row < rowCount; row++) {
                    rows.add(name + "=" + formatStatsCell(arr, row, fdtype));
                }
                return rows;
            }
            throw new IllegalStateException(
                    "stats array is not a struct: " + arr.getClass().getSimpleName());
        }
        int n = (int) sa.length();
        List<String> rows = new ArrayList<>(n);
        for (int row = 0; row < n; row++) {
            StringBuilder sb = new StringBuilder();
            for (int f = 0; f < sa.fieldCount(); f++) {
                if (f > 0) {
                    sb.append(", ");
                }
                String name = statsDtype.fieldNames().get(f).value();
                DType fdtype = statsDtype.fieldTypes().get(f);
                Array field = sa.field(f);
                sb.append(name).append('=').append(formatStatsCell(field, row, fdtype));
            }
            rows.add(sb.toString());
        }
        return rows;
    }

    private static String formatStatsCell(Array field, int row, DType declared) {
        if (field instanceof MaskedArray m) {
            if (!m.isValid(row)) {
                return "null";
            }
            return formatValue(m.inner(), row, declared);
        }
        return formatValue(field, row, declared);
    }

    /// Whether the array's dtype is an unsigned integer, so high-half values must render as
    /// unsigned decimal rather than the two's-complement negative the raw signed getter returns.
    ///
    /// @param arr the value array under inspection
    /// @return `true` when the dtype is a `U8`–`U64` primitive
    private static boolean isUnsigned(Array arr) {
        return arr.dtype() instanceof DType.Primitive p && p.ptype().isUnsigned();
    }

    private static String tryDecimal(LongFunction<BigDecimal> reader, Array a, int i) {
        try {
            return reader.apply(i).toPlainString();
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("null cell")) {
                return "null";
            }
            return "<" + a.getClass().getSimpleName() + " " + a.dtype() + ">";
        }
    }

    private static String bytesToShortHex(byte[] bytes) {
        int n = Math.min(bytes.length, 16);
        StringBuilder sb = new StringBuilder(n * 3 + 2);
        sb.append("0x");
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%02x", bytes[i] & 0xff));
        }
        if (bytes.length > n) {
            sb.append("...");
        }
        return sb.toString();
    }

    /// Formats one 16-byte row of a hex dump: offset, hex columns, ASCII gutter.
    ///
    /// @param data   the bytes being dumped
    /// @param offset start offset of this row within `data`
    /// @return the formatted `"%08x  .. .. | .... |"` line
    static String formatHexRow(byte[] data, int offset) {
        StringBuilder sb = new StringBuilder(80);
        sb.append(String.format("%08x  ", offset));
        for (int i = 0; i < 16; i++) {
            int idx = offset + i;
            if (idx < data.length) {
                sb.append(String.format("%02x ", data[idx] & 0xff));
            } else {
                sb.append("   ");
            }
            if (i == 7) {
                sb.append(' ');
            }
        }
        sb.append(" |");
        for (int i = 0; i < 16; i++) {
            int idx = offset + i;
            if (idx >= data.length) {
                sb.append(' ');
                continue;
            }
            int b = data[idx] & 0xff;
            sb.append(b >= 0x20 && b < 0x7f ? (char) b : '.');
        }
        sb.append('|');
        return sb.toString();
    }

    /// Pads or truncates `s` to exactly `width` characters.
    ///
    /// @param s     source string
    /// @param width target width
    /// @return a string of length `width`
    static String pad(String s, int width) {
        if (s.length() >= width) {
            return s.substring(0, width);
        }
        return s + " ".repeat(width - s.length());
    }

    /// Truncates `s` to at most `width` characters.
    ///
    /// @param s     source string
    /// @param width maximum width
    /// @return `s` unchanged if short enough, otherwise its first `width` characters
    static String truncate(String s, int width) {
        return s.length() > width ? s.substring(0, width) : s;
    }
}
