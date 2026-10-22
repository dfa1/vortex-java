package io.github.dfa1.vortex.cli.tui;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.ExtensionId;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.DecimalArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.Float16Array;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.extension.DateExtensionDecoder;
import io.github.dfa1.vortex.reader.extension.TimeExtensionDecoder;
import io.github.dfa1.vortex.reader.extension.TimestampExtensionDecoder;
import io.github.dfa1.vortex.reader.extension.UuidExtensionDecoder;

import java.util.Optional;

/// Pure cell-formatting helpers for the grid viewer. Stateless: turns one decoded
/// array element into the display string shown in a grid cell. Extracted from
/// [LazyGridSource] so the per-type and per-extension branches can be unit-tested
/// against in-memory arrays without a terminal or an encoded fixture.
final class GridRender {

    private GridRender() {
    }

    /// Formats the element at logical index `i` of `array` for display.
    ///
    /// Returns the empty string for out-of-range indices and masked-out (null)
    /// cells. Extension types (date/time/timestamp/uuid) are rendered via their
    /// decoders; on any decode failure an angle-bracketed diagnostic is returned.
    ///
    /// @param array    the column array (may be a [MaskedArray])
    /// @param i        logical element index
    /// @param declared declared logical type (drives extension rendering)
    /// @return formatted cell text
    static String formatCell(Array array, long i, DType declared) {
        if (array == null || i >= array.length()) {
            return "";
        }
        if (array instanceof MaskedArray m && !m.isValid(i)) {
            return "";
        }
        Array inner = array instanceof MaskedArray m ? m.inner() : array;
        if (i >= inner.length()) {
            return "";
        }
        try {
            if (declared instanceof DType.Extension ext) {
                Optional<String> extFormatted = formatExtension(ext, inner, i);
                if (extFormatted.isPresent()) {
                    return extFormatted.get();
                }
            }
            return switch (inner) {
                case VarBinArray a -> a.dtype() instanceof DType.Utf8 ? a.getString(i) : bytesToHex(a.getBytes(i));
                case DecimalArray a -> a.getDecimal(i).toPlainString();
                default -> formatPrimitive(inner, i).orElse("<" + inner.getClass().getSimpleName() + ">");
            };
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            return "<" + e.getClass().getSimpleName()
                    + (msg != null ? ": " + msg.split("\n", 2)[0] : "") + ">";
        }
    }

    /// Formats the element at `i` of a numeric or boolean leaf array, shared by the grid and the
    /// inspector, which differ only in how they render strings, bytes and decimals.
    ///
    /// @param array the leaf value array (never a [MaskedArray]; the caller unwraps first)
    /// @param i     element index
    /// @return the formatted value, or empty for any other array shape
    static Optional<String> formatPrimitive(Array array, long i) {
        return switch (array) {
            // Long/IntArray have no dtype-aware getter, so gate the unsigned rendering on the
            // ptype; a U64/U32 high-half value would otherwise show as a negative decimal
            // (e.g. a U8 magnesium column widened to U64 with the sign bit set).
            case LongArray a -> Optional.of(isUnsigned(a) ? Long.toUnsignedString(a.getLong(i)) : Long.toString(a.getLong(i)));
            case IntArray a -> Optional.of(isUnsigned(a) ? Integer.toUnsignedString(a.getInt(i)) : Integer.toString(a.getInt(i)));
            // Short/ByteArray.getInt already zero-extends U16/U8, so widening to int and
            // printing signed decimal yields the correct unsigned value.
            case ShortArray a -> Optional.of(Integer.toString(a.getInt(i)));
            case ByteArray a -> Optional.of(Integer.toString(a.getInt(i)));
            case DoubleArray a -> Optional.of(Double.toString(a.getDouble(i)));
            case FloatArray a -> Optional.of(Float.toString(a.getFloat(i)));
            case Float16Array a -> Optional.of(Float.toString(a.getFloat(i)));
            case BoolArray a -> Optional.of(Boolean.toString(a.getBoolean(i)));
            default -> Optional.empty();
        };
    }

    /// Whether the array's dtype is an unsigned integer, so high-half values must render as
    /// unsigned decimal rather than the two's-complement negative the raw signed getter returns.
    ///
    /// @param arr the leaf value array (never a [MaskedArray]; the caller unwraps first)
    /// @return `true` when the dtype is a `U8`–`U64` primitive
    private static boolean isUnsigned(Array arr) {
        return arr.dtype() instanceof DType.Primitive p && p.ptype().isUnsigned();
    }

    private static Optional<String> formatExtension(DType.Extension ext, Array storage, long i) {
        if (!(ExtensionId.parse(ext.extensionId()) instanceof ExtensionId.WellKnown id)) {
            return Optional.empty();
        }
        return switch (id) {
            case VORTEX_DATE -> Optional.of(DateExtensionDecoder.INSTANCE.decode(ext, storage, i).toString());
            case VORTEX_TIME -> Optional.of(TimeExtensionDecoder.INSTANCE.decode(ext, storage, i).toString());
            case VORTEX_TIMESTAMP -> Optional.of(TimestampExtensionDecoder.INSTANCE.instant(ext, storage, i).toString());
            case VORTEX_UUID -> Optional.of(UuidExtensionDecoder.INSTANCE.decode(storage, i).toString());
            // JSON text and geometries have no Java domain type yet: render the storage
            default -> Optional.empty();
        };
    }

    /// Renders up to the first 16 bytes as `0x`-prefixed hex, with `...` when there are more.
    ///
    /// @param bytes the bytes to render
    /// @return the hex preview
    static String bytesToHex(byte[] bytes) {
        int n = Math.min(bytes.length, 16);
        StringBuilder sb = new StringBuilder(n * 2 + 2);
        sb.append("0x");
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%02x", bytes[i] & 0xff));
        }
        if (bytes.length > n) {
            sb.append("...");
        }
        return sb.toString();
    }
}
