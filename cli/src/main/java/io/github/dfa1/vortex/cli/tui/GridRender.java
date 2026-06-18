package io.github.dfa1.vortex.cli.tui;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.extension.ExtensionId;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.DecimalArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
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
                case LongArray a -> Long.toString(a.getLong(i));
                case IntArray a -> Integer.toString(a.getInt(i));
                case ShortArray a -> Short.toString(a.getShort(i));
                case ByteArray a -> Byte.toString(a.getByte(i));
                case DoubleArray a -> Double.toString(a.getDouble(i));
                case FloatArray a -> Float.toString(a.getFloat(i));
                case BoolArray a -> Boolean.toString(a.getBoolean(i));
                case VarBinArray a -> a.dtype() instanceof DType.Utf8
                        ? a.getString(i)
                        : bytesToHex(a.getBytes(i));
                case DecimalArray a -> a.getDecimal(i).toPlainString();
                default -> "<" + inner.getClass().getSimpleName() + ">";
            };
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            return "<" + e.getClass().getSimpleName()
                    + (msg != null ? ": " + msg.split("\n", 2)[0] : "") + ">";
        }
    }

    private static Optional<String> formatExtension(DType.Extension ext, Array storage, long i) {
        Optional<ExtensionId> idOpt = ExtensionId.parse(ext.extensionId());
        if (idOpt.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(switch (idOpt.get()) {
            case VORTEX_DATE -> DateExtensionDecoder.INSTANCE.decode(storage, i).toString();
            case VORTEX_TIME -> TimeExtensionDecoder.INSTANCE.decode(ext, storage, i).toString();
            case VORTEX_TIMESTAMP -> TimestampExtensionDecoder.INSTANCE.instant(ext, storage, i).toString();
            case VORTEX_UUID -> UuidExtensionDecoder.INSTANCE.decode(storage, i).toString();
        });
    }

    private static String bytesToHex(byte[] bytes) {
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
